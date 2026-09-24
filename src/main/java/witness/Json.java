package witness;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal, dependency-free JSON parser / canonical writer used for storage, events and HTTP. */
public final class Json {
    private Json() {}

    public static Object parse(String s) {
        Parser p = new Parser(s);
        p.skipWs();
        Object v = p.value();
        p.skipWs();
        if (!p.eof()) throw new IllegalArgumentException("trailing characters at " + p.pos);
        return v;
    }

    public static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    public static List<Object> arr(Object... vs) {
        List<Object> l = new ArrayList<>();
        for (Object v : vs) l.add(v);
        return l;
    }

    public static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        write(sb, v);
        return sb.toString();
    }

    public static String pretty(Object v) {
        StringBuilder sb = new StringBuilder();
        pretty(sb, v, 0);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v) {
        if (v == null) sb.append("null");
        else if (v instanceof Boolean || v instanceof Number) sb.append(v.toString());
        else if (v instanceof String) writeStr(sb, (String) v);
        else if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeStr(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof Iterable) {
            sb.append('[');
            boolean first = true;
            for (Object e : (Iterable<?>) v) {
                if (!first) sb.append(',');
                first = false;
                write(sb, e);
            }
            sb.append(']');
        } else if (v instanceof Object[]) {
            sb.append('[');
            Object[] a = (Object[]) v;
            for (int i = 0; i < a.length; i++) {
                if (i > 0) sb.append(',');
                write(sb, a[i]);
            }
            sb.append(']');
        } else writeStr(sb, v.toString());
    }

    private static void pretty(StringBuilder sb, Object v, int indent) {
        if (v instanceof Map && !((Map<?, ?>) v).isEmpty()) {
            sb.append("{\n");
            int i = 0, n = ((Map<?, ?>) v).size();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                pad(sb, indent + 1);
                writeStr(sb, String.valueOf(e.getKey()));
                sb.append(": ");
                pretty(sb, e.getValue(), indent + 1);
                if (++i < n) sb.append(',');
                sb.append('\n');
            }
            pad(sb, indent);
            sb.append('}');
        } else if (v instanceof List && !((List<?>) v).isEmpty()) {
            sb.append("[\n");
            int n = ((List<?>) v).size();
            for (int i = 0; i < n; i++) {
                pad(sb, indent + 1);
                pretty(sb, ((List<?>) v).get(i), indent + 1);
                if (i + 1 < n) sb.append(',');
                sb.append('\n');
            }
            pad(sb, indent);
            sb.append(']');
        } else write(sb, v);
    }

    private static void pad(StringBuilder sb, int n) {
        for (int i = 0; i < n; i++) sb.append("  ");
    }

    private static void writeStr(StringBuilder sb, String s) {
        sb.append('"');
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }

    // ---- accessor helpers ----
    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object o) { return (Map<String, Object>) o; }
    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object o) { return (List<Object>) o; }

    public static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    public static int integer(Map<String, Object> m, String key, int dflt) {
        Object v = m.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v != null) try { return Integer.parseInt(v.toString()); } catch (NumberFormatException ignored) {}
        return dflt;
    }

    public static boolean bool(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return Boolean.TRUE.equals(v);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> mapAt(Map<String, Object> m, String key) {
        return (Map<String, Object>) m.get(key);
    }

    @SuppressWarnings("unchecked")
    public static List<Object> listAt(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? new ArrayList<>() : (List<Object>) v;
    }

    private static final class Parser {
        final String s;
        int pos;
        Parser(String s) { this.s = s; }
        boolean eof() { return pos >= s.length(); }
        char peek() { return s.charAt(pos); }
        void skipWs() { while (!eof() && Character.isWhitespace(peek())) pos++; }

        Object value() {
            skipWs();
            if (eof()) throw new IllegalArgumentException("unexpected end of input");
            char c = peek();
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (c == 't' || c == 'f') return bool();
            if (c == 'n') return nul();
            return number();
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            expect('{'); skipWs();
            if (peek() == '}') { pos++; return m; }
            while (true) {
                skipWs();
                String k = string();
                skipWs();
                expect(':');
                Object v = value();
                m.put(k, v);
                skipWs();
                char c = next();
                if (c == '}') return m;
                if (c != ',') throw new IllegalArgumentException("expected , or } at " + pos);
            }
        }

        List<Object> array() {
            List<Object> l = new ArrayList<>();
            expect('['); skipWs();
            if (peek() == ']') { pos++; return l; }
            while (true) {
                Object v = value();
                l.add(v);
                skipWs();
                char c = next();
                if (c == ']') return l;
                if (c != ',') throw new IllegalArgumentException("expected , or ] at " + pos);
            }
        }

        String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (eof()) throw new IllegalArgumentException("unterminated string");
                char c = next();
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char e = next();
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'u':
                            int code = Integer.parseInt(s.substring(pos, pos + 4), 16);
                            pos += 4;
                            sb.append((char) code);
                            break;
                        default: throw new IllegalArgumentException("bad escape \\" + e);
                    }
                } else sb.append(c);
            }
        }

        Boolean bool() {
            if (s.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
            if (s.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
            throw new IllegalArgumentException("invalid literal at " + pos);
        }

        Object nul() {
            if (s.startsWith("null", pos)) { pos += 4; return null; }
            throw new IllegalArgumentException("invalid literal at " + pos);
        }

        Object number() {
            int start = pos;
            if (peek() == '-' || peek() == '+') pos++;
            while (!eof() && (Character.isDigit(peek()) || peek() == '.' || peek() == 'e' || peek() == 'E' || peek() == '+' || peek() == '-')) pos++;
            String tok = s.substring(start, pos);
            if (tok.isEmpty()) throw new IllegalArgumentException("invalid number at " + start);
            if (tok.contains(".") || tok.contains("e") || tok.contains("E")) return Double.parseDouble(tok);
            try { return Long.parseLong(tok); } catch (NumberFormatException e) {
                return new java.math.BigInteger(tok);
            }
        }

        char next() { return s.charAt(pos++); }
        void expect(char c) {
            if (eof() || next() != c) throw new IllegalArgumentException("expected '" + c + "' at " + (pos - 1));
        }
    }
}
