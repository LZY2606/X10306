package witnessroom.smt;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses a full SMT-LIB script into an ordered command stream.
 * The command stream, push/pop levels, named assertions,
 * check-sat-assuming literals, response order anchors and the logic
 * declaration are all preserved verbatim.
 */
public final class SessionParser {

    public record ParseOutcome(List<Command> commands, List<String> diagnostics) {
        public boolean ok() { return diagnostics.isEmpty(); }
    }

    public static ParseOutcome parse(String src) {
        List<String> diags = new ArrayList<>();
        List<Command> out = new ArrayList<>();
        List<Lexer.Tok> toks;
        try {
            toks = Lexer.tokenize(src);
        } catch (ParseException e) {
            diags.add(e.getMessage());
            return new ParseOutcome(out, diags);
        }
        int[] pos = {0};
        int index = 0;
        while (pos[0] < toks.size()) {
            Lexer.Tok start = toks.get(pos[0]);
            if (!start.text().equals("(")) {
                diags.add("expected '(' but found '" + start.text() + "' (line " + start.line() + ", col " + start.col() + ")");
                return new ParseOutcome(out, diags);
            }
            try {
                SExpr form = parseForm(toks, pos);
                int endOffset = pos[0] > 0 ? endOffsetOf(toks.get(pos[0] - 1), src) : src.length();
                String raw = src.substring(start.offset(), Math.min(endOffset, src.length()));
                out.add(classify(index++, raw, start.line(), start.col(), form, diags));
            } catch (ParseException e) {
                diags.add(e.getMessage());
                return new ParseOutcome(out, diags);
            }
        }
        return new ParseOutcome(out, diags);
    }

    private static int endOffsetOf(Lexer.Tok t, String src) {
        int i = t.offset() + t.text().length();
        return Math.min(i, src.length());
    }

    private static SExpr parseForm(List<Lexer.Tok> toks, int[] pos) throws ParseException {
        Lexer.Tok t = toks.get(pos[0]++);
        if (t.text().equals(")")) throw new ParseException("unexpected ')'", t.line(), t.col());
        if (!t.text().equals("(")) return new SExpr.Atom(unquote(t.text()));
        List<SExpr> items = new ArrayList<>();
        while (true) {
            if (pos[0] >= toks.size()) throw new ParseException("unbalanced parentheses", t.line(), t.col());
            Lexer.Tok nxt = toks.get(pos[0]);
            if (nxt.text().equals(")")) { pos[0]++; return new SExpr.Lst(items); }
            items.add(parseForm(toks, pos));
        }
    }

    private static String unquote(String text) {
        if (text.startsWith("|") && text.endsWith("|") && text.length() >= 2)
            return text.substring(1, text.length() - 1);
        return text;
    }

    private static Command classify(int index, String raw, int line, int col, SExpr form, List<String> diags) {
        Command.Kind kind = Command.Kind.UNKNOWN;
        String name = null, declName = null, sort = null, diagnostic = null;
        SExpr term = null;
        List<SExpr> assumptions = null;
        int levels = 0;
        boolean supported = true;

        if (form instanceof SExpr.Lst l && !l.items().isEmpty() && l.items().get(0) instanceof SExpr.Atom head) {
            switch (head.value()) {
                case "set-logic" -> {
                    kind = Command.Kind.SET_LOGIC;
                    if (l.items().size() > 1 && l.items().get(1) instanceof SExpr.Atom lg) {
                        declName = lg.value();
                        if (lg.value().contains("BV") || lg.value().contains("FP") || lg.value().contains("S") && lg.value().endsWith("S")) {
                            diagnostic = "logic '" + lg.value() + "' mentions theories outside the local finite subset; affected terms are flagged, not dropped";
                        }
                    }
                }
                case "declare-const" -> {
                    kind = Command.Kind.DECLARE_CONST;
                    if (l.items().size() >= 3) {
                        declName = SExpr.render(l.items().get(1));
                        sort = SExpr.render(l.items().get(2));
                        if (!supportedSort(sort)) {
                            supported = false;
                            diagnostic = "sort '" + sort + "' is outside the local finite subset; value kept raw with diagnostic";
                        }
                    }
                }
                case "declare-fun" -> {
                    kind = Command.Kind.DECLARE_FUN;
                    if (l.items().size() >= 4) {
                        declName = SExpr.render(l.items().get(1));
                        sort = SExpr.render(l.items().get(3));
                        if (l.items().get(2) instanceof SExpr.Lst args && !args.items().isEmpty()) {
                            supported = false;
                            diagnostic = "uninterpreted functions with arguments are outside the local subset";
                        } else if (!supportedSort(sort)) {
                            supported = false;
                            diagnostic = "sort '" + sort + "' is outside the local finite subset; value kept raw with diagnostic";
                        }
                    }
                }
                case "define-fun" -> {
                    kind = Command.Kind.DEFINE_FUN;
                    if (l.items().size() >= 5) {
                        declName = SExpr.render(l.items().get(1));
                        sort = SExpr.render(l.items().get(3));
                        term = l.items().get(4);
                        if (l.items().get(2) instanceof SExpr.Lst args && !args.items().isEmpty()) {
                            supported = false;
                            diagnostic = "functions with arguments are outside the local evaluation subset";
                        }
                    }
                }
                case "assert" -> {
                    kind = Command.Kind.ASSERT;
                    if (l.items().size() >= 2) {
                        SExpr t = l.items().get(1);
                        if (t instanceof SExpr.Lst tl && tl.items().size() >= 3
                                && SExpr.isAtom(tl.items().get(0), "!")) {
                            term = tl.items().get(1);
                            for (int i = 2; i + 1 < tl.items().size(); i++) {
                                if (SExpr.isAtom(tl.items().get(i), ":named"))
                                    name = SExpr.render(tl.items().get(i + 1));
                            }
                        } else {
                            term = t;
                        }
                    }
                }
                case "push" -> {
                    kind = Command.Kind.PUSH;
                    levels = l.items().size() > 1 ? parseLevel(l.items().get(1)) : 1;
                }
                case "pop" -> {
                    kind = Command.Kind.POP;
                    levels = l.items().size() > 1 ? parseLevel(l.items().get(1)) : 1;
                }
                case "check-sat" -> kind = Command.Kind.CHECK_SAT;
                case "check-sat-assuming" -> {
                    kind = Command.Kind.CHECK_SAT_ASSUMING;
                    assumptions = new ArrayList<>();
                    if (l.items().size() > 1 && l.items().get(1) instanceof SExpr.Lst lits)
                        assumptions.addAll(lits.items());
                }
                case "get-model" -> kind = Command.Kind.GET_MODEL;
                case "get-unsat-core" -> kind = Command.Kind.GET_UNSAT_CORE;
                case "get-value" -> kind = Command.Kind.GET_VALUE;
                case "exit" -> kind = Command.Kind.EXIT;
                default -> {
                    kind = Command.Kind.UNKNOWN;
                    supported = false;
                    diagnostic = "command '" + head.value() + "' is not interpreted locally; preserved verbatim in the command stream";
                }
            }
        }
        return new Command(index, raw, line, col, kind, form, name, term, assumptions,
                levels, declName, sort, supported, diagnostic);
    }

    private static int parseLevel(SExpr e) {
        try { return Integer.parseInt(SExpr.render(e)); } catch (NumberFormatException ex) { return 1; }
    }

    public static boolean supportedSort(String sort) {
        return sort.equals("Bool") || sort.equals("Int") || sort.equals("Real")
                || sort.equals("(Array Int Int)") || sort.equals("(Array Int Bool)");
    }
}
