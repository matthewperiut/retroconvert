package com.periut.retroconvert.gradle;

import com.periut.retroconvert.JarConverter;
import com.periut.retroconvert.TokenMap;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * Thin bridge between the Gradle plugin and the reused conversion engine. Loads
 * the bundled babric&lt;-&gt;calamus-b1.7.3 mapping table and drives
 * {@link JarConverter} in either direction.
 */
final class Conversions {
	private static final String MAPPING = "/retroconvert/babric-to-calamus-b1.7.3.tsv";

	private Conversions() {
	}

	/**
	 * @param reverse {@code true} = ornithe (calamus gen2) -&gt; babric (the inverse
	 *                direction used to produce a babric variant); {@code false} =
	 *                babric -&gt; ornithe.
	 */
	static byte[] convert(byte[] jarBytes, boolean reverse) throws IOException {
		TokenMap map = loadMap(reverse);
		JarConverter converter = new JarConverter(map, reverse);
		Map<String, byte[]> entries = JarConverter.readEntries(new ByteArrayInputStream(jarBytes));
		return converter.convert(entries);
	}

	static TokenMap loadMap(boolean reverse) throws IOException {
		InputStream in = Conversions.class.getResourceAsStream(MAPPING);
		if (in == null) {
			throw new IOException("bundled mapping resource missing: " + MAPPING);
		}
		try {
			return TokenMap.load(in, reverse);
		} finally {
			in.close();
		}
	}

	/**
	 * Splits leftover calamus tokens into the ones b1.7.3 mappings actually cover
	 * — those are real conversion gaps and will fail at runtime — and the rest,
	 * which name members of some other Minecraft version and are unconvertible by
	 * construction.
	 */
	static java.util.Set<String> mappable(java.util.Set<String> tokens) throws IOException {
		TokenMap map = loadMap(true);
		if (map.reverseKnown.isEmpty()) {
			// would silently reclassify every real gap as "other version" and lose the warning
			throw new IOException("reverse token map is empty; mapping resource " + MAPPING + " is broken");
		}
		java.util.Set<String> gaps = new java.util.LinkedHashSet<String>();
		for (String token : tokens) {
			if (map.reverseKnown.contains(token)) {
				gaps.add(token);
			}
		}
		return gaps;
	}

	/** Detects which intermediary a jar's bytecode/resources use. */
	static JarConverter.Kind detect(byte[] jarBytes) throws IOException {
		return JarConverter.detect(JarConverter.readEntries(new ByteArrayInputStream(jarBytes)));
	}

	/**
	 * Collects up to {@code limit} distinct calamus gen2 tokens still present in a
	 * jar, recursing into nested META-INF/jars. Used to sanity-check the output of
	 * a reverse conversion, which should have none.
	 */
	static java.util.Set<String> findCalamusTokens(byte[] jarBytes, int limit) throws IOException {
		java.util.Set<String> found = new java.util.LinkedHashSet<String>();
		collectCalamusTokens(JarConverter.readEntries(new ByteArrayInputStream(jarBytes)), limit, found);
		return found;
	}

	private static void collectCalamusTokens(Map<String, byte[]> entries, int limit,
			java.util.Set<String> found) throws IOException {
		for (Map.Entry<String, byte[]> e : entries.entrySet()) {
			byte[] data = e.getValue();
			if (data == null) {
				continue;
			}
			String name = e.getKey();
			if (name.startsWith("META-INF/jars/") && name.endsWith(".jar")) {
				collectCalamusTokens(JarConverter.readEntries(new ByteArrayInputStream(data)), limit, found);
			} else if (isScannable(name)) {
				java.util.regex.Matcher m = CALAMUS_TOKEN.matcher(
						new String(data, java.nio.charset.StandardCharsets.ISO_8859_1));
				while (m.find() && found.size() < limit) {
					found.add(m.group());
				}
			}
			if (found.size() >= limit) {
				return;
			}
		}
	}

	private static boolean isScannable(String name) {
		String lower = name.toLowerCase(java.util.Locale.ROOT);
		return lower.endsWith(".class") || lower.endsWith(".json") || lower.endsWith(".accesswidener")
				|| lower.endsWith(".classtweaker");
	}

	private static final java.util.regex.Pattern CALAMUS_TOKEN = java.util.regex.Pattern.compile(
			"net/minecraft/unmapped/C_\\d{8}|\\bm_\\d{8}\\b|\\bf_\\d{8}\\b|\\bC_\\d{8}\\b");
}
