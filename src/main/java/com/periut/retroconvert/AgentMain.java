package com.periut.retroconvert;

import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Optional java-agent entry point: add
 * {@code -javaagent:/path/to/retroconvert-<version>.jar} to the JVM arguments
 * (e.g. Prism Launcher → instance Settings → Java → JVM arguments) and
 * conversion runs BEFORE fabric loader discovers the mods folder. No file is
 * locked yet, so there is never a restart, a relaunch, or a deferred swap —
 * the loader only ever sees the converted jars.
 *
 * The same jar keeps working as a normal ornithe mod: when the agent has
 * already run, the in-game preLaunch pass sees the marker property and skips
 * its scan. Without the agent, the mod path (in-JVM relaunch / restart prompt)
 * behaves exactly as before. We deliberately don't use Instrumentation at all
 * — premain is just the earliest possible hook.
 */
public final class AgentMain {
	/** read by the mod's preLaunch so it can skip the duplicate scan */
	public static final String DONE_MARKER = "retroconvert.agent.done";

	private AgentMain() {
	}

	public static void premain(String agentArgs, Instrumentation inst) {
		try {
			run(agentArgs);
		} catch (Throwable t) {
			// never break the launch from inside an agent; the mod path (if
			// installed in mods/) still picks everything up later
			System.out.println("[RetroConvert/agent] failed, leaving mods folder to the in-game pass: " + t);
			t.printStackTrace(System.out);
		}
	}

	/** also usable via dynamic attach */
	public static void agentmain(String agentArgs, Instrumentation inst) {
		premain(agentArgs, inst);
	}

	private static void run(String agentArgs) throws Exception {
		Path gameDir = resolveGameDir(agentArgs);
		String modsProp = System.getProperty("fabric.modsFolder");
		Path modsDir = modsProp != null ? Paths.get(modsProp) : gameDir.resolve("mods");
		if (!Files.isDirectory(modsDir)) {
			System.out.println("[RetroConvert/agent] no mods folder at " + modsDir + ", skipping");
			return;
		}

		Converter.Result result = new Converter(modsDir, gameDir.resolve("config").resolve("retroconvert"),
				new Converter.Log() {
					@Override
					public void info(String message) {
						System.out.println("[RetroConvert/agent] " + message);
					}

					@Override
					public void warn(String message, Throwable t) {
						System.out.println("[RetroConvert/agent] WARN " + message);
						if (t != null) {
							t.printStackTrace(System.out);
						}
					}
				}).run();

		System.setProperty(DONE_MARKER, "true");
		if (result.converted > 0) {
			System.out.println("[RetroConvert/agent] converted " + result.converted
					+ " babric mod(s) before loader startup; no restart needed");
		}
	}

	/**
	 * Finds the instance's game directory without needing any configuration.
	 * Candidates in order: the agent argument ({@code -javaagent:...=<gameDir>}),
	 * the MultiMC/Prism instance environment variables (when exported), the
	 * {@code --gameDir} program argument, and the working directory — Prism,
	 * MultiMC and the vanilla launcher all set cwd to the instance's minecraft
	 * folder, which is also why a relative
	 * {@code -javaagent:mods/retroconvert-<version>.jar} resolves on its own.
	 * The first candidate that actually contains a mods folder wins.
	 */
	private static Path resolveGameDir(String agentArgs) {
		java.util.List<Path> candidates = new java.util.ArrayList<Path>();
		if (agentArgs != null && !agentArgs.trim().isEmpty()) {
			candidates.add(Paths.get(agentArgs.trim()));
		}
		String instMcDir = System.getenv("INST_MC_DIR"); // MultiMC / Prism Launcher
		if (instMcDir != null && !instMcDir.isEmpty()) {
			candidates.add(Paths.get(instMcDir));
		}
		String instDir = System.getenv("INST_DIR");
		if (instDir != null && !instDir.isEmpty()) {
			candidates.add(Paths.get(instDir, ".minecraft"));
			candidates.add(Paths.get(instDir, "minecraft"));
		}
		// best effort: breaks on paths with spaces, later candidates cover those
		String command = System.getProperty("sun.java.command", "");
		String[] parts = command.split(" ");
		for (int i = 0; i < parts.length - 1; i++) {
			if (parts[i].equals("--gameDir")) {
				candidates.add(Paths.get(parts[i + 1]));
				break;
			}
		}
		candidates.add(Paths.get(System.getProperty("user.dir", ".")));

		for (Path candidate : candidates) {
			try {
				if (Files.isDirectory(candidate.resolve("mods"))) {
					return candidate;
				}
			} catch (RuntimeException invalidPath) {
				// malformed candidate (e.g. truncated --gameDir); try the next one
			}
		}
		return candidates.get(candidates.size() - 1);
	}
}
