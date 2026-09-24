package org.witness.smt.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal JSON parser/writer used for persistence, export/import and the web API. */
public final class Json {
    private Json() {}

    public static final class Obj extends LinkedHashMap<String, Object> {
        public Obj getObj(String k) { Object v = get(k); return v instanceof Obj o ? o : new Obj(); }
        public List<Object> getArr(String k) { Object v = get(k); return v instanceof List<?> l ? new ArrayList<>(l) : new ArrayList<>(); }
        public String getStr(String k) { Object v = get(k); return v == null ? null : v.toString(); }
        public long getLong(String k, long dflt) { Object v = get(k); return v instanceof Number n ? n.longValue() : dflt; }
        public boolean has(String k) { return containsKey(k); }
    }

    public static ArrayList<Object> arr(Object... xs) {
        ArrayList<Object> a = new ArrayList<>();
        for (Object x : xs) a.add(x);
        return a;
    }

    public static Obj obj(Object... kv) {
        Obj o = new Obj();
        for (int i = 0; i + 1 < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]);
        return o;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asArr(Object o) { return (List<Object>) o; }
    @SuppressWarnings("unchecked")
    public static Obj asObj(Object o) { return (Obj) o; }

    public static String write(Object o) { return write(o, false); }

    public static String write(Object o, boolean pretty) {
        StringBuilder sb = new StringBuilder();
        writeTo(sb, o, pretty ? 0 : -1);
        return sb.toString();
    }

    private static void writeTo(StringBuilder sb, Object o, int indent) {
        boolean pretty = indent >= 0;
        if (o == null) sb.append("null");
        else if (o instanceof Boolean b) sb.append(b.booleanValue());
        else if (o instanceof Number n) sb.append(n.toString());
        else if (o instanceof String s) writeStr(sb, s);
        else if (o instanceof Map<?, ?> m) {
            if (m.isEmpty()) { sb.append("{}"); return; }
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                if (pretty) sb.append('\n').append("  ".repeat(indent + 1));
                writeStr(sb, String.valueOf(e.getKey()));
                sb.append(pretty ? ": " : ":");
                writeTo(sb, e.getValue(), pretty ? indent + 1 : -1);
            }
            if (pretty) sb.append('\n').append("  ".repeat(indent));
            sb.append('}');
        } else if (o instanceof List<?> l) {
            if (l.isEmpty()) { sb.append("[]"); return; }
            sb.append('[');
            boolean first = true;
            for (Object x : l) {
                if (!first) sb.append(',');
                first = false;
                if (pretty) sb.append('\n').append("  ".repeat(indent + 1));
                writeTo(sb, x, pretty ? indent + 1 : -1);
            }
            if (pretty) sb.append('\n').append("  ".repeat(indent));
            sb.append(']');
        } else writeStr(sb, String.valueOf(o));
    }

    private static void writeStr(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.value();
        p.skipWs();
        if (!p.eof()) throw new JsonException("trailing characters at " + p.pos);
        return v;
    }

    public static final class JsonException extends RuntimeException {
        public JsonException(String m) { super(m); }
    }

    private static final class Parser {
        final String s;
        int pos;
        Parser(String s) { this.s = s; }
        boolean eof() { return pos >= s.length(); }
        void skipWs() { while (!eof() && Character.isWhitespace(s.charAt(pos))) pos++; }

        Object value() {
            skipWs();
            if (eof()) throw new JsonException("unexpected end");
            char c = s.charAt(pos);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (c == 't' || c == 'f') return bool();
            if (c == 'n') return nul();
            return number();
        }

        Obj object() {
            Obj o = new Obj();
            expect('{');
            skipWs();
            if (peek() == '}') { pos++; return o; }
            while (true) {
                skipWs();
                String k = string();
                skipWs();
                expect(':');
                Object v = value();
                o.put(k, v);
                skipWs();
                char c = next();
                if (c == ',') continue;
                if (c == '}') break;
                throw new JsonException("expected , or } at " + pos);
            }
            return o;
        }

        List<Object> array() {
            List<Object> a = new ArrayList<>();
            expect('[');
            skipWs();
            if (peek() == ']') { pos++; return a; }
            while (true) {
                a.add(value());
                skipWs();
                char c = next();
                if (c == ',') continue;
                if (c == ']') break;
                throw new JsonException("expected , or ] at " + pos);
            }
            return a;
        }

        String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
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
                            int hex = Integer.parseInt(s.substring(pos, pos + 4), 16);
                            pos += 4;
                            sb.append((char) hex);
                        }
                        default -> throw new JsonException("bad escape \\" + e);
                    }
                } else sb.append(c);
            }
        }

        Boolean bool() {
            if (s.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
            if (s.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
            throw new JsonException("bad literal at " + pos);
        }

        Object nul() {
            if (s.startsWith("null", pos)) { pos += 4; return null; }
            throw new JsonException("bad literal at " + pos);
        }

        Object number() {
            int start = pos;
            if (peek() == '-') pos++;
            boolean real = false;
            while (!eof()) {
                char c = s.charAt(pos);
                if (Character.isDigit(c)) pos++;
                else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') { real = true; pos++; }
                else break;
            }
            String t = s.substring(start, pos);
            if (t.isEmpty()) throw new JsonException("bad number at " + start);
            return real ? Double.parseDouble(t) : Long.parseLong(t);
        }

        char peek() { if (eof()) throw new JsonException("unexpected end"); return s.charAt(pos); }
        char next() { return s.charAt(pos++); }
        void expect(char c) { skipWs(); if (eof() || s.charAt(pos) != c) throw new JsonException("expected " + c + " at " + pos); pos++; }
    }
}
