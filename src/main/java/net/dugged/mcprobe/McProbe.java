package net.dugged.mcprobe;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import one.profiler.AsyncProfiler;

/**
 * Java agent for Minecraft 1.12.2 servers (vanilla, carpet). Publishes tick times, loaded chunks
 * and optionally the server key pair as small files in a directory (meant for tmpfs, e.g. /run).
 *
 * <pre>java -javaagent:mcprobe.jar=dir=/run/mcprobe/survival[,ticks=1000][,chunks=1000][,key=true] -jar server.jar</pre>
 *
 * Read-only: no class transformation, nothing is written into the server, and no work is ever
 * scheduled on the server thread: everything runs on one low-priority daemon thread.
 * The agent never prevents the server from starting: every error is logged and swallowed.
 */
public final class McProbe implements Runnable {
	// Obfuscated 1.12.2 names (verified with javap against the vanilla server jar).
	private static final String SERVER_THREAD = "Server thread";
	private static final String F_TICK_TIMES = "h";   // MinecraftServer.tickTimeArray  long[100]
	private static final String F_TICK_COUNT = "y";   // MinecraftServer.tickCounter    int
	private static final String F_WORLDS = "d";       // MinecraftServer.worlds         WorldServer[]
	private static final String M_STOPPED = "al";     // MinecraftServer.isServerStopped()
	private static final String M_KEY_PAIR = "O";     // MinecraftServer.getKeyPair()
	private static final String F_PROVIDER = "s";     // World.provider
	private static final String M_DIM_TYPE = "q";     // WorldProvider.getDimensionType()
	private static final String M_DIM_ID = "a";       // DimensionType.getId()
	private static final String M_DIM_NAME = "b";     // DimensionType.getName()
	private static final String M_CHUNK_PROVIDER = "r"; // WorldServer.getChunkProvider()
	private static final String F_CHUNK_MAP = "e";    // ChunkProviderServer.loadedChunks  Long2ObjectOpenHashMap<Chunk>
	// (fastutil's own fields key/value/size are not obfuscated)

	private final Path dir;
	private final long ticksMillis;
	private final long chunksMillis;
	private final boolean key;
	private final String pid;

	private Object server;
	private Field tickTimes;
	private Field tickCount;
	private boolean keyWritten;
	private final String profilerLib, profilerEvent;
	private AsyncProfiler profiler; // loaded on first use
	private long profileStart, nextProfile;
	private long lastChunks;
	private String lastError;

	private McProbe(Path dir, long ticksMillis, long chunksMillis, boolean key, String profilerLib, String profilerEvent) {
		this.profilerLib = profilerLib;
		this.profilerEvent = profilerEvent;
		this.dir = dir;
		this.ticksMillis = ticksMillis;
		this.chunksMillis = chunksMillis;
		this.key = key;
		String name = ManagementFactory.getRuntimeMXBean().getName();
		this.pid = name.contains("@") ? name.substring(0, name.indexOf('@')) : name;
	}

	public static void premain(String args, Instrumentation inst) {
		try {
			String dir = null;
			long ticks = 1000, chunks = 1000;
			boolean key = false;
			String profiler = null, profilerEvent = "itimer";
			for (String part : (args == null ? "" : args).split(",")) {
				String[] kv = part.trim().split("=", 2);
				if (kv.length != 2) continue;
				switch (kv[0].trim()) {
					case "dir": dir = kv[1].trim(); break;
					case "ticks": ticks = Long.parseLong(kv[1].trim()); break;
					case "chunks": chunks = Long.parseLong(kv[1].trim()); break;
					case "key": key = Boolean.parseBoolean(kv[1].trim()); break;
					case "profiler": profiler = kv[1].trim(); break;
					case "profilerEvent": profilerEvent = kv[1].trim(); break;
					default: log("ignoring unknown option " + kv[0]);
				}
			}
			if (dir == null) throw new IllegalArgumentException("missing dir=<directory>");
			McProbe probe = new McProbe(Paths.get(dir), ticks, chunks, key, profiler, profilerEvent);
			Files.createDirectories(probe.dir);
			probe.cleanup();
			Runtime.getRuntime().addShutdownHook(new Thread(probe::cleanup, "mcprobe-cleanup"));
			Thread thread = new Thread(probe, "mcprobe");
			thread.setDaemon(true);
			thread.setPriority(Thread.MIN_PRIORITY);
			thread.start();
			log("publishing to " + dir);
		} catch (Throwable error) {
			log("disabled: " + error);
		}
	}

