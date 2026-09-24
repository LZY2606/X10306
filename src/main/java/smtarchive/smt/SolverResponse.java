package smtarchive.smt;

import java.util.ArrayList;
import java.util.List;

/**
 * A complete solver response transcript. Responses are kept in their emitted order;
 * explicit pairing with query commands happens during replay (nth query -> nth answer).
 */
public record SolverResponse(String rawText, List<Answer> answers, List<SExpr.FormatError> formatErrors) {

    public enum Kind { SAT, UNSAT, UNKNOWN, MODEL, UNSAT_CORE, ERROR, ASSERTIONS, OTHER }

    public sealed interface Answer permits Answer.Status, Answer.ModelAnswer, Answer.CoreAnswer,
            Answer.ErrorAnswer, Answer.TextAnswer {
        int order();
        int line();
        String raw();
        Kind kind();
    }
    public record Status(int order, int line, String raw, String status) implements Answer {
        public Kind kind() {
            return switch (status.trim()) {
                case "sat" -> Kind.SAT;
                case "unsat" -> Kind.UNSAT;
                default -> Kind.UNKNOWN;
            };
        }
    }
    public record ModelAnswer(int order, int line, String raw, SmtModel model) implements Answer {
        public Kind kind() { return Kind.MODEL; }
    }
    public record CoreAnswer(int order, int line, String raw, List<String> names) implements Answer {
        public Kind kind() { return Kind.UNSAT_CORE; }
    }
    public record ErrorAnswer(int order, int line, String raw, String message) implements Answer {
        public Kind kind() { return Kind.ERROR; }
    }
    public record TextAnswer(int order, int line, String raw, String text) implements Answer {
        public Kind kind() { return Kind.OTHER; }
    }

    public static SolverResponse parse(String rawText) {
        List<SExpr.FormatError> errors = new ArrayList<>();
        List<Answer> answers = new ArrayList<>();
        int order = 0;
        int i = 0, n = rawText.length();
        while (i < n) {
            // skip blanks / comments
            while (i < n && (Character.isWhitespace(rawText.charAt(i)) || rawText.charAt(i) == ';')) {
                if (rawText.charAt(i) == ';') while (i < n && rawText.charAt(i) != '\n') i++;
                else i++;
            }
            if (i >= n) break;
            int startLine = lineOf(rawText, i);
            if (rawText.charAt(i) == '(') {
                int j = i, depth = 0;
                boolean inStr = false, inBar = false;
                while (j < n) {
                    char c = rawText.charAt(j);
                    if (inStr) { if (c == '"') inStr = false; }
                    else if (inBar) { if (c == '|') inBar = false; }
                    else if (c == '"') inStr = true;
                    else if (c == '|') inBar = true;
                    else if (c == '(') depth++;
                    else if (c == ')') { depth--; if (depth == 0) { j++; break; } }
                    j++;
                }
                String block = rawText.substring(i, Math.min(j, n));
                answers.add(classifyBlock(order++, startLine, block, errors));
                i = j;
            } else {
                int j = i;
                while (j < n && rawText.charAt(j) != '\n') j++;
                String t = rawText.substring(i, j).trim();
                if (t.equals("sat") || t.equals("unsat") || t.equals("unknown"))
                    answers.add(new Status(order++, startLine, t, t));
                else if (t.startsWith("(error") || t.startsWith("(error"))
                    answers.add(new ErrorAnswer(order++, startLine, t, t));
                else if (!t.isEmpty())
                    answers.add(new TextAnswer(order++, startLine, t, t));
                i = j + 1;
            }
        }
        return new SolverResponse(rawText, answers, errors);
    }

    private static int lineOf(String s, int offset) {
        int line = 1;
        for (int k = 0; k < offset && k < s.length(); k++) if (s.charAt(k) == '\n') line++;
        return line;
    }

    private static Answer classifyBlock(int order, int line, String block, List<SExpr.FormatError> errors) {
        List<SExpr.FormatError> local = new ArrayList<>();
        List<SExpr> exprs = SExpr.Parser.parseAll(block, local);
        if (!local.isEmpty()) errors.addAll(local);
        if (exprs.size() == 1 && exprs.get(0) instanceof SExpr.List l) {
            if (l.items().size() >= 2 && "model".equals(l.headToken())) {
                return new ModelAnswer(order, line, block, SmtModel.parse(block, l));
            }
            // (define-fun ...) directly, or a bare list => treat as a one-item model
            if ("define-fun".equals(l.headToken()) || "define-const".equals(l.headToken())
                    || "declare-fun".equals(l.headToken()) || "forall".equals(l.headToken())) {
                return new ModelAnswer(order, line, block, SmtModel.parse(block, null));
            }
            // a core is a flat list of atoms: (p1 p2 ...)
            if (l.items().stream().allMatch(it -> it instanceof SExpr.Atom)) {
                List<String> names = new ArrayList<>();
                for (SExpr it : l.items()) names.add(SExpr.unquote(((SExpr.Atom) it).token()));
                return new CoreAnswer(order, line, block, names);
            }
        }
        // multiple top-level define-fun blocks
        if (!exprs.isEmpty() && exprs.stream().allMatch(e ->
                (e instanceof SExpr.List ll) && ("define-fun".equals(ll.headToken())
                        || "define-const".equals(ll.headToken()) || "declare-fun".equals(ll.headToken())))) {
            return new ModelAnswer(order, line, block, SmtModel.parse(block, null));
        }
        return new TextAnswer(order, line, block, block.trim());
    }

}
