package com.periut.retroconvert;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The composed babric-intermediary -> calamus-gen2-intermediary token map for b1.7.3.
 *
 * Both intermediary schemes use globally unique member tokens
 * (babric: method_N / field_N, calamus: m_NNNNNNNN / f_NNNNNNNN), which makes
 * owner-independent renaming safe for everything Mojang obfuscated. The only
 * owner-aware entries ("M"/"F" rows) are bundled library members (argo,
 * paulscode, ...) whose original names survived in babric but were renamed by
 * calamus.
 */
public final class TokenMap {
	/** com.matthewperiut.accessoryapi moved to com.periut.accessoryapi in the Ornithe port. */
	public static final String OLD_API_SLASH = "com/matthewperiut/accessoryapi";
	public static final String NEW_API_SLASH = "com/periut/accessoryapi";

	public final Map<String, String> classes = new HashMap<String, String>();
	/** bare simple-name class tokens, e.g. "class_57" -> "C_42232651" (for reflection strings) */
	public final Map<String, String> bareClasses = new HashMap<String, String>();
	public final Map<String, String> methods = new HashMap<String, String>();
	public final Map<String, String> fields = new HashMap<String, String>();
	/** key: owner + '\0' + name + '\0' + desc */
	public final Map<String, String> ownerMethods = new HashMap<String, String>();
	/** key: owner + '\0' + name */
	public final Map<String, String> ownerFields = new HashMap<String, String>();

	private static final Pattern BARE_MC_CLASS = Pattern.compile("net/minecraft/class_\\d+");

	/**
	 * One pass over a string, longest/leftmost match wins. Path alternatives are
	 * listed first so "net/minecraft/class_206" is consumed whole before the bare
	 * "class_206" alternative could fire inside it.
	 */
	private static final Pattern STRING_TOKENS = Pattern.compile(
			"(?:net[./]minecraft|argo|paulscode|com[./]mojang|com[./]matthewperiut[./]accessoryapi)(?:[./][A-Za-z0-9_$]+)*"
					+ "|\\bmethod_\\d+\\b"
					+ "|\\bfield_\\d+\\b"
					+ "|\\bclass_\\d+\\b");

	public static TokenMap load(InputStream in) throws IOException {
		TokenMap map = new TokenMap();
		BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
		String line;
		while ((line = reader.readLine()) != null) {
			if (line.isEmpty() || line.charAt(0) == '#') {
				continue;
			}
			String[] parts = line.split("\t");
			switch (parts[0]) {
			case "c":
				map.classes.put(parts[1], parts[2]);
				if (BARE_MC_CLASS.matcher(parts[1]).matches()) {
					String bare = parts[1].substring(parts[1].lastIndexOf('/') + 1);
					String simple = parts[2].substring(parts[2].lastIndexOf('/') + 1);
					map.bareClasses.put(bare, simple);
				}
				break;
			case "m":
				map.methods.put(parts[1], parts[2]);
				break;
			case "f":
				map.fields.put(parts[1], parts[2]);
				break;
			case "M":
				map.ownerMethods.put(parts[1] + '\0' + parts[2] + '\0' + parts[3], parts[4]);
				break;
			case "F":
				map.ownerFields.put(parts[1] + '\0' + parts[2], parts[3]);
				break;
			default:
				break;
			}
		}
		return map;
	}

	/** Maps an internal (slash-separated) class name. Returns the input if unmapped. */
	public String mapClass(String internalName) {
		String mapped = classes.get(internalName);
		if (mapped != null) {
			return mapped;
		}
		if (internalName.startsWith(OLD_API_SLASH)) {
			return NEW_API_SLASH + internalName.substring(OLD_API_SLASH.length());
		}
		return internalName;
	}

	/**
	 * Remaps every babric token occurring in an arbitrary string: internal and
	 * dotted class names (including inside descriptors), bare method_N/field_N/
	 * class_N tokens (reflection!), and the accessoryapi package move. Used for
	 * string constants, annotation values, refmaps, accesswideners and
	 * fabric.mod.json alike.
	 */
	public String remapString(String s) {
		Matcher m = STRING_TOKENS.matcher(s);
		if (!m.find()) {
			return s;
		}
		StringBuffer sb = new StringBuffer(s.length() + 16);
		do {
			String token = m.group();
			String replacement = token;
			if (token.indexOf('.') >= 0 || token.indexOf('/') >= 0) {
				boolean dotted = token.indexOf('.') >= 0;
				String slashed = dotted ? token.replace('.', '/') : token;
				String mapped = mapClass(slashed);
				if (!mapped.equals(slashed)) {
					replacement = dotted ? mapped.replace('/', '.') : mapped;
				}
			} else if (token.startsWith("method_")) {
				replacement = methods.getOrDefault(token, token);
			} else if (token.startsWith("field_")) {
				replacement = fields.getOrDefault(token, token);
			} else { // class_N
				replacement = bareClasses.getOrDefault(token, token);
			}
			m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
		} while (m.find());
		m.appendTail(sb);
		return sb.toString();
	}
}