	@Override
	public void run() {
		long nextTicks = 0, nextChunks = 0;
		while (true) {
			long sleep = 1000;
			try {
				if (server == null) {
					server = findServer();
					if (server != null) log("attached to the server thread");
				}
				if (server != null) {
					if ((Boolean) invoke(server, M_STOPPED)) {
						cleanup();
						return;
					}
					long now = System.currentTimeMillis();
					if (ticksMillis > 0 && now >= nextTicks) {
						writeTicks(now);
						nextTicks = now + ticksMillis;
					}
					if (chunksMillis > 0 && now >= nextChunks) {
						writeChunks(now);
						nextChunks = now + chunksMillis;
					}
					if (key && !keyWritten) writeKey();
					long next = Math.min(ticksMillis > 0 ? nextTicks : Long.MAX_VALUE,
							chunksMillis > 0 ? nextChunks : Long.MAX_VALUE);
					if (profilerLib != null) next = Math.min(next, profile(now));
					sleep = Math.max(10, Math.min(1000, next - System.currentTimeMillis()));
				}
				lastError = null;
			} catch (Throwable error) {
				String message = String.valueOf(error.getCause() != null ? error.getCause() : error);
				if (!message.equals(lastError)) log("not ready: " + message);
				lastError = message;
			}
			try {
				Thread.sleep(sleep);
			} catch (InterruptedException e) {
				return;
			}
		}
	}

	// --- data -------------------------------------------------------------------------------

	/**
	 * The server increments its tick counter at the start of a tick but stores the tick's duration
	 * at the end, so the slot of the current tick is ambiguous from outside (finished, or still the
	 * value from 100 ticks ago). Only ticks that are certainly finished are published: up to the
	 * last 99, oldest first, without the never-written slots after startup.
	 */
	private void writeTicks(long now) throws Exception {
		long[] ring;
		int tick;
		do { // retry if a tick ended while copying
			tick = tickCount.getInt(server);
			ring = ((long[]) tickTimes.get(server)).clone();
		} while (tickCount.getInt(server) != tick);
		int count = Math.max(0, Math.min(ring.length - 1, tick - 1));
		StringBuilder json = header(now).append(",\"tick\":").append(tick).append(",\"tickNanos\":[");
		for (int t = tick - count; t < tick; t++) { // ticks tick-count .. tick-1
			if (t > tick - count) json.append(',');
			json.append(ring[Math.floorMod(t, ring.length)]);
		}
		write("ticks.json", json.append("]}\n").toString(), false);
	}

	private void writeChunks(long now) throws Exception {
		List<Object[]> dims = new ArrayList<>();
		for (Object world : (Object[]) field(server, F_WORLDS)) {
			if (world == null) continue;
			Object type = invoke(field(world, F_PROVIDER), M_DIM_TYPE);
			long[] keys = readKeys(field(invoke(world, M_CHUNK_PROVIDER), F_CHUNK_MAP));
			if (keys == null) return; // map kept changing while reading: try again next interval
			dims.add(new Object[] { invoke(type, M_DIM_ID), invoke(type, M_DIM_NAME), keys });
		}

		// skip the write if no dimension's set of loaded chunks changed (keys are sorted)
		long fingerprint = dims.size();
		for (Object entry : dims) {
			Object[] dim = (Object[]) entry;
			long[] keys = (long[]) dim[2];
			fingerprint = fingerprint * 31 + (Integer) dim[0];
			for (long key : keys) fingerprint = fingerprint * 31 + key;
		}
		if (fingerprint == lastChunks) return;

		StringBuilder json = header(now).append(",\"dimensions\":[");
		for (int d = 0; d < dims.size(); d++) {
			Object[] dim = (Object[]) dims.get(d);
			long[] keys = (long[]) dim[2];
			StringBuilder chunks = new StringBuilder(keys.length * 10), lazy = new StringBuilder();
			int lazyCount = 0;
			for (long key : keys) {
				int x = (int) key, z = (int) (key >>> 32);
				String xz = "[" + x + "," + z + "]";
				if (chunks.length() > 0) chunks.append(',');
				chunks.append(xz);
				if (!entityProcessing(keys, x, z)) {
					if (lazy.length() > 0) lazy.append(',');
					lazy.append(xz);
					lazyCount++;
				}
			}
			if (d > 0) json.append(',');
			json.append("{\"id\":").append(dim[0]).append(",\"name\":\"").append(dim[1])
					.append("\",\"loaded\":").append(keys.length).append(",\"lazy\":").append(lazyCount)
					.append(",\"chunks\":[").append(chunks).append("],\"lazyChunks\":[").append(lazy).append("]}");
		}
		write("chunks.json", json.append("]}\n").toString(), false);
		lastChunks = fingerprint;
	}

