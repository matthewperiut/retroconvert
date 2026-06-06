package com.periut.retroconvert;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * config/retroconvert.json — tracks every mod jar ever seen in the mods folder
 * by sha256 so each file is only assessed once. Conversion itself is idempotent
 * (a converted jar detects as ornithe), so a lost/corrupt registry merely
 * causes a rescan.
 */
public final class Registry {
	public static final String STATUS_CONVERTED = "converted";
	public static final String STATUS_REPLACED = "replaced-with-official-build";
	public static final String STATUS_OUTPUT = "converted-output";
	/** a downloaded official build — distinct from STATUS_OUTPUT so it is never "upgraded" onto itself */
	public static final String STATUS_OFFICIAL = "official-build";
	public static final String STATUS_ORNITHE = "ornithe";
	public static final String STATUS_NEUTRAL = "no-minecraft-refs";

	public static final class Entry {
		public String file;
		public String status;
		/** for STATUS_CONVERTED: the produced jar name; for STATUS_OUTPUT: the source jar name */
		public String related;
		public long time;

		public Entry(String file, String status, String related, long time) {
			this.file = file;
			this.status = status;
			this.related = related;
			this.time = time;
		}
	}

	public final Map<String, Entry> entries = new LinkedHashMap<String, Entry>();
	private boolean dirty;

	public static Registry load(Path path) {
		Registry registry = new Registry();
		if (!Files.isRegularFile(path)) {
			return registry;
		}
		try {
			String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
			Object root = MiniJson.parse(text);
			Object mods = root instanceof Map ? ((Map<?, ?>) root).get("mods") : null;
			if (mods instanceof Map) {
				for (Map.Entry<?, ?> e : ((Map<?, ?>) mods).entrySet()) {
					if (!(e.getValue() instanceof Map)) {
						continue;
					}
					Map<?, ?> v = (Map<?, ?>) e.getValue();
					registry.entries.put(String.valueOf(e.getKey()), new Entry(
							str(v.get("file")), str(v.get("status")), str(v.get("related")),
							v.get("time") instanceof Long ? (Long) v.get("time") : 0L));
				}
			}
		} catch (Exception ex) {
			// corrupt registry: start over; conversion is idempotent so this is safe
		}
		return registry;
	}

	private static String str(Object o) {
		return o == null ? null : String.valueOf(o);
	}

	public Entry get(String sha256) {
		return entries.get(sha256);
	}

	public void put(String sha256, Entry entry) {
		entries.put(sha256, entry);
		dirty = true;
	}

	public void remove(String sha256) {
		if (entries.remove(sha256) != null) {
			dirty = true;
		}
	}

	public boolean isDirty() {
		return dirty;
	}

	public void save(Path path) throws IOException {
		StringBuilder sb = new StringBuilder(4096);
		sb.append("{\n  \"_\": \"RetroConvert registry. Keyed by jar sha256; delete to rescan everything.\",\n");
		sb.append("  \"mods\": {");
		boolean first = true;
		for (Map.Entry<String, Entry> e : entries.entrySet()) {
			if (!first) {
				sb.append(',');
			}
			first = false;
			sb.append("\n    ");
			MiniJson.writeString(sb, e.getKey());
			sb.append(": { \"file\": ");
			MiniJson.writeString(sb, e.getValue().file == null ? "" : e.getValue().file);
			sb.append(", \"status\": ");
			MiniJson.writeString(sb, e.getValue().status == null ? "" : e.getValue().status);
			if (e.getValue().related != null) {
				sb.append(", \"related\": ");
				MiniJson.writeString(sb, e.getValue().related);
			}
			sb.append(", \"time\": ").append(e.getValue().time).append(" }");
		}
		sb.append("\n  }\n}\n");
		Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
		Files.createDirectories(path.toAbsolutePath().getParent());
		Files.write(tmp, sb.toString().getBytes(StandardCharsets.UTF_8));
		Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
		dirty = false;
	}

	public static String sha256(byte[] data) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(data);
			StringBuilder sb = new StringBuilder(hash.length * 2);
			for (byte b : hash) {
				sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
			}
			return sb.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		}
	}
}
