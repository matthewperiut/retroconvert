package com.periut.retroconvert;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

/**
 * Boots a fresh copy of fabric loader inside the same JVM so converted mods
 * are picked up without a user-visible restart.
 *
 * At preLaunch nothing irreversible has happened yet: no game class is loaded,
 * no LWJGL natives, no AWT. The production classpath (java.class.path)
 * contains the loader, its libraries and the game, so a new isolated
 * classloader (platform parent, fresh statics for loader and mixin alike) can
 * run Knot's main from the top. The inner loader re-discovers mods/ — now
 * containing the converted jars — and launches the game in this same process;
 * the half-initialized outer loader is simply never resumed.
 *
 * Minecraft exits the JVM when closed, so the call below normally never
 * returns. Disable with -Dretroconvert.relaunch=false.
 */
public final class Relauncher {
	private Relauncher() {
	}

	/** guards against pathological relaunch loops; survives the in-JVM relaunch */
	private static final String RELAUNCHED_MARKER = "retroconvert.relaunched";

	public static boolean enabled() {
		return !"false".equals(System.getProperty("retroconvert.relaunch"))
				&& System.getProperty(RELAUNCHED_MARKER) == null;
	}

	/**
	 * Never returns on success (the game exits the JVM). Throws if the inner
	 * loader could not be started — the caller falls back to the restart prompt.
	 */
	public static void relaunch(boolean client, String[] launchArgs) throws Exception {
		String mainClass = client
				? "net.fabricmc.loader.impl.launch.knot.KnotClient"
				: "net.fabricmc.loader.impl.launch.knot.KnotServer";

		List<URL> classpath = new ArrayList<URL>();
		for (String part : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
			if (!part.isEmpty()) {
				classpath.add(new File(part).toURI().toURL());
			}
		}
		// -javaagent jars are appended to the system classloader's search path
		// but never appear in java.class.path. Agents like ornithe's flap
		// bytecode-patch loader classes to call INTO agent code (the patch is
		// JVM-global, so it hits the fresh loader copy too) — the agent's own
		// classes must therefore be on the fresh classpath as well.
		try {
			for (String arg : java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments()) {
				if (arg.startsWith("-javaagent:")) {
					String spec = arg.substring("-javaagent:".length());
					int options = spec.indexOf('=');
					String path = options >= 0 ? spec.substring(0, options) : spec;
					classpath.add(new File(path).toURI().toURL());
				}
			}
		} catch (Throwable inspectionUnavailable) {
			// exotic JVM; worst case the relaunch fails and the caller falls
			// back to the restart prompt
		}

		// Agents (ornithe's flap) park state in fixed-name in-memory filesystems;
		// NIO providers are JVM-global, so the abandoned outer session's
		// filesystems must be closed or the inner agent code collides with them
		// (FileSystemAlreadyExistsException: jimfs://mod-variant-remapper).
		closeInMemoryFilesystems();

		URLClassLoader fresh = new URLClassLoader(classpath.toArray(new URL[0]), parentLoader());
		Thread.currentThread().setContextClassLoader(fresh);
		Class<?> knot = Class.forName(mainClass, false, fresh);
		Method main = knot.getMethod("main", String[].class);
		System.setProperty(RELAUNCHED_MARKER, "true");
		// runs on this same thread (matters on macOS: -XstartOnFirstThread)
		main.invoke(null, (Object) launchArgs.clone());

		// Old Minecraft (and starac's LWJGL3 layer) starts the game on its own
		// non-daemon thread ("Minecraft main thread") and returns from main()
		// immediately — exiting here would kill the game as it spawns. Mirror
		// normal JVM lifetime instead: keep this thread parked until every
		// other non-daemon thread has finished, then end the process so the
		// stale outer launch never resumes.
		waitForRemainingThreads();
		System.exit(0);
	}

	private static void waitForRemainingThreads() {
		while (true) {
			boolean anyRunning = false;
			for (Thread thread : Thread.getAllStackTraces().keySet()) {
				if (thread == Thread.currentThread() || thread.isDaemon() || !thread.isAlive()) {
					continue;
				}
				if ("DestroyJavaVM".equals(thread.getName())) {
					continue;
				}
				anyRunning = true;
				break;
			}
			if (!anyRunning) {
				return;
			}
			try {
				Thread.sleep(1000L);
			} catch (InterruptedException interrupted) {
				return;
			}
		}
	}

	/**
	 * Closes every open in-memory (jimfs-style) filesystem registered with the
	 * JVM-global provider list. Best effort: providers keep their filesystems
	 * in a map field; closing deregisters them so the fresh session can create
	 * its own under the same fixed names.
	 */
	private static void closeInMemoryFilesystems() {
		try {
			for (java.nio.file.spi.FileSystemProvider provider
					: java.nio.file.spi.FileSystemProvider.installedProviders()) {
				String scheme = provider.getScheme();
				if (!"jimfs".equals(scheme)) {
					continue;
				}
				for (Class<?> type = provider.getClass(); type != null; type = type.getSuperclass()) {
					for (java.lang.reflect.Field field : type.getDeclaredFields()) {
						if (!java.util.Map.class.isAssignableFrom(field.getType())) {
							continue;
						}
						field.setAccessible(true);
						Object value = field.get(provider);
						if (!(value instanceof java.util.Map)) {
							continue;
						}
						for (Object fs : new ArrayList<Object>(((java.util.Map<?, ?>) value).values())) {
							if (fs instanceof java.io.Closeable) {
								try {
									((java.io.Closeable) fs).close();
								} catch (Throwable ignored) {
								}
							}
						}
					}
				}
			}
		} catch (Throwable ignored) {
			// nothing to close, or a provider we can't introspect — the relaunch
			// will surface any remaining collision and the caller falls back
		}
	}

	/** Platform classloader on Java 9+, extension loader on 8 — never the app classloader. */
	private static ClassLoader parentLoader() {
		try {
			return (ClassLoader) ClassLoader.class.getMethod("getPlatformClassLoader").invoke(null);
		} catch (Exception java8) {
			return ClassLoader.getSystemClassLoader().getParent();
		}
	}
}
