package com.periut.retroconvert;

import java.nio.file.Path;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

/**
 * Runs before any game class is loaded — and crucially before any babric mod's
 * mixins could be applied — converts newly detected babric mods to ornithe
 * intermediaries, then restarts fabric loader from the top inside the same JVM
 * so the converted jars are picked up invisibly ({@link Relauncher}). Where
 * that isn't possible (dev environment, locked files on Windows, exotic
 * launchers) it falls back to a one-time restart prompt. On every later launch
 * nothing new is found and the game starts normally.
 */
public final class RetroConvert implements PreLaunchEntrypoint {
	public static final Logger LOGGER = LogManager.getLogger("RetroConvert");

	/**
	 * Once the loader's grip is released its classloader can no longer serve
	 * NEW classes — and in some launcher setups log4j itself is served through
	 * it, so even logging can throw. Everything that may run after a release
	 * goes through these failsafe wrappers (also: no {}-parameterized calls,
	 * which lazy-load log4j message classes).
	 */
	static void safeInfo(String message) {
		try {
			LOGGER.info(message);
		} catch (Throwable broken) {
			System.out.println("[RetroConvert] " + message);
		}
	}

	static void safeWarn(String message, Throwable t) {
		try {
			LOGGER.warn(message, t);
		} catch (Throwable broken) {
			System.out.println("[RetroConvert] WARN " + message);
			if (t != null) {
				t.printStackTrace(System.out);
			}
		}
	}

	@Override
	public void onPreLaunch() {
		if (System.getProperty(AgentMain.DONE_MARKER) != null) {
			// the java agent already converted everything before the loader
			// scanned mods/ — nothing left to do this session
			LOGGER.info("RetroConvert: agent pass already ran before loader startup; skipping scan");
			return;
		}

		FabricLoader loader = FabricLoader.getInstance();
		Path modsDir = loader.getGameDir().resolve("mods");
		Path configBaseDir = loader.getConfigDir().resolve("retroconvert");

		final Converter.Log log = new Converter.Log() {
			@Override
			public void info(String message) {
				safeInfo(message);
			}

			@Override
			public void warn(String message, Throwable t) {
				safeWarn(message, t);
			}
		};

		Converter.Result result;
		try {
			Converter converter = new Converter(modsDir, configBaseDir, log);
			// as soon as a conversion is committed, have the loader let go of the
			// mods folder (Windows file locks) — this session relaunches anyway
			converter.setLockReleaser(new Converter.LockReleaser() {
				@Override
				public boolean releaseLocks() {
					return LoaderGrip.release(log);
				}
			});
			result = converter.run();
		} catch (Throwable ex) {
			// never block the game from starting over a converter failure — and
			// catch Errors too (NoClassDefFoundError after a grip release)
			safeWarn("RetroConvert failed; leaving mods folder untouched", ex);
			result = null;
		}

		if (result != null) {
			safeInfo("RetroConvert: scanned " + result.scanned + " jar(s): " + result.converted
					+ " converted, " + result.alreadyOrnithe + " already ornithe, " + result.neutral
					+ " without minecraft refs, " + result.skippedKnown + " known, " + result.failed + " failed");
		}

		// once the grip is released this loader session cannot continue, even if
		// every conversion subsequently failed
		if (result == null || result.converted == 0) {
			if (LoaderGrip.wasReleased()) {
				safeWarn("RetroConvert: loader handles were released but nothing was converted; relaunching to restore a clean session", null);
				relaunchOrExit(loader, result, "RetroConvert had to restart the loader but no mods were converted.\n"
						+ "Please restart the game.");
			}
			return;
		}

		StringBuilder message = new StringBuilder();
		message.append("RetroConvert rewrote ").append(result.converted)
				.append(result.converted == 1 ? " babric mod" : " babric mods")
				.append(" to Ornithe intermediaries:\n");
		for (String name : result.convertedNames) {
			message.append("  - ").append(name).append('\n');
		}
		message.append("The originals were moved to config/retroconvert/originals.\n");
		if (!result.pendingSwaps.isEmpty()) {
			message.append("Some files were locked and will be swapped as the game closes.\n");
		}
		message.append("Please restart the game so the converted mods are loaded.");

		safeInfo("RetroConvert: converted " + result.converted + " mod(s)");
		relaunchOrExit(loader, result, message.toString());
	}

	/**
	 * Invisible path first: boot a fresh loader over the converted mods folder
	 * in this same JVM. Requires production Knot and all originals swapped now
	 * (a deferred swap means mods/ isn't in its final state yet). Falls back to
	 * the restart dialog. Never returns.
	 */
	private static void relaunchOrExit(FabricLoader loader, Converter.Result result, String restartMessage) {
		boolean swapsSettled = result == null || result.pendingSwaps.isEmpty();
		if (Relauncher.enabled() && !loader.isDevelopmentEnvironment() && swapsSettled) {
			try {
				safeInfo("RetroConvert: relaunching fabric loader in-process");
				Relauncher.relaunch(loader.getEnvironmentType() == net.fabricmc.api.EnvType.CLIENT,
						loader.getLaunchArguments(false));
			} catch (Throwable relaunchFailure) {
				// Conversions are already committed to disk, so a manual restart
				// always recovers — never escalate this into a crash. (A genuine
				// in-game crash exits the JVM through the loader's own handler
				// and never returns here; what lands here are setup failures of
				// the inner loader.)
				safeWarn("RetroConvert: in-process relaunch failed, falling back to restart prompt", relaunchFailure);
			}
		}

		safeInfo("\n==========================================================\n"
				+ restartMessage + "\n==========================================================");
		showDialog(restartMessage);
		System.exit(0);
	}

	private static void showDialog(String message) {
		String os = System.getProperty("os.name", "").toLowerCase();
		if (os.contains("mac") || java.awt.GraphicsEnvironment.isHeadless()) {
			return; // AWT before LWJGL misbehaves on macOS; servers have no display
		}
		try {
			javax.swing.JOptionPane.showMessageDialog(null, message,
					"RetroConvert — restart required", javax.swing.JOptionPane.INFORMATION_MESSAGE);
		} catch (Throwable ignored) {
			// console banner already covers it
		}
	}
}
