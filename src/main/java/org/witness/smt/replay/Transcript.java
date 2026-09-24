package org.witness.smt.replay;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.witness.smt.SExpr;

/**
 * Parser for the copy-paste transcript format (filename suffix {@code .smt2t}).
 *
 * <pre>
 * (set-logic QF_LIA)            ; a command line starts with '('
 * < success                     ; command status: success or (error "...")
 * (check-sat-assuming (a1))     ; check-sat command
 * $ unsat                       ; check-sat result: sat | unsat | unknown
 * (get-model)
 * @ ( (define-fun x () Int 3) ) ; model payload
 * (get-unsat-core)
 * & (a1 a2)                     ; named-literal payload
 * %unit x m                     ; domain annotation: symbol carries unit "m"
 * </pre>
 *
 * Original text is retained verbatim; nothing is normalised at import time.
 */
public final class Transcript {

    public enum EntryType { CMD, STATUS, RESULT, ANNOT }

    public enum ResultKind {
        SUCCESS, ERROR, SAT, UNSAT, UNKNOWN, MODEL, NAMED_LIST, GENERIC
    }

    public static final Set<String> STATUS_COMMANDS = Set.of(
            "set-logic", "set-option", "set-info", "declare-sort", "declare-const",
            "declare-fun", "define-fun", "define-sort", "assert", "push", "pop",
            "reset", "reset-assertions", "echo");
    public static final Set<String> CHECK_COMMANDS = Set.of(
            "check-sat", "check-sat-assuming");
    public static final Set<String> CORE_COMMANDS = Set.of(
            "get-unsat-core", "get-unsat-assumptions");

    public static final class Entry {
        public final int index;
        public final EntryType type;
        public final String raw;
        public final int sourceLine;
        public SExpr command;
        public String commandName;
        public ResultKind resultKind;
        public SExpr payload;
        public int pairedCommand = -1;
        public String annotKey;
        public String annotSymbol;
        public String annotValue;

        Entry(int index, EntryType type, String raw, int sourceLine) {
            this.index = index;
            this.type = type;
            this.raw = raw;
            this.sourceLine = sourceLine;
        }

        public boolean isError() {
            return resultKind == ResultKind.ERROR;
        }
    }

    public static final class ParseException extends RuntimeException {
        public final int line;

