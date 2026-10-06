package org.xulj.bridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal JSON writer/parser, so the bridge needs nothing beyond the JDK. */
public final class Json {
    private Json() {}

    public static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, v);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object v) {
        if (v == null) sb.append("null");
        else if (v instanceof String) writeString(sb, (String) v);
        else if (v instanceof Boolean) sb.append(v.toString());
        else if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) sb.append(v.toString());
        else if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) sb.append('0');
            else if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append((long) d);
            else sb.append(d);
        } else if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeValue(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof Iterable) {
            sb.append('[');
            boolean first = true;
            for (Object item : (Iterable<?>) v) {
                if (!first) sb.append(',');
                first = false;
                writeValue(sb, item);
            }
            sb.append(']');
        } else writeString(sb, v.toString());
    }

    private static void writeString(StringBuilder sb, String s) {
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
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }

    /** Parses into LinkedHashMap, ArrayList, String, Double, Boolean or null. */
    public static Object parse(String text) {
        int[] pos = {0};
        Object v = parseValue(text, pos);
        skipWs(text, pos);
        if (pos[0] != text.length()) throw new IllegalArgumentException("trailing characters");
        return v;
    }

    private static void skipWs(String t, int[] p) {
        while (p[0] < t.length() && Character.isWhitespace(t.charAt(p[0]))) p[0]++;
    }

    private static Object parseValue(String t, int[] p) {
        skipWs(t, p);
        if (p[0] >= t.length()) throw new IllegalArgumentException("unexpected end");
        char c = t.charAt(p[0]);
        if (c == '{') {
            Map<String, Object> m = new LinkedHashMap<>();
            p[0]++;
            skipWs(t, p);
            if (t.charAt(p[0]) == '}') { p[0]++; return m; }
            while (true) {
                skipWs(t, p);
                String key = parseString(t, p);
                skipWs(t, p);
                if (t.charAt(p[0]++) != ':') throw new IllegalArgumentException("expected ':'");
                m.put(key, parseValue(t, p));
                skipWs(t, p);
                char d = t.charAt(p[0]++);
                if (d == ',') continue;
                if (d == '}') return m;
                throw new IllegalArgumentException("expected ',' or '}'");
            }
        }
        if (c == '[') {
            List<Object> l = new ArrayList<>();
            p[0]++;
            skipWs(t, p);
            if (t.charAt(p[0]) == ']') { p[0]++; return l; }
            while (true) {
                l.add(parseValue(t, p));
                skipWs(t, p);
                char d = t.charAt(p[0]++);
                if (d == ',') continue;
                if (d == ']') return l;
                throw new IllegalArgumentException("expected ',' or ']'");
            }
        }
        if (c == '"') return parseString(t, p);
        if (t.startsWith("true", p[0])) { p[0] += 4; return Boolean.TRUE; }
        if (t.startsWith("false", p[0])) { p[0] += 5; return Boolean.FALSE; }
        if (t.startsWith("null", p[0])) { p[0] += 4; return null; }
        int start = p[0];
        while (p[0] < t.length() && "+-0123456789.eE".indexOf(t.charAt(p[0])) >= 0) p[0]++;
        if (start == p[0]) throw new IllegalArgumentException("unexpected character");
        return Double.parseDouble(t.substring(start, p[0]));
    }

    private static String parseString(String t, int[] p) {
        if (t.charAt(p[0]) != '"') throw new IllegalArgumentException("expected string");
        p[0]++;
        StringBuilder sb = new StringBuilder();
        while (t.charAt(p[0]) != '"') {
            char c = t.charAt(p[0]++);
            if (c != '\\') { sb.append(c); continue; }
            char e = t.charAt(p[0]++);
            switch (e) {
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'u': sb.append((char) Integer.parseInt(t.substring(p[0], p[0] + 4), 16)); p[0] += 4; break;
                default: sb.append(e);
            }
        }
        p[0]++;
        return sb.toString();
    }
}
