package chamber.smt;

import java.util.ArrayList;
import java.util.List;

/** An imported SMT-LIB session: the original command stream and the solver responses,
 *  in their original interleaved order. Nothing is reordered or dropped.
 *
 *  File convention: solver responses are lines starting with ";<< ".
 *  Continuation lines of a multi-line response start with ";< ".
 *  Everything else is SMT-LIB command text.
 */
public record SessionScript(List<Entry> entries) {

    public enum EntryKind { COMMAND, RESPONSE }

    public record Entry(int index, EntryKind kind, String raw, Command command) {
        public boolean isCommand() { return kind == EntryKind.COMMAND; }
        public boolean isResponse() { return kind == EntryKind.RESPONSE; }
    }

    public List<Entry> commands() {
        return entries.stream().filter(Entry::isCommand).toList();
    }

    /** The response that directly follows the command entry at position {@code entryIndex}, if any. */
    public Entry responseAfter(int entryIndex) {
        if (entryIndex + 1 < entries.size() && entries.get(entryIndex + 1).isResponse()) {
            return entries.get(entryIndex + 1);
        }
        return null;
    }

    public static final class ParseFailure extends Exception {
        public final List<String> diagnostics;
        public ParseFailure(List<String> diagnostics) {
            super(String.join("; ", diagnostics));
            this.diagnostics = diagnostics;
        }
    }

    public static SessionScript parse(String text) throws ParseFailure {
        List<Entry> entries = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        StringBuilder cmdBuf = new StringBuilder();
        int cmdStartLine = 1;
        int balance = 0;
        boolean inQuote = false;
        String[] lines = text.split("\n", -1);
        StringBuilder respBuf = null;

        Runnable flushResponse = () -> {};
        for (int li = 0; li < lines.length; li++) {
            String line = lines[li];
            String trimmed = line.strip();
            if (trimmed.startsWith(";<<")) {
                if (respBuf != null) {
                    entries.add(responseEntry(entries.size(), respBuf.toString()));
                }
                respBuf = new StringBuilder(trimmed.substring(3).strip());
                continue;
            }
            if (respBuf != null && trimmed.startsWith(";<")) {
                respBuf.append('\n').append(trimmed.substring(2).strip());
                continue;
            }
            if (respBuf != null) {
                entries.add(responseEntry(entries.size(), respBuf.toString()));
                respBuf = null;
            }
            // accumulate command text, tracking paren balance outside comments/quotes
            boolean lineHasCode = false;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == ';' && !inQuote) break; // trailing comment
                if (c == '|') inQuote = !inQuote;
                if (c == '(' && !inQuote) balance++;
                if (c == ')' && !inQuote) balance--;
                if (!Character.isWhitespace(c)) lineHasCode = true;
                cmdBuf.append(c);
                if (balance < 0) {
                    diagnostics.add("line " + (li + 1) + ": unexpected ')'");
                    balance = 0;
                }
            }
            if (lineHasCode && cmdBuf.toString().isBlank()) cmdStartLine = li + 1;
            cmdBuf.append('\n');
            if (balance == 0 && !cmdBuf.toString().isBlank()) {
                String chunk = cmdBuf.toString();
                cmdBuf.setLength(0);
                try {
                    for (SExpr e : SExpr.parseAll(chunk)) {
                        Command cmd = Command.parse(e);
                        entries.add(new Entry(entries.size(), EntryKind.COMMAND, e.toString(), cmd));
                        if (cmd.diagnostic() != null) {
                            diagnostics.add("entry " + (entries.size() - 1) + ": " + cmd.diagnostic());
                        }
                    }
                } catch (SExpr.ParseException pe) {
                    diagnostics.add("near line " + (li + 1) + ": " + pe.getMessage());
                }
            }
        }
        if (respBuf != null) entries.add(responseEntry(entries.size(), respBuf.toString()));
        if (balance != 0) diagnostics.add("unbalanced parentheses at end of input");
        if (!cmdBuf.toString().isBlank()) {
            diagnostics.add("trailing incomplete command: " + cmdBuf.toString().strip());
        }
        // Hard failures: malformed s-expressions. Soft diagnostics (unsupported theory etc.)
        // are attached to entries and do not reject the import.
        boolean hard = diagnostics.stream().anyMatch(d ->
                d.contains("unbalanced") || d.contains("unexpected") || d.contains("incomplete")
                        || d.contains("unterminated"));
        if (hard) throw new ParseFailure(diagnostics);
        return new SessionScript(List.copyOf(entries));
    }

    private static Entry responseEntry(int index, String raw) {
        return new Entry(index, EntryKind.RESPONSE, raw, null);
    }
}
