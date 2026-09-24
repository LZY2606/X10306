package smtwitness.core;

import java.util.ArrayList;
import java.util.List;

/** Parses a command/response transcript into a Session. Responses are `(recv <sexp-or-atom>)`. */
public final class SmtParser {

    public record ParseIssue(String code, String message, int ordinal) {}
    public record ParseResult(SmtModel.Session session, List<ParseIssue> issues) {
        public boolean ok() { return issues.stream().noneMatch(i -> i.code().startsWith("FATAL")); }
    }

    public ParseResult parseTranscript(String sourceId, String text) {
        List<ParseIssue> issues = new ArrayList<>();
        List<SExpr> tops;
        try {
            tops = SExpr.parseAll(text);
        } catch (SExpr.ParseException e) {
            issues.add(new ParseIssue("FATAL_SYNTAX", e.getMessage(), -1));
            return new ParseResult(new SmtModel.Session(null, java.util.Map.of(), List.of(), List.of()), issues);
        }
        String logic = null;
        java.util.Map<String, String> info = new java.util.LinkedHashMap<>();
        List<SmtModel.Command> commands = new ArrayList<>();
        List<SmtModel.SolverResponse> responses = new ArrayList<>();
        int ordinal = 0;
        for (SExpr top : tops) {
            ordinal++;
            if (!top.isList() || top.items().isEmpty()) {
                issues.add(new ParseIssue("FATAL_TOPLEVEL", "top-level form must be (send ...) or (recv ...), got atom " + top, ordinal));
                continue;
            }
            String head = top.head();
            if (head.equals("send")) {
                if (top.items().size() != 2 || !top.items().get(1).isList()) {
                    issues.add(new ParseIssue("FATAL_SEND", "send form must contain exactly one command s-expression", ordinal));
                    continue;
                }
                SExpr cmd = top.items().get(1);
                String cmdHead = cmd.head();
                String id = sourceId + "/cmd/" + ordinal;
                SmtModel.Command c = new SmtModel.Command(
                        id, ordinal, cmdHead, render(cmd), cmd,
                        extractName(cmd), extractLabel(cmd), extractTerm(cmd), extractAssumptions(cmd));
                commands.add(c);
                switch (cmdHead) {
                    case "set-logic" -> {
                        if (cmd.items().size() == 2) logic = cmd.items().get(1).atomText();
                        else issues.add(new ParseIssue("FATAL_ARITY", "set-logic expects one argument", ordinal));
                    }
                    case "set-info" -> {
                        if (cmd.items().size() == 3) info.put(cmd.items().get(1).atomText(), render(cmd.items().get(2)));
                    }
                    default -> { /* recorded verbatim */ }
                }
            } else if (head.equals("recv")) {
                if (top.items().size() != 2) {
                    issues.add(new ParseIssue("FATAL_RECV", "recv form must contain exactly one response", ordinal));
                    continue;
                }
                SExpr payload = top.items().get(1);
                String kind = payload.isAtom() ? payload.atomText() : payload.head();
                responses.add(new SmtModel.SolverResponse(sourceId + "/resp/" + ordinal, ordinal, kind, render(payload), payload));
            } else {
                issues.add(new ParseIssue("FATAL_TOPLEVEL", "unknown top-level wrapper '" + head + "'; expected send or recv", ordinal));
            }
        }
        if (logic == null) issues.add(new ParseIssue("WARN_NO_LOGIC", "no set-logic command; logic recorded as undeclared", -1));
        return new ParseResult(new SmtModel.Session(logic, info, commands, responses), issues);
    }

    /** Parse a bare SMT-LIB command script (no send/recv wrappers) into commands only. */
    public ParseResult parseScript(String sourceId, String text) {
        StringBuilder sb = new StringBuilder();
        try {
            List<SExpr> tops = SExpr.parseAll(text);
            for (SExpr t : tops) sb.append("(send ").append(render(t)).append(")\n");
        } catch (SExpr.ParseException e) {
            List<ParseIssue> issues = new ArrayList<>();
            issues.add(new ParseIssue("FATAL_SYNTAX", e.getMessage(), -1));
            return new ParseResult(new SmtModel.Session(null, java.util.Map.of(), List.of(), List.of()), issues);
        }
        return parseTranscript(sourceId, sb.toString());
    }

    private static String extractName(SExpr cmd) {
        String h = cmd.head();
        if ((h.equals("declare-const") || h.equals("declare-fun") || h.equals("define-fun")) && cmd.items().size() >= 2)
            return cmd.items().get(1).atomText();
        return null;
    }

    private static String extractLabel(SExpr cmd) {
        if (!cmd.head().equals("assert")) return null;
        List<SExpr> items = cmd.items();
        for (int i = 1; i + 1 < items.size(); i++) {
            SExpr it = items.get(i);
            if (it.isAtom() && it.atomText().equals(":named")) return items.get(i + 1).atomText();
        }
        return null;
    }

    private static SExpr extractTerm(SExpr cmd) {
        if (!cmd.head().equals("assert") || cmd.items().size() < 2) return null;
        return cmd.items().get(1);
    }

    private static List<SExpr> extractAssumptions(SExpr cmd) {
        if (!cmd.head().equals("check-sat-assuming") || cmd.items().size() < 2) return List.of();
        SExpr l = cmd.items().get(1);
        if (!l.isList()) return List.of();
        return new ArrayList<>(l.items());
    }

    public static String render(SExpr e) {
        if (e instanceof SExpr.Atom a) return a.text();
        StringBuilder sb = new StringBuilder("(");
        boolean first = true;
        for (SExpr item : e.items()) {
            if (!first) sb.append(' ');
            sb.append(render(item));
            first = false;
        }
        return sb.append(')').toString();
    }
}
