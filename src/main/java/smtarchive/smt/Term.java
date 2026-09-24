package smtarchive.smt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Term AST. Unsupported constructs are kept in the tree (never dropped) and flagged. */
public sealed interface Term
        permits Term.Num, Term.BoolLit, Term.Sym, Term.App, Term.Let, Term.Annot, Term.Unsupported {

    record Num(Rational value, boolean isInt, String raw) implements Term {}
    record BoolLit(boolean value) implements Term {}
    record Sym(String name) implements Term {}
    record App(String op, List<Term> args, String raw) implements Term {}
    record Let(List<Binding> bindings, Term body) implements Term {
        public record Binding(String name, Term value) {}
    }
    record Annot(Term inner, Map<String, String> attrs) implements Term {}
    record Unsupported(String kind, String description, String raw, List<Term> children) implements Term {}

    final class Builder {
        public static Term from(SExpr e) {
            if (e instanceof SExpr.Atom a) {
                String t = SExpr.unquote(a.token());
                if (t.equals("true")) return new BoolLit(true);
                if (t.equals("false")) return new BoolLit(false);
                if (isNumeral(t)) {
                    boolean isInt = !t.contains(".");
                    return new Num(Rational.parse(t), isInt, t);
                }
                return new Sym(t);
            }
            SExpr.List l = (SExpr.List) e;
            if (l.items().isEmpty()) return new Unsupported("empty-list", "empty application", "()", List.of());
            if (!(l.items().get(0) instanceof SExpr.Atom)) {
                // operator is a parenthesised expression, e.g. ((as const (Array I E)) v)
                SExpr opExpr = l.items().get(0);
                if (opExpr instanceof SExpr.List ol && "as".equals(ol.headToken())
                        && ol.items().size() == 3 && ol.items().get(1) instanceof SExpr.Atom ca
                        && "const".equals(SExpr.unquote(((SExpr.Atom) ol.items().get(1)).token()))) {
                    return constArrayWithValue(l);
                }
                return new Unsupported("higher-order", "compound operator is not supported",
                        l.toString(), List.of(from(opExpr)));
            }
            String h = l.headToken();

            switch (h) {
                case "let" -> {
                    if (l.items().size() != 3 || !(l.items().get(1) instanceof SExpr.List bs))
                        return bad("malformed let", l);
                    List<Let.Binding> bindings = new ArrayList<>();
                    for (SExpr b : bs.items()) {
                        if (b instanceof SExpr.List bl && bl.items().size() == 2
                                && bl.items().get(0) instanceof SExpr.Atom na) {
                            bindings.add(new Let.Binding(SExpr.unquote(na.token()), from(bl.items().get(1))));
                        } else {
                            bindings.add(new Let.Binding("?", new Unsupported("bad-binding", "malformed let binding", b.toString(), List.of())));
                        }
                    }
                    return new Let(bindings, from(l.items().get(2)));
                }
                case "!" -> {
                    if (l.items().size() < 3) return bad("malformed annotation", l);
                    java.util.LinkedHashMap<String, String> attrs = new java.util.LinkedHashMap<>();
                    for (int i = 2; i < l.items().size(); i += 2) {
                        String key = l.items().get(i) instanceof SExpr.Atom ka ? SExpr.unquote(ka.token()) : "?";
                        String val = i + 1 < l.items().size() && l.items().get(i + 1) instanceof SExpr.Atom va
                                ? SExpr.unquote(va.token()) : "?";
                        attrs.put(key.replaceFirst("^:", ""), val);
                    }
                    return new Annot(from(l.items().get(1)), attrs);
                }
                case "forall", "exists" -> {
                    return new Unsupported("quantifier", h + " is outside the quantifier-free fragment",
                            l.toString(), List.of());
                }
                case "_" -> {
                    // indexed operator / sort-indexed literal, e.g. (_ BitVec 32), as const
                    if (l.items().size() >= 2 && l.items().get(1) instanceof SExpr.Atom ia) {
                        String indexed = SExpr.unquote(ia.token());
                        if (indexed.startsWith("bv") || indexed.contains("BitVec")
                                || indexed.equals("extract") || indexed.equals("zero_extend")
                                || indexed.equals("sign_extend") || indexed.startsWith("rotate")
                                || indexed.equals("concat")) {
                            return new Unsupported("bitvector",
                                    "bitvector construct '" + indexed + "' is not in the supported fragment",
                                    l.toString(), List.of());
                        }
                        if (indexed.equals("const")) {
                            // ((as const (Array I E)) v)
                            List<Term> args = new ArrayList<>();
                            for (int i = 2; i < l.items().size(); i++) args.add(from(l.items().get(i)));
                            return new App("const-array", args, l.toString());
                        }
                    }
                    return new Unsupported("indexed", "unsupported indexed operator", l.toString(), List.of());
                }
                case "as" -> {
                    // (as const (Array I E)) used as an operator head: wrap as const-array with value applied
                    if (l.items().size() == 3 && l.items().get(1) instanceof SExpr.Atom ca
                            && "const".equals(SExpr.unquote(ca.token()))) {
                        return new App("const-array-sort", List.of(), l.toString());
                    }
                    Sort s = Sort.fromSExpr(l.items().size() > 2 ? l.items().get(2) : l.items().get(1));
                    return new Unsupported("cast:" + s, "explicit 'as' cast to " + s, l.toString(), List.of());
                }
                default -> {
                    List<Term> args = new ArrayList<>();
                    for (int i = 1; i < l.items().size(); i++) args.add(from(l.items().get(i)));
                    return new App(h, args, l.toString());
                }
            }
        }

        /** Pattern used when a const-array operator is applied: ((as const ...) v). */
        public static Term constArrayWithValue(SExpr.List application) {
            // application.items() = [ (as const (Array I E)), value ]
            if (application.items().size() == 2) {
                Term v = from(application.items().get(1));
                return new App("const-array", List.of(v), application.toString());
            }
            return new Unsupported("array", "malformed constant array", application.toString(), List.of());
        }

        private static Term bad(String why, SExpr.List l) {
            return new Unsupported("malformed", why, l.toString(), List.of());
        }

        public static boolean isNumeral(String t) {
            if (t.isEmpty()) return false;
            int start = t.charAt(0) == '-' ? 1 : 0;
            boolean dot = false, slash = false;
            for (int i = start; i < t.length(); i++) {
                char c = t.charAt(i);
                if (c == '.' && !dot) { dot = true; continue; }
                if (c == '/' && !slash && i > start) { slash = true; continue; }
                if (!Character.isDigit(c)) return false;
            }
            return start < t.length();
        }
    }
}
