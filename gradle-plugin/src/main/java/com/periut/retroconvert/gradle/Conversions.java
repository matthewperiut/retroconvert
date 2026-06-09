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
		InputStream in = Conversions.class.getResourceAsStream(MAPPING);
		if (in == null) {
			throw new IOException("bundled mapping resource missing: " + MAPPING);
		}
		TokenMap map;
		try {
			map = TokenMap.load(in, reverse);
		} finally {
			in.close();
		}
		JarConverter converter = new JarConverter(map, reverse);
		Map<String, byte[]> entries = JarConverter.readEntries(new ByteArrayInputStream(jarBytes));
		return converter.convert(entries);
	}

	/** Detects which intermediary a jar's bytecode/resources use. */
	static JarConverter.Kind detect(byte[] jarBytes) throws IOException {
		return JarConverter.detect(JarConverter.readEntries(new ByteArrayInputStream(jarBytes)));
	}
}
