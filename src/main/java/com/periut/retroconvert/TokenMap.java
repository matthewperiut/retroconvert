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

	/**
	 * Reverse (ornithe -> babric) token shapes. Every calamus class lives under
	 * net/minecraft/unmapped/, so the path alternative is listed first to consume
	 * "net/minecraft/unmapped/C_98947689" whole before the bare "C_98947689"
	 * alternative (reflection simple names) could fire inside it.
	 */
	private static final Pattern REVERSE_STRING_TOKENS = Pattern.compile(
			"net[./]minecraft[./]unmapped(?:[./]C_\\d{8})+"
					+ "|\\bm_\\d{8}\\b"
					+ "|\\bf_\\d{8}\\b"
					+ "|\\bC_\\d{8}\\b");

	/** true when this map was loaded inverted (calamus gen2 -> babric). */
	public final boolean reverse;

	public TokenMap() {
		this(false);
	}

	private TokenMap(boolean reverse) {
		this.reverse = reverse;
	}

	public static TokenMap load(InputStream in) throws IOException {
		return load(in, false);
	}

	/**
	 * @param reverse when true the bundled babric->calamus table is inverted into
	 *     a calamus->babric map. The owner-aware library rows (argo, paulscode)
	 *     keep identical owners and descriptors across both schemes, so only the
	 *     member name is swapped.
	 */
	public static TokenMap load(InputStream in, boolean reverse) throws IOException {
		TokenMap map = new TokenMap(reverse);
		// reverse-only: calamus tokens that several babric tokens merged into. They
		// must not resolve by bare name; RM/RF rows resolve them owner-aware (methods)
		// or with a client-preferred override (fields), applied after the main pass.
		java.util.Set<String> ambiguousMethods = new java.util.HashSet<String>();
		Map<String, String> reverseFieldOverrides = new HashMap<String, String>();
		BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
		String line;
		while ((line = reader.readLine()) != null) {
			if (line.isEmpty() || line.charAt(0) == '#') {
				continue;
			}
			String[] parts = line.split("\t");
			String babric, calamus;
			switch (parts[0]) {
			case "c":
				babric = parts[1];
				calamus = parts[2];
				map.classes.put(reverse ? calamus : babric, reverse ? babric : calamus);
				if (BARE_MC_CLASS.matcher(babric).matches()) {
					String babricBare = babric.substring(babric.lastIndexOf('/') + 1);
					String calamusBare = calamus.substring(calamus.lastIndexOf('/') + 1);
					map.bareClasses.put(reverse ? calamusBare : babricBare, reverse ? babricBare : calamusBare);
				}
				break;
			case "m":
				// Reverse maps by bare calamus name, which is only safe for the
				// synthetic m_NNNNNNNN tokens (globally unique). Real calamus names
				// (compare, compareTo) are JDK-override names shared across classes —
				// never put them in the global map or they'd clobber e.g. the
				// Comparator.compare SAM in every lambda. Ambiguous synthetic tokens
				// are pruned below; both are resolved owner-aware via RM rows.
				if (!reverse) {
					map.methods.put(parts[1], parts[2]);
				} else if (parts[2].matches("m_\\d{8}")) {
					map.methods.put(parts[2], parts[1]);
				}
				break;
			case "RM":
				// reverse owner-aware disambiguation: <calamusOwner> <calamusName> <calamusDesc> <babricToken>
				if (reverse) {
					map.ownerMethods.put(parts[1] + '\0' + parts[2] + '\0' + parts[3], parts[4]);
					ambiguousMethods.add(parts[2]);
				}
				break;
			case "RF":
				// reverse client-preferred field override: <calamusName> <babricToken>
				if (reverse) {
					reverseFieldOverrides.put(parts[1], parts[2]);
				}
				break;
			case "f":
				map.fields.put(reverse ? parts[2] : parts[1], reverse ? parts[1] : parts[2]);
				break;
			case "M":
				// key: owner + name + desc ; value: the other scheme's member name
				if (!reverse) {
					map.ownerMethods.put(parts[1] + '\0' + parts[2] + '\0' + parts[3], parts[4]);
				} else if (parts[4].matches("m_\\d{8}")) {
					map.ownerMethods.put(parts[1] + '\0' + parts[4] + '\0' + parts[3], parts[2]);
				}
				break;
			case "F":
				map.ownerFields.put(parts[1] + '\0' + (reverse ? parts[3] : parts[2]),
						reverse ? parts[2] : parts[3]);
				break;
			default:
				break;
			}
		}
		if (reverse) {
			// drop ambiguous synthetic tokens from the global map so lookups fall
			// through to the owner-aware RM entries
			for (String name : ambiguousMethods) {
				map.methods.remove(name);
			}
			map.fields.putAll(reverseFieldOverrides);
		}
		return map;
	}

	/** Maps an internal (slash-separated) class name. Returns the input if unmapped. */
	public String mapClass(String internalName) {
		String mapped = classes.get(internalName);
		if (mapped != null) {
			return mapped;
		}
		// The accessoryapi package move is forward-only: the reverse direction
		// deliberately leaves com.matthewperiut.accessoryapi where it is.
		if (!reverse && internalName.startsWith(OLD_API_SLASH)) {
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
		Matcher m = (reverse ? REVERSE_STRING_TOKENS : STRING_TOKENS).matcher(s);
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
			} else if (token.startsWith("method_") || token.startsWith("m_")) {
				replacement = methods.getOrDefault(token, token);
			} else if (token.startsWith("field_") || token.startsWith("f_")) {
				replacement = fields.getOrDefault(token, token);
			} else { // bare class token: class_N (forward) or C_NNNNNNNN (reverse)
				replacement = bareClasses.getOrDefault(token, token);
			}
			m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
		} while (m.find());
		m.appendTail(sb);
		return sb.toString();
	}
}