        public ParseException(String message, int line) {
            super(message + " (line " + line + ")");
            this.line = line;
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    public final String raw;

    private Transcript(String raw) {
        this.raw = raw;
    }

    public List<Entry> entries() {
        return entries;
    }

    public static Transcript parse(String raw) {
        Transcript t = new Transcript(raw);
        String[] lines = raw.split("\n", -1);
        int logical = 0;
        for (int i = 0; i < lines.length; i++) {
            int lineNo = i + 1;
            String line = lines[i];
            String trimmed = stripComment(line).trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            char marker = trimmed.charAt(0);
            switch (marker) {
                case '(' -> {
                    SExpr form;
                    try {
                        form = SExpr.parseOne(trimmed);
                    } catch (SExpr.FormatError e) {
                        throw new ParseException("malformed S-expression: " + e.getMessage(), lineNo);
                    }
                    if (!form.isList() || form.head() == null) {
                        throw new ParseException("expected an SMT-LIB command of the form (<command> ...)", lineNo);
                    }
                    Entry e = new Entry(logical++, EntryType.CMD, trimmed, lineNo);
                    e.command = form;
                    e.commandName = form.head();
                    t.entries.add(e);
                }
                case '<' -> i = parseStatus(t, lines, i);
                case '$' -> parseCheckResult(t, trimmed, lineNo);
                case '@' -> i = parseModel(t, lines, i);
                case '&' -> i = parseNamedList(t, lines, i);
                case '%' -> parseAnnot(t, trimmed, lineNo);
                default -> throw new ParseException(
                        "unrecognised line: commands start with '(', responses with '<', '$', '@', '&', annotations with '%'",
                        lineNo);
            }
        }
        pairResponses(t);
        return t;
    }

    private static String stripComment(String line) {
        boolean inString = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inString = !inString;
            } else if (c == ';' && !inString) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    /**
     * Collects a response body that may span multiple lines. Continuation
     * lines are indented or start with {@code (}/{@code )}; gathering stops
     * when parens balance (for list bodies) or at the next response marker.
     */
    /**
     * Collects a response body that may span multiple lines. Returns the
     * index of the next line after the consumed block (or {@code i} when the
     * body is a single, non-list token). Continuation is decided purely by
     * parenthesis depth so indented '(' lines belong to the current body.
     */
    private static int gatherBody(String[] lines, int i, char marker) {
        String first = stripComment(lines[i]).trim();
        String body = first.substring(1).trim();
        if (!body.startsWith("(")) {
            return i;
        }
        int depth = parenDepth(body);
        int j = i;
        while (depth > 0 && j + 1 < lines.length) {
            String rawTrim = lines[j + 1].trim();
            String nxt = stripComment(lines[j + 1]).trim();
            j++;
            if (nxt.isEmpty()) {
                continue;
            }
            char nm = rawTrim.charAt(0);
            if (nm == '<' || nm == '$' || nm == '@' || nm == '&' || nm == '%') {
                // Next response reached while unbalanced: malformed input.
                throw new ParseException("unterminated " + marker + " response body; "
                        + "parenthesis depth " + depth + " before line " + (j + 1), j + 1);
            }
            body += "\n" + nxt;
            depth += parenDepth(nxt);
        }
        if (depth != 0) {
            throw new ParseException("unterminated " + marker + " response body", i + 1);
        }
        lines[i] = marker + " " + body;
        return j; // index of last consumed line; caller adds 1 via the for loop
    }

    private static int parenDepth(String s) {
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                inString = !inString;
            } else if (!inString) {
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                }
            }
        }
        return depth;
    }

    private static int parseStatus(Transcript t, String[] lines, int i) {
        int lineNo = i + 1;
        int after = gatherBody(lines, i, '<');
        String trimmed = lines[i].trim();
        String body = requireBody(trimmed, lineNo);
        Entry e = new Entry(t.entries.size(), EntryType.STATUS, trimmed, lineNo);
        if (body.equals("success")) {
            e.resultKind = ResultKind.SUCCESS;
        } else if (body.startsWith("(")) {
            SExpr form;
            try {
                form = SExpr.parseOne(body);
            } catch (SExpr.FormatError ex) {
                throw new ParseException("malformed response: " + ex.getMessage(), lineNo);
            }
            if (form.isList() && "error".equals(form.head()) && form.arity() == 1
                    && form.arg(0).isAtom() && form.arg(0).atom().startsWith("\"")) {
                e.resultKind = ResultKind.ERROR;
                e.payload = form;
            } else {
                e.resultKind = ResultKind.GENERIC;
                e.payload = form;
            }
        } else {
            throw new ParseException("status response must be 'success' or '(error \"...\")', got: " + body, lineNo);
        }
        t.entries.add(e);
        return after;
    }

    private static void parseCheckResult(Transcript t, String trimmed, int lineNo) {
        String body = requireBody(trimmed, lineNo);
        Entry e = new Entry(t.entries.size(), EntryType.RESULT, trimmed, lineNo);
        e.resultKind = switch (body) {
            case "sat" -> ResultKind.SAT;
            case "unsat" -> ResultKind.UNSAT;
            case "unknown" -> ResultKind.UNKNOWN;
            default -> throw new ParseException("check-sat result must be sat, unsat or unknown, got: " + body, lineNo);
        };
        t.entries.add(e);
    }

    private static int parseModel(Transcript t, String[] lines, int i) {
        int lineNo = i + 1;
        int after = gatherBody(lines, i, '@');
        String trimmed = lines[i].trim();
        String body = requireBody(trimmed, lineNo);
        SExpr form;
        try {
            form = SExpr.parseOne(body);
        } catch (SExpr.FormatError ex) {
            throw new ParseException("malformed model payload: " + ex.getMessage(), lineNo);
        }
        if (!form.isList()) {
            throw new ParseException("model payload must be a list of define-fun / define-const entries", lineNo);
        }
        Entry e = new Entry(t.entries.size(), EntryType.RESULT, trimmed, lineNo);
        e.resultKind = ResultKind.MODEL;
        e.payload = form;
        t.entries.add(e);
        return after;
    }

    private static int parseNamedList(Transcript t, String[] lines, int i) {
        int lineNo = i + 1;
        int after = gatherBody(lines, i, '&');
        String trimmed = lines[i].trim();
        String body = requireBody(trimmed, lineNo);
        SExpr form;
        try {
            form = SExpr.parseOne(body);
        } catch (SExpr.FormatError ex) {
            throw new ParseException("malformed named-literal payload: " + ex.getMessage(), lineNo);
        }
        if (!form.isList()) {
            throw new ParseException("unsat core / assumptions payload must be a list of named-literal symbols", lineNo);
        }
        for (SExpr sx : form.items()) {
            if (!sx.isAtom()) {
                throw new ParseException("payload list must contain named-literal symbols only", lineNo);
            }
        }
        Entry e = new Entry(t.entries.size(), EntryType.RESULT, trimmed, lineNo);
        e.resultKind = ResultKind.NAMED_LIST;
        e.payload = form;
        t.entries.add(e);
        return after;
    }

    private static void parseAnnot(Transcript t, String trimmed, int lineNo) {
        String body = requireBody(trimmed, lineNo);
        String[] parts = body.split("\\s+", 3);
        if (parts.length < 3) {
            throw new ParseException("annotation must look like '%unit x m' (key symbol value)", lineNo);
        }
        Entry e = new Entry(t.entries.size(), EntryType.ANNOT, trimmed, lineNo);
        e.annotKey = parts[0];
        e.annotSymbol = parts[1];
        e.annotValue = parts[2];
        t.entries.add(e);
    }

    private static String requireBody(String trimmed, int lineNo) {
        String body = trimmed.substring(1).trim();
        if (body.isEmpty()) {
            throw new ParseException("response marker '" + trimmed.charAt(0) + "' requires a body", lineNo);
        }
        return body;
    }

    private static void pairResponses(Transcript t) {
        for (Entry e : t.entries) {
            if (e.type != EntryType.STATUS && e.type != EntryType.RESULT) {
                continue;
            }
            Entry cmd = lastCommandBefore(t, e.index);
            if (cmd == null) {
                throw new ParseException("response without a preceding command", e.sourceLine);
            }
            if (cmd.pairedCommand != -1) {
                throw new ParseException(
                        "command '" + cmd.commandName + "' on line " + cmd.sourceLine + " already has a response",
                        e.sourceLine);
            }
            cmd.pairedCommand = e.index;
            e.pairedCommand = cmd.index;
            validatePairing(cmd, e);
        }
        for (Entry e : t.entries) {
            if (e.type == EntryType.CMD && e.pairedCommand == -1) {
                throw new ParseException("command '" + e.commandName + "' is missing its solver response", e.sourceLine);
            }
        }
    }

    private static Entry lastCommandBefore(Transcript t, int index) {
        for (int i = index - 1; i >= 0; i--) {
            Entry e = t.entries.get(i);
            if (e.type == EntryType.CMD) {
                return e;
            }
        }
        return null;
    }

    private static void validatePairing(Entry cmd, Entry resp) {
        String name = cmd.commandName;
        int line = resp.sourceLine;
        if (CHECK_COMMANDS.contains(name)) {
            if (resp.type != EntryType.RESULT
                    || (resp.resultKind != ResultKind.SAT
                        && resp.resultKind != ResultKind.UNSAT
                        && resp.resultKind != ResultKind.UNKNOWN)) {
                throw new ParseException("'" + name + "' requires a '$ sat|unsat|unknown' result", line);
            }
        } else if ("get-model".equals(name)) {
            if (resp.type != EntryType.RESULT || resp.resultKind != ResultKind.MODEL) {
                throw new ParseException("'get-model' requires a '@ (...)' model payload", line);
            }
        } else if (CORE_COMMANDS.contains(name)) {
            if (resp.type != EntryType.RESULT || resp.resultKind != ResultKind.NAMED_LIST) {
                throw new ParseException("'" + name + "' requires a '& (...)' named-literal payload", line);
            }
        } else {
            if (resp.type != EntryType.STATUS) {
                throw new ParseException("'" + name + "' requires a '< success' or '< (error ...)' status", line);
            }
        }
    }
}
