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
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Java agent for Minecraft 1.12.2 servers (vanilla, carpet). Publishes tick times, loaded chunks
 * and optionally the server key pair as small files in a directory (meant for tmpfs, e.g. /run).
 *
 * <pre>java -javaagent:mcprobe.jar=dir=/run/mcprobe/survival[,ticks=1000][,chunks=10000][,key=true] -jar server.jar</pre>
 *
 * Read-only: no class transformation, nothing is written into the server. All work happens on one
 * low-priority daemon thread; only the chunk snapshot (copying coordinates) runs on the server thread.
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
	private static final String M_SCHEDULE = "a";     // MinecraftServer.callFromMainThread(Callable)
	private static final String F_PROVIDER = "s";     // World.provider
	private static final String M_DIM_TYPE = "q";     // WorldProvider.getDimensionType()
	private static final String M_DIM_ID = "a";       // DimensionType.getId()
	private static final String M_DIM_NAME = "b";     // DimensionType.getName()
	private static final String M_CHUNK_PROVIDER = "r"; // WorldServer.getChunkProvider()
	private static final String F_CHUNK_MAP = "e";    // ChunkProviderServer.loadedChunks  Long2ObjectMap<Chunk>

	private final Path dir;
	private final long ticksMillis;
	private final long chunksMillis;
	private final boolean key;
	private final String pid;

	private Object server;
	private Field tickTimes;
	private Field tickCount;
	private boolean keyWritten;
	private String lastError;

	private McProbe(Path dir, long ticksMillis, long chunksMillis, boolean key) {
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
			long ticks = 1000, chunks = 10000;
			boolean key = false;
			for (String part : (args == null ? "" : args).split(",")) {
				String[] kv = part.trim().split("=", 2);
				if (kv.length != 2) continue;
				switch (kv[0].trim()) {
					case "dir": dir = kv[1].trim(); break;
					case "ticks": ticks = Long.parseLong(kv[1].trim()); break;
					case "chunks": chunks = Long.parseLong(kv[1].trim()); break;
					case "key": key = Boolean.parseBoolean(kv[1].trim()); break;
					default: log("ignoring unknown option " + kv[0]);
				}
			}
			if (dir == null) throw new IllegalArgumentException("missing dir=<directory>");
			McProbe probe = new McProbe(Paths.get(dir), ticks, chunks, key);
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

	private void writeTicks(long now) throws Exception {
		long[] ring = ((long[]) tickTimes.get(server)).clone();
		int newest = Math.floorMod(tickCount.getInt(server), ring.length);
		StringBuilder json = header(now).append(",\"tick\":").append(tickCount.getInt(server));
		json.append(",\"tickNanos\":[");
		for (int i = 1; i <= ring.length; i++) { // oldest -> newest
			if (i > 1) json.append(',');
			json.append(ring[(newest + i) % ring.length]);
		}
		write("ticks.json", json.append("]}\n").toString(), false);
	}

	private void writeChunks(long now) throws Exception {
		Object srv = server;
		Method schedule = srv.getClass().getMethod(M_SCHEDULE, Callable.class);
		// The chunk map is not thread-safe: on the server thread only copy its keys
		// (one long per chunk, x in the low and z in the high 32 bits) with a single
		// toLongArray(); decoding and JSON happen here, off the server thread.
		Callable<List<Object[]>> task = () -> {
			List<Object[]> dims = new ArrayList<>();
			for (Object world : (Object[]) field(srv, F_WORLDS)) {
				if (world == null) continue;
				Object type = invoke(field(world, F_PROVIDER), M_DIM_TYPE);
				Object keys = invoke(field(invoke(world, M_CHUNK_PROVIDER), F_CHUNK_MAP), "keySet");
				dims.add(new Object[] { invoke(type, M_DIM_ID), invoke(type, M_DIM_NAME), invoke(keys, "toLongArray") });
			}
			return dims;
		};
		Future<?> future = (Future<?>) schedule.invoke(srv, task);
		List<?> dims;
		try {
			dims = (List<?>) future.get(5, TimeUnit.SECONDS);
		} finally {
			future.cancel(false);
		}
		StringBuilder json = header(now).append(",\"dimensions\":[");
		for (int d = 0; d < dims.size(); d++) {
			Object[] dim = (Object[]) dims.get(d);
			long[] keys = (long[]) dim[2];
			if (d > 0) json.append(',');
			json.append("{\"id\":").append(dim[0]).append(",\"name\":\"").append(dim[1])
					.append("\",\"loaded\":").append(keys.length).append(",\"chunks\":[");
			for (int i = 0; i < keys.length; i++) {
				if (i > 0) json.append(',');
				json.append('[').append((int) keys[i]).append(',').append((int) (keys[i] >>> 32)).append(']');
			}
			json.append("]}");
		}
		write("chunks.json", json.append("]}\n").toString(), false);
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

	// Reflection lookups are cached: they would otherwise dominate the time spent on the server thread.
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
			method.setAccessible(true); // public methods of non-public classes (fastutil key sets)
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
		for (String name : new String[] { "ticks.json", "chunks.json", "key.json" }) {
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