	/**
	 * Reads the keys of the chunk map (fastutil Long2ObjectOpenHashMap) from this thread, without
	 * involving the server thread at all. The map is not thread-safe, so a read can overlap a chunk
	 * load/unload; such a read is detected (arrays replaced, size changed, entry count or duplicates
	 * not matching) and retried after a short pause; the server only changes the map at certain
	 * points of a tick and not while it sleeps between ticks. Returns sorted keys, or null if no
	 * consistent read succeeded within ~100 ms.
	 */
	private static long[] readKeys(Object map) throws Exception {
		for (int attempt = 0; attempt < 50; attempt++) {
			if (attempt > 0) Thread.sleep(2);
			int size = (Integer) field(map, "size");
			long[] key = (long[]) field(map, "key");
			Object[] value = (Object[]) field(map, "value");
			if (key.length != value.length) continue; // caught in the middle of a rehash
			long[] out = new long[size];
			int n = 0;
			for (int i = 0; i < key.length && n <= size; i++) {
				if (value[i] == null) continue;
				if (n == size) { n++; break; } // more entries than size: inconsistent
				out[n++] = key[i];
			}
			if (n != size || field(map, "key") != key || field(map, "value") != value
					|| (Integer) field(map, "size") != size) continue;
			Arrays.sort(out);
			boolean duplicate = false;
			for (int i = 1; i < out.length && !duplicate; i++) duplicate = out[i] == out[i - 1];
			if (!duplicate) return out;
		}
		return null;
	}

