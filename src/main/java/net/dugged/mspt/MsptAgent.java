package net.dugged.mspt;

import com.sun.tools.attach.VirtualMachine;

import java.io.*;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

public final class MsptAgent {
	private static final DecimalFormat MSPT_FORMAT = new DecimalFormat("0.00",
			DecimalFormatSymbols.getInstance(Locale.US));
	private static final Object LOCK = new Object();

	private static volatile boolean started;
	private static volatile ServerSocket serverSocket;
	private static volatile Object minecraftServer;
	private static volatile Field tickTimesField;

	private MsptAgent() {
	}

	public static void premain(String args, Instrumentation instrumentation) throws Exception {
		start(args);
	}

	public static void agentmain(String args, Instrumentation instrumentation) throws Exception {
		start(args);
	}

	public static void main(String[] args) throws Exception {
		if (args.length == 0 || "help".equals(args[0]) || "--help".equals(args[0])) {
			usage();
			return;
		}

		if (args.length != 1 || !args[0].matches("[0-9]+")) {
			throw new IllegalArgumentException("expected a single JVM pid");
		}

		runHelper(args[0]);
	}

	private static void usage() {
		System.out.println("usage: mspt-agent <pid>");
		System.out.println("       writes /tmp/mspt-agent-<pid>.mspt until killed or pid exits");
		System.out.println("       java -javaagent:mspt-agent.jar -jar vanilla_1.12.2.jar nogui");
	}

	private static void start(String args) throws IOException {
		synchronized (LOCK) {
			if (started) {
				if (serverSocket != null && !serverSocket.isClosed()) {
					writePortFile(currentPid(), serverSocket.getLocalPort());
				}
				return;
			}
			started = true;
		}

		int requestedPort = parsePort(args);
		serverSocket = new ServerSocket(requestedPort, 8, InetAddress.getByName("127.0.0.1"));
		writePortFile(currentPid(), serverSocket.getLocalPort());

		Thread thread = new Thread(new Runnable() {
			public void run() {
				serve();
			}
		}, "mspt-agent-query");
		thread.setDaemon(true);
		thread.start();
	}

	private static int parsePort(String args) {
		if (args == null || args.trim().isEmpty()) {
			return 0;
		}
		String[] parts = args.split(",");
		for (String part : parts) {
			String trimmed = part.trim();
			if (trimmed.startsWith("port=")) {
				return Integer.parseInt(trimmed.substring("port=".length()));
			}
		}
		return 0;
	}

