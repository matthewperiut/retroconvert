package com.periut.retroconvert;

import java.lang.reflect.Field;
import java.net.URLClassLoader;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

/**
 * Makes fabric loader release its open handles on the jars in mods/ so they
 * can be moved in-process — on Windows the loader's grip is a hard file lock.
 *
 * The loader holds each mod jar open twice: cached JarFile handles inside
 * Knot's DynamicURLClassLoader (the classpath), and a ZipFileSystem per mod
 * (the container's root paths). Closing both releases the locks. Already
 * loaded classes keep working after a URLClassLoader is closed; only loading
 * NEW classes through it breaks — which is fine, because this is only ever
 * called once a conversion has happened and the session is committed to an
 * in-JVM relaunch (or exit) anyway. Everything RetroConvert itself still
 * needs afterwards is force-loaded first.
 */
public final class LoaderGrip {
	private static boolean released;

	private LoaderGrip() {
	}

	/** Once true, the outer loader session cannot continue and must relaunch or exit. */
	public static synchronized boolean wasReleased() {
		return released;
	}

	/** Idempotent. Returns true if anything was actually closed. */
	public static synchronized boolean release(Converter.Log log) {
		if (released) {
			return true;
		}
		released = true;

		// Everything used between here and the relaunch/exit must already be
		// loaded — after the close, our own jar can no longer serve classes. So
		// preload ALL of RetroConvert's classes, plus any synthetic companions
		// (switch-map holders, anonymous classes) javac may have emitted.
		Class<?>[] keepLoaded = {
				AgentMain.class, Converter.class, Converter.Result.class,
				ConverterCli.class, JarConverter.class, JarConverter.Kind.class,
				MiniJson.class, Registry.class, Registry.Entry.class,
				Relauncher.class, RetroConvert.class, RetroRemapper.class,
				RetroRemapper.ClassMeta.class, SwapHelper.class, TokenMap.class,
		};
		ClassLoader self = LoaderGrip.class.getClassLoader();
		for (Class<?> type : keepLoaded) {
			for (int i = 1; i <= 8; i++) {
				try {
					Class.forName(type.getName() + "$" + i, true, self);
				} catch (Throwable absent) {
					break; // synthetics are numbered consecutively
				}
			}
		}
		// In some launcher setups (e.g. Prism + the ornithe meta) log4j itself is
		// served through the loader's classloader; preload the message classes it
		// lazily creates so logging after the close doesn't NoClassDefFoundError.
		String[] log4jLazies = {
				"org.apache.logging.log4j.message.ReusableParameterizedMessage",
				"org.apache.logging.log4j.message.ReusableSimpleMessage",
				"org.apache.logging.log4j.message.ReusableObjectMessage",
				"org.apache.logging.log4j.message.ParameterizedMessage",
				"org.apache.logging.log4j.message.SimpleMessage",
				"org.apache.logging.log4j.message.ObjectMessage",
				"org.apache.logging.log4j.message.ParameterFormatter",
		};
		for (String name : log4jLazies) {
			try {
				Class.forName(name, true, self);
			} catch (Throwable absent) {
				// log4j not present or differently packaged; the failsafe loggers cover it
			}
		}

		boolean closedAnything = false;

		// 1. the loader's zip filesystems over mod jars (public API)
		try {
			for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
				for (Path root : mod.getRootPaths()) {
					try {
						FileSystem fs = root.getFileSystem();
						if (fs != FileSystems.getDefault() && fs.isOpen()) {
							fs.close();
							closedAnything = true;
						}
					} catch (Throwable perMod) {
						// some roots share filesystems / are already closed
					}
				}
			}
		} catch (Throwable t) {
			log.warn("RetroConvert: could not close mod filesystems", t);
		}

		// 2. the URLClassLoader(s) holding the classpath JarFile cache. Knot's
		// class loader either is one or wraps one in a field; the loader is in
		// the unnamed module, so setAccessible is permitted.
		try {
			for (ClassLoader cl = LoaderGrip.class.getClassLoader(); cl != null; cl = cl.getParent()) {
				if (cl == ClassLoader.getSystemClassLoader()) {
					break; // never close the launch classpath out from under the JVM
				}
				if (cl instanceof URLClassLoader) {
					((URLClassLoader) cl).close();
					closedAnything = true;
					continue;
				}
				for (Class<?> type = cl.getClass(); type != null; type = type.getSuperclass()) {
					for (Field field : type.getDeclaredFields()) {
						if (!URLClassLoader.class.isAssignableFrom(field.getType())) {
							continue;
						}
						field.setAccessible(true);
						Object value = field.get(cl);
						if (value != null) {
							((URLClassLoader) value).close();
							closedAnything = true;
						}
					}
				}
			}
		} catch (Throwable t) {
			log.warn("RetroConvert: could not close the mod classloader's jar handles", t);
		}

		if (closedAnything) {
			log.info("RetroConvert: released the loader's hold on the mods folder");
		}
		return closedAnything;
	}
}
