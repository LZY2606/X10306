package witness.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal dependency-free JSON parser / writer, sufficient for event logs,
 * API payloads and export bundles. Map iteration order is preserved.
 */
public final class Json {
    private Json() {}

    public static final Object NIL = new Object() {
        @Override public String toString() { return "null"; }
    };

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeTo(sb, value);
        return sb.toString();
    }

    private static void writeTo(StringBuilder sb, Object value) {
        if (value == null || value == NIL) {
            sb.append("null");
        } else if (value instanceof String s) {
            writeString(sb, s);
        } else if (value instanceof Boolean b) {
            sb.append(b.booleanValue());
        } else if (value instanceof Number n) {
            String t = n.toString();
            sb.append(t);
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeTo(sb, e.getValue());
            }
            sb.append('}');
        } else if (value instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (Object item : it) {
                if (!first) sb.append(',');
                first = false;
                writeTo(sb, item);
            }
            sb.append(']');
        } else if (value instanceof Object[] arr) {
            sb.append('[');
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) sb.append(',');
                writeTo(sb, arr[i]);
            }
            sb.append(']');
        } else {
            writeString(sb, String.valueOf(value));
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        return (Map<String, Object>) parse(text);
    }

    public static List<Object> parseArray(String text) {
        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) parse(text);
        return list;
    }

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.readValue();
        p.skipWs();
        if (!p.eof()) throw new JsonException("trailing characters at " + p.pos);
        return v;
    }

    public static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    public static int integer(Map<String, Object> m, String key, int fallback) {
        Object v = m.get(key);
        return v instanceof Number n ? n.intValue() : fallback;
    }

    public static long longInt(Map<String, Object> m, String key, long fallback) {
        Object v = m.get(key);
        return v instanceof Number n ? n.longValue() : fallback;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Map<?, ?> ? (Map<String, Object>) v : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof List<?> ? (List<Object>) v : new ArrayList<>();
    }

    public static Map<String, Object> obj() {
        return new LinkedHashMap<>();
    }

    public static List<Object> arr() {
        return new ArrayList<>();
    }

    public static final class JsonException extends RuntimeException {
        public JsonException(String msg) { super(msg); }
    }

    private static final class Parser {
        final String text;
        int pos;

        Parser(String text) { this.text = text; }

        boolean eof() { return pos >= text.length(); }

        void skipWs() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++;
        }

        Object readValue() {
            skipWs();
            if (eof()) throw new JsonException("unexpected end of input");
            char c = text.charAt(pos);
            return switch (c) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't', 'f' -> readBoolean();
                case 'n' -> readNull();
                default -> readNumber();
            };
        }

        Map<String, Object> readObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            expect('{');
            skipWs();
            if (peek() == '}') { pos++; return map; }
            while (true) {
                skipWs();
                String key = readString();
                skipWs();
                expect(':');
                Object value = readValue();
                map.put(key, value);
                skipWs();
                char c = next();
                if (c == '}') break;
                if (c != ',') throw new JsonException("expected , or } at " + pos);
            }
            return map;
        }

        List<Object> readArray() {
            List<Object> list = new ArrayList<>();
            expect('[');
            skipWs();
            if (peek() == ']') { pos++; return list; }
            while (true) {
                list.add(readValue());
                skipWs();
                char c = next();
                if (c == ']') break;
                if (c != ',') throw new JsonException("expected , or ] at " + pos);
            }
            return list;
        }

        String readString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (eof()) throw new JsonException("unterminated string");
                char c = next();
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char e = next();
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            if (pos + 4 > text.length()) throw new JsonException("bad unicode escape");
                            sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> throw new JsonException("bad escape \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        Boolean readBoolean() {
            if (text.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
            if (text.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
            throw new JsonException("invalid literal at " + pos);
        }

        Object readNull() {
            if (text.startsWith("null", pos)) { pos += 4; return NIL; }
            throw new JsonException("invalid literal at " + pos);
        }

        Number readNumber() {
            int start = pos;
            if (peek() == '-') pos++;
            while (!eof() && Character.isDigit(peek())) pos++;
            boolean floating = false;
            if (!eof() && peek() == '.') {
                floating = true;
                pos++;
                while (!eof() && Character.isDigit(peek())) pos++;
            }
            if (!eof() && (peek() == 'e' || peek() == 'E')) {
                floating = true;
                pos++;
                if (!eof() && (peek() == '+' || peek() == '-')) pos++;
                while (!eof() && Character.isDigit(peek())) pos++;
            }
            String token = text.substring(start, pos);
            if (token.isEmpty() || "-".equals(token)) throw new JsonException("invalid number at " + start);
            if (floating) return Double.parseDouble(token);
            try {
                return Long.parseLong(token);
            } catch (NumberFormatException ex) {
                return Double.parseDouble(token);
            }
        }

        char peek() { return text.charAt(pos); }
        char next() { return text.charAt(pos++); }
        void expect(char c) {
            if (eof() || text.charAt(pos) != c) throw new JsonException("expected '" + c + "' at " + pos);
            pos++;
        }
    }
}