	private static void serve() {
		while (true) {
			try {
				Socket socket = serverSocket.accept();
				try {
					PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(),
							StandardCharsets.UTF_8));
					try {
						out.println(currentSnapshot());
					} catch (Throwable error) {
						out.println("ERR " + error.getMessage());
					}
					out.flush();
				} finally {
					socket.close();
				}
			} catch (Throwable ignored) {
				// Keep the agent alive; callers receive an error if the socket disappears.
			}
		}
	}

	private static String currentSnapshot() throws Exception {
		Object server = minecraftServer;
		if (server == null) {
			server = findMinecraftServerFromThread();
			minecraftServer = server;
		}

		Field field = tickTimesField;
		if (field == null) {
			field = findTickTimesField(server.getClass());
			tickTimesField = field;
		}

		long[] values = (long[]) field.get(server);
		if (values == null || values.length == 0) {
			throw new IllegalStateException("tick time array is empty");
		}

		long sum = 0;
		int count = 0;
		long fingerprint = 1125899906842597L;
		for (long value : values) {
			sum += value;
			count++;
			fingerprint = (fingerprint * 31L) ^ value;
		}
		return MSPT_FORMAT.format((sum / (double) count) / 1000000.0D) + "|" + Long.toUnsignedString(fingerprint);
	}

	private static Object findMinecraftServerFromThread() throws Exception {
		Field targetField = Thread.class.getDeclaredField("target");
		targetField.setAccessible(true);

		Thread[] threads = allThreads();
		for (Thread thread : threads) {
			if (thread == null || !"Server thread".equals(thread.getName())) {
				continue;
			}
			Object target = targetField.get(thread);
			if (target != null && hasTickTimesField(target.getClass())) {
				return target;
			}
		}

		throw new IllegalStateException("Minecraft server thread not found yet");
	}

	private static Thread[] allThreads() {
		ThreadGroup root = Thread.currentThread().getThreadGroup();
		while (root.getParent() != null) {
			root = root.getParent();
		}

		int size = root.activeCount() * 2;
		while (true) {
			Thread[] threads = new Thread[size];
			int count = root.enumerate(threads, true);
			if (count < size) {
				return threads;
			}
			size *= 2;
		}
	}

	private static boolean hasTickTimesField(Class<?> type) {
		try {
			findTickTimesField(type);
			return true;
		} catch (Exception ignored) {
			return false;
		}
	}

	private static Field findTickTimesField(Class<?> type) throws NoSuchFieldException {
		Class<?> current = type;
		while (current != null) {
			try {
				Field field = current.getDeclaredField("h");
				if (field.getType().isArray() && field.getType().getComponentType() == Long.TYPE) {
					field.setAccessible(true);
					return field;
				}
			} catch (NoSuchFieldException ignored) {
			}
			current = current.getSuperclass();
		}
		throw new NoSuchFieldException("long[] field h not found on " + type.getName());
	}

	private static void install(String pid) throws Exception {
		VirtualMachine vm = VirtualMachine.attach(pid);
		try {
			vm.loadAgent(agentJarPath(), "");
		} finally {
			vm.detach();
		}
	}

	private static void runHelper(String pid) throws Exception {
		ensureInstalled(pid);
		File output = msptFile(pid);
		String lastFingerprint = null;
		while (new File("/proc/" + pid).exists()) {
			Snapshot snapshot = query(pid);
			if (!snapshot.fingerprint.equals(lastFingerprint)) {
				lastFingerprint = snapshot.fingerprint;
				writeText(output, snapshot.mspt + "\n");
				System.out.println(snapshot.mspt);
				System.out.flush();
			}
			Thread.sleep(10L);
		}
	}

	private static void ensureInstalled(String pid) throws Exception {
		try {
			query(pid);
			return;
		} catch (Exception ignored) {
			deletePortFile(pid);
		}

		install(pid);
		Exception last = null;
		for (int i = 0; i < 20; i++) {
			try {
				query(pid);
				return;
			} catch (Exception error) {
				last = error;
				Thread.sleep(50L);
			}
		}
		throw last;
	}

	private static Snapshot query(String pid) throws Exception {
		File file = portFile(pid);
		if (!file.isFile()) {
			throw new IllegalStateException("agent is not installed for pid " + pid);
		}

		BufferedReader fileReader = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(file),
				StandardCharsets.UTF_8));
		int port;
		try {
			port = Integer.parseInt(fileReader.readLine().trim());
		} finally {
			fileReader.close();
		}

		Socket socket = new Socket(InetAddress.getByName("127.0.0.1"), port);
		try {
			BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(),
					StandardCharsets.UTF_8));
			String line = in.readLine();
			if (line == null || line.length() == 0) {
				throw new IOException("empty response from agent");
			}
			if (line.startsWith("ERR ")) {
				throw new IOException(line.substring(4));
			}
			return Snapshot.parse(line);
		} finally {
			socket.close();
		}
	}

	private static void writePortFile(String pid, int port) throws IOException {
		File file = portFile(pid);
		FileOutputStream out = new FileOutputStream(file);
		try {
			out.write(Integer.toString(port).getBytes(StandardCharsets.UTF_8));
			out.write('\n');
		} finally {
			out.close();
		}
	}

	private static File portFile(String pid) {
		return new File(System.getProperty("java.io.tmpdir"), "mspt-agent-" + pid + ".port");
	}

	private static File msptFile(String pid) {
		return new File(System.getProperty("java.io.tmpdir"), "mspt-agent-" + pid + ".mspt");
	}

	private static void writeText(File file, String text) throws IOException {
		File tmp = new File(file.getAbsolutePath() + ".tmp");
		FileOutputStream out = new FileOutputStream(tmp);
		try {
			out.write(text.getBytes(StandardCharsets.UTF_8));
		} finally {
			out.close();
		}
		if (!tmp.renameTo(file)) {
			throw new IOException("failed to replace " + file);
		}
	}

	private static void deletePortFile(String pid) {
		File file = portFile(pid);
		if (file.isFile()) {
			file.delete();
		}
	}

	private static String currentPid() {
		String name = ManagementFactory.getRuntimeMXBean().getName();
		int at = name.indexOf('@');
		return at < 0 ? name : name.substring(0, at);
	}

	private static String agentJarPath() throws Exception {
		return new File(MsptAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getAbsolutePath();
	}

	private record Snapshot(String mspt, String fingerprint) {

		static Snapshot parse(String line) throws IOException {
				int separator = line.indexOf('|');
				if (separator <= 0 || separator == line.length() - 1) {
					throw new IOException("invalid agent response: " + line);
				}
				return new Snapshot(line.substring(0, separator), line.substring(separator + 1));
			}
		}
}