	/**
	 * 1.12 updates an entity only if every chunk within 2 chunks of it is loaded
	 * (World.updateEntityWithOptionalForce checks a +-32 block area). Loaded chunks
	 * failing this are "lazy": blocks and tile entities tick, entities do not.
	 */
	private static boolean entityProcessing(long[] sortedKeys, int x, int z) {
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				if (Arrays.binarySearch(sortedKeys, chunkKey(x + dx, z + dz)) < 0) return false;
			}
		}
		return true;
	}

	/** ChunkPos.asLong: x in the low, z in the high 32 bits. */
	private static long chunkKey(int x, int z) {
		return (x & 0xFFFFFFFFL) | (z & 0xFFFFFFFFL) << 32;
	}

	private void writeKey() throws Exception {
		KeyPair pair = (KeyPair) invoke(server, M_KEY_PAIR);
		if (pair == null) return; // generated during startup
		Base64.Encoder b64 = Base64.getEncoder();
		write("key.json", header(System.currentTimeMillis())
				.append(",\"algorithm\":\"").append(pair.getPublic().getAlgorithm())
				.append("\",\"publicX509\":\"").append(b64.encodeToString(pair.getPublic().getEncoded()))
				.append("\",\"privatePkcs8\":\"").append(b64.encodeToString(pair.getPrivate().getEncoded()))
				.append("\"}\n").toString(), true);
		keyWritten = true;
	}

	// --- profiler ---------------------------------------------------------------------------

	private static final long PROFILE_EVERY = 1000;   // ms between profile.collapsed updates
	private static final long PROFILE_IDLE = 30_000;  // stop when profile.request is older

	/**
	 * On-demand profiling of the server thread with async-profiler (CPU time (itimer), 2 ms per sample, stacks per thread; no attach, no safepoints):
	 * while a reader keeps touching profile.request, the stacks sampled since the start are written
	 * to profile.collapsed ("# start=<ms>" + collapsed stacks) every second; the sampling itself and
	 * building the dump happen outside the server thread. Returns the next due time.
	 */
	private long profile(long now) throws Exception {
		long requested;
		try {
			requested = Files.getLastModifiedTime(dir.resolve("profile.request")).toMillis();
		} catch (IOException noRequest) {
			requested = 0;
		}
		boolean wanted = now - requested < PROFILE_IDLE;
		if (!wanted) {
			if (profileStart != 0) {
				profiler.execute("stop");
				profileStart = 0;
				Files.deleteIfExists(dir.resolve("profile.collapsed"));
				log("profiler stopped");
			}
			return now + PROFILE_EVERY;
		}
		if (profileStart == 0) {
			if (profiler == null) profiler = AsyncProfiler.getInstance(profilerLib);
			// all threads are sampled (thread filters do not work with itimer); stacks carry their thread
			// as first frame and the dumps keep only the server thread (include=)
			profiler.execute("start,event=" + profilerEvent + ",interval=2ms,threads");
			profileStart = now;
			nextProfile = now;
			log("profiler started");
		}
		if (now >= nextProfile) {
			write("profile.collapsed", "# start=" + profileStart + "\n" + profiler.execute("collapsed,samples,sig,include=*Server thread*"), false);
			nextProfile = now + PROFILE_EVERY;
		}
		return nextProfile;
	}

	private StringBuilder header(long now) {
		return new StringBuilder(4096).append("{\"pid\":").append(pid).append(",\"time\":").append(now);
	}

	// --- server lookup ----------------------------------------------------------------------

	private Object findServer() throws Exception {
		Field target = Thread.class.getDeclaredField("target");
		target.setAccessible(true);
		ThreadGroup root = Thread.currentThread().getThreadGroup();
		while (root.getParent() != null) root = root.getParent();
		Thread[] threads = new Thread[root.activeCount() + 16];
		int count = root.enumerate(threads, true);
		for (int i = 0; i < count; i++) {
			Thread thread = threads[i];
			if (!SERVER_THREAD.equals(thread.getName())) continue;
			Object candidate = target.get(thread);
			if (candidate == null) continue;
			Field times = findField(candidate.getClass(), F_TICK_TIMES);
			if (times == null || times.getType() != long[].class) continue;
			tickTimes = times;
			tickCount = findField(candidate.getClass(), F_TICK_COUNT);
			if (tickCount == null || tickCount.getType() != int.class) {
				throw new IllegalStateException("unsupported server: no tick counter");
			}
			return candidate;
		}
		return null;
	}

	private static Field findField(Class<?> type, String name) {
		for (Class<?> c = type; c != null; c = c.getSuperclass()) {
			try {
				Field field = c.getDeclaredField(name);
				field.setAccessible(true);
				return field;
			} catch (NoSuchFieldException ignored) {
			}
		}
		return null;
	}

	// Reflection lookups are cached: they would otherwise dominate the agent's own time.
	private static final Map<String, Field> FIELDS = new ConcurrentHashMap<>();
	private static final Map<String, Method> METHODS = new ConcurrentHashMap<>();

	private static Object field(Object target, String name) throws Exception {
		Class<?> type = target.getClass();
		Field field = FIELDS.get(type.getName() + "." + name);
		if (field == null) {
			field = findField(type, name);
			if (field == null) throw new NoSuchFieldException(type.getName() + "." + name);
			FIELDS.put(type.getName() + "." + name, field);
		}
		return field.get(target);
	}

	private static Object invoke(Object target, String name) throws Exception {
		Class<?> type = target.getClass();
		Method method = METHODS.get(type.getName() + "." + name);
		if (method == null) {
			method = type.getMethod(name);
			method.setAccessible(true);
			METHODS.put(type.getName() + "." + name, method);
		}
		return method.invoke(target);
	}

	// --- output -----------------------------------------------------------------------------

	/** Atomic replace: readers never see a partial file. */
	private void write(String name, String content, boolean secret) throws IOException {
		Path tmp = dir.resolve("." + name + ".tmp");
		Files.deleteIfExists(tmp);
		if (secret) Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
		Files.write(tmp, content.getBytes(StandardCharsets.UTF_8));
		Files.move(tmp, dir.resolve(name), StandardCopyOption.ATOMIC_MOVE);
	}

	private void cleanup() {
		for (String name : new String[] { "ticks.json", "chunks.json", "key.json", "profile.collapsed" }) {
			try {
				Files.deleteIfExists(dir.resolve(name));
			} catch (IOException ignored) {
			}
		}
	}

	private static void log(String message) {
		System.out.println("[mcprobe] " + message);
	}
}
