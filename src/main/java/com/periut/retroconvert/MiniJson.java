package com.periut.retroconvert;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny JSON reader/writer for the registry file. Minecraft b1.7.3 has no JSON
 * library on the classpath and the loader's gson is shaded away, so we keep
 * this self-contained.
 */
public final class MiniJson {
	private final String src;
	private int pos;

	private MiniJson(String src) {
		this.src = src;
	}

	public static Object parse(String text) {
		MiniJson parser = new MiniJson(text);
		parser.skipWs();
		Object value = parser.parseValue();
		parser.skipWs();
		if (parser.pos != text.length()) {
			throw new IllegalArgumentException("trailing data at " + parser.pos);
		}
		return value;
	}

	private Object parseValue() {
		char c = peek();
		switch (c) {
		case '{':
			return parseObject();
		case '[':
			return parseArray();
		case '"':
			return parseString();
		case 't':
			expect("true");
			return Boolean.TRUE;
		case 'f':
			expect("false");
			return Boolean.FALSE;
		case 'n':
			expect("null");
			return null;
		default:
			return parseNumber();
		}
	}

	private Map<String, Object> parseObject() {
		Map<String, Object> map = new LinkedHashMap<String, Object>();
		pos++; // {
		skipWs();
		if (peek() == '}') {
			pos++;
			return map;
		}
		while (true) {
			skipWs();
			String key = parseString();
			skipWs();
			if (src.charAt(pos++) != ':') {
				throw new IllegalArgumentException("expected ':' at " + (pos - 1));
			}
			skipWs();
			map.put(key, parseValue());
			skipWs();
			char c = src.charAt(pos++);
			if (c == '}') {
				return map;
			}
			if (c != ',') {
				throw new IllegalArgumentException("expected ',' or '}' at " + (pos - 1));
			}
		}
	}

	private List<Object> parseArray() {
		List<Object> list = new ArrayList<Object>();
		pos++; // [
		skipWs();
		if (peek() == ']') {
			pos++;
			return list;
		}
		while (true) {
			skipWs();
			list.add(parseValue());
			skipWs();
			char c = src.charAt(pos++);
			if (c == ']') {
				return list;
			}
			if (c != ',') {
				throw new IllegalArgumentException("expected ',' or ']' at " + (pos - 1));
			}
		}
	}

	private String parseString() {
		if (src.charAt(pos) != '"') {
			throw new IllegalArgumentException("expected string at " + pos);
		}
		pos++;
		StringBuilder sb = new StringBuilder();
		while (true) {
			char c = src.charAt(pos++);
			if (c == '"') {
				return sb.toString();
			}
			if (c == '\\') {
				char esc = src.charAt(pos++);
				switch (esc) {
				case '"': sb.append('"'); break;
				case '\\': sb.append('\\'); break;
				case '/': sb.append('/'); break;
				case 'b': sb.append('\b'); break;
				case 'f': sb.append('\f'); break;
				case 'n': sb.append('\n'); break;
				case 'r': sb.append('\r'); break;
				case 't': sb.append('\t'); break;
				case 'u':
					sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
					pos += 4;
					break;
				default:
					throw new IllegalArgumentException("bad escape \\" + esc);
				}
			} else {
				sb.append(c);
			}
		}
	}

	private Object parseNumber() {
		int start = pos;
		while (pos < src.length() && "+-0123456789.eE".indexOf(src.charAt(pos)) >= 0) {
			pos++;
		}
		String num = src.substring(start, pos);
		if (num.indexOf('.') < 0 && num.indexOf('e') < 0 && num.indexOf('E') < 0) {
			return Long.parseLong(num);
		}
		return Double.parseDouble(num);
	}

	private char peek() {
		return src.charAt(pos);
	}

	private void expect(String literal) {
		if (!src.startsWith(literal, pos)) {
			throw new IllegalArgumentException("expected " + literal + " at " + pos);
		}
		pos += literal.length();
	}

	private void skipWs() {
		while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
			pos++;
		}
	}

	// ---------- writing ----------

	/** Serializes a parsed JSON tree (Map/List/String/Number/Boolean/null) back to pretty JSON. */
	public static String write(Object value) {
		StringBuilder sb = new StringBuilder(256);
		write(sb, value, 0);
		sb.append('\n');
		return sb.toString();
	}

	private static void write(StringBuilder sb, Object value, int indent) {
		if (value == null) {
			sb.append("null");
		} else if (value instanceof String) {
			writeString(sb, (String) value);
		} else if (value instanceof Map) {
			Map<?, ?> map = (Map<?, ?>) value;
			if (map.isEmpty()) {
				sb.append("{}");
				return;
			}
			sb.append("{\n");
			int i = 0;
			for (Map.Entry<?, ?> e : map.entrySet()) {
				pad(sb, indent + 1);
				writeString(sb, String.valueOf(e.getKey()));
				sb.append(": ");
				write(sb, e.getValue(), indent + 1);
				if (++i < map.size()) {
					sb.append(',');
				}
				sb.append('\n');
			}
			pad(sb, indent);
			sb.append('}');
		} else if (value instanceof List) {
			List<?> list = (List<?>) value;
			if (list.isEmpty()) {
				sb.append("[]");
				return;
			}
			sb.append("[\n");
			for (int i = 0; i < list.size(); i++) {
				pad(sb, indent + 1);
				write(sb, list.get(i), indent + 1);
				if (i + 1 < list.size()) {
					sb.append(',');
				}
				sb.append('\n');
			}
			pad(sb, indent);
			sb.append(']');
		} else { // Boolean, Long, Double
			sb.append(value.toString());
		}
	}

	private static void pad(StringBuilder sb, int indent) {
		for (int i = 0; i < indent; i++) {
			sb.append("  ");
		}
	}

	public static void writeString(StringBuilder sb, String s) {
		sb.append('"');
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
			case '"': sb.append("\\\""); break;
			case '\\': sb.append("\\\\"); break;
			case '\n': sb.append("\\n"); break;
			case '\r': sb.append("\\r"); break;
			case '\t': sb.append("\\t"); break;
			default:
				if (c < 0x20) {
					sb.append(String.format("\\u%04x", (int) c));
				} else {
					sb.append(c);
				}
			}
		}
		sb.append('"');
	}
}
