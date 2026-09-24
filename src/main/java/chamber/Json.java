package chamber;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal dependency-free JSON reader/writer used for event payloads and export. */
public final class Json {
    private Json() {}

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(value, sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(Object v, StringBuilder sb) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            sb.append('"');
            for (char c : s.toCharArray()) {
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> {
                        if (c < 0x20) sb.append("\\u%04x".formatted((int) c));
                        else sb.append(c);
                    }
                }
            }
            sb.append('"');
        } else if (v instanceof Number || v instanceof Boolean) {
            sb.append(v.toString());
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                write(String.valueOf(e.getKey()), sb);
                sb.append(':');
                write(e.getValue(), sb);
            }
            sb.append('}');
        } else if (v instanceof List<?> l) {
            sb.append('[');
            boolean first = true;
            for (Object o : l) {
                if (!first) sb.append(',');
                first = false;
                write(o, sb);
            }
            sb.append(']');
        } else {
            write(v.toString(), sb);
        }
    }

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (!p.eof()) throw new IllegalArgumentException("trailing content at " + p.pos);
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(String text) {
        return (Map<String, Object>) parse(text);
    }

    private static final class Parser {
        final String s;
        int pos;

        Parser(String s) { this.s = s; }

        boolean eof() { return pos >= s.length(); }

        void ws() {
            while (!eof() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        Object value() {
            if (eof()) throw new IllegalArgumentException("unexpected end of json");
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> { expect("true"); yield Boolean.TRUE; }
                case 'f' -> { expect("false"); yield Boolean.FALSE; }
                case 'n' -> { expect("null"); yield null; }
                default -> number();
            };
        }

        void expect(String lit) {
            if (!s.startsWith(lit, pos)) throw new IllegalArgumentException("expected " + lit + " at " + pos);
            pos += lit.length();
        }

        Map<String, Object> object() {
            pos++;
            Map<String, Object> m = new LinkedHashMap<>();
            ws();
            if (!eof() && s.charAt(pos) == '}') { pos++; return m; }
            while (true) {
                ws();
                String k = string();
                ws();
                if (s.charAt(pos) != ':') throw new IllegalArgumentException("expected ':' at " + pos);
                pos++;
                ws();
                m.put(k, value());
                ws();
                char c = s.charAt(pos++);
                if (c == '}') return m;
                if (c != ',') throw new IllegalArgumentException("expected ',' at " + (pos - 1));
            }
        }

        List<Object> array() {
            pos++;
            List<Object> l = new ArrayList<>();
            ws();
            if (!eof() && s.charAt(pos) == ']') { pos++; return l; }
            while (true) {
                ws();
                l.add(value());
                ws();
                char c = s.charAt(pos++);
                if (c == ']') return l;
                if (c != ',') throw new IllegalArgumentException("expected ',' at " + (pos - 1));
            }
        }

        String string() {
            if (s.charAt(pos) != '"') throw new IllegalArgumentException("expected string at " + pos);
            pos++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char e = s.charAt(pos++);
                    switch (e) {
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'r' -> sb.append('\r');
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'u' -> {
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> throw new IllegalArgumentException("bad escape \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        Object number() {
            int start = pos;
            while (!eof() && "-+0123456789.eE".indexOf(s.charAt(pos)) >= 0) pos++;
            String t = s.substring(start, pos);
            if (t.isEmpty()) throw new IllegalArgumentException("bad value at " + start);
            try {
                if (t.contains(".") || t.contains("e") || t.contains("E")) return Double.parseDouble(t);
                return Long.parseLong(t);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("bad number '" + t + "' at " + start);
            }
        }
    }
}
