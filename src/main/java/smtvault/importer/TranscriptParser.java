package smtvault.importer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import smtvault.domain.CmdRecord;
import smtvault.domain.Sexp;
import smtvault.domain.Transcript;

/**
 * Parses the offline transcript format (.smtses):
 *
 *   #meta key = value
 *   (set-logic QF_AX)
 *   (declare-const x Bool)
 *   (assert (! (= x false) :named p1))
 *   (check-sat)
 *   ;; => unsat
 *
 * Rules:
 *  - Every command keeps its exact source text and source line.
 *  - ";; =" response lines attach, in order, to the preceding command.
 *    Responses may be parenthesised (model / core) and span multiple
 *    ";; =" lines; balanced parens decide the boundary.
 *  - Plain ";" comments are preserved only inside command text.
 *  - Malformed commands are recorded with a diagnostic, never dropped.
 */
public final class TranscriptParser {

    public Transcript parse(String label, String rawText) {
        Transcript t = new Transcript();
        t.label = label;
        t.rawText = rawText;
        t.meta = new LinkedHashMap<>();
        t.diagnostics = new ArrayList<>();
        t.commands = new ArrayList<>();

        String[] lines = rawText.split("\n", -1);
        List<RawBlock> blocks = splitBlocks(lines, t.diagnostics);

        int seq = 0;
        for (RawBlock block : blocks) {
            if (block.text.startsWith("#meta")) {
                String rest = block.text.substring("#meta".length()).trim();
                int eq = rest.indexOf('=');
                if (eq > 0) {
                    t.meta.put(rest.substring(0, eq).trim(), rest.substring(eq + 1).trim());
                } else {
                    t.diagnostics.add("line " + block.startLine + ": malformed #meta directive");
                }
                continue;
            }
            CmdRecord rec = buildRecord(seq, block);
            if ("set-logic".equals(rec.commandName()) && rec.command.argc() >= 1) {
                t.logic = rec.command.arg(0).token();
            }
            t.commands.add(rec);
            seq++;
        }
        return t;
    }

    private static final class RawBlock {
        final int startLine;
        final String text;
        final List<String> responses = new ArrayList<>();

        RawBlock(int startLine, String text) {
            this.startLine = startLine;
            this.text = text;
        }
    }

    private List<RawBlock> splitBlocks(String[] lines, List<String> diagnostics) {
        List<RawBlock> blocks = new ArrayList<>();
        RawBlock current = null;
        int parenDepth = 0;
        StringBuilder pending = new StringBuilder();
        boolean inCommand = false;

        for (int idx = 0; idx < lines.length; idx++) {
            int lineNo = idx + 1;
            String line = lines[idx];
            String trimmed = stripComment(line).trim();

            if (!inCommand && line.trim().startsWith(";;")) {
                String payload = line.trim().substring(2).trim();
                if (payload.startsWith("=")) {
                    payload = payload.substring(1).trim();
                }
                if (current == null) {
                    diagnostics.add("line " + lineNo + ": response with no command");
                } else {
                    current.responses.add(payload);
                }
                continue;
            }
            if (!inCommand && (trimmed.isEmpty() || line.trim().startsWith("#meta"))) {
                if (line.trim().startsWith("#meta")) {
                    blocks.add(new RawBlock(lineNo, line.trim()));
                }
                continue;
            }

            if (!inCommand) {
                current = new RawBlock(lineNo, "");
                blocks.add(current);
                pending.setLength(0);
                inCommand = true;
            }
            if (pending.length() > 0) {
                pending.append('\n');
            }
            pending.append(line);
            parenDepth += countParens(stripComment(line));
            if (parenDepth <= 0) {
                inCommand = false;
                parenDepth = 0;
                current.text = pending.toString();
                current = null;
            }
        }
        if (inCommand) {
            current.text = pending.toString();
            diagnostics.add("line " + blocks.get(blocks.size() - 1).startLine
                    + ": unbalanced parentheses in command");
        }
        return blocks;
    }

    private CmdRecord buildRecord(int seq, RawBlock block) {
        String raw = block.text;
        Sexp command = null;
        String parseError = null;
        try {
            command = Sexp.parse(raw);
        } catch (Sexp.ParseFailure failure) {
            parseError = failure.getMessage();
        } catch (RuntimeException re) {
            if (re.getCause() instanceof Sexp.ParseFailure pf) {
                parseError = pf.getMessage();
            } else {
                parseError = String.valueOf(re.getMessage());
            }
        }
        String responseRaw = String.join("\n", block.responses);
        Sexp response = null;
        String responseError = null;
        if (!responseRaw.isBlank()) {
            try {
                String joined = String.join(" ", block.responses).trim();
                if (joined.startsWith("(")) {
                    response = Sexp.parse(joined);
                } else {
                    String firstToken = joined.split("\\s+", 2)[0];
                    response = Sexp.atom(firstToken, block.startLine);
                    if (joined.length() > firstToken.length()) {
                        // trailing text retained raw, ignored structurally
                    }
                }
            } catch (Sexp.ParseFailure failure) {
                responseError = failure.getMessage();
            }
        }
        return new CmdRecord(seq, block.startLine, raw.strip(), command, parseError,
                responseRaw.strip(), response, responseError);
    }

    private static String stripComment(String line) {
        boolean inQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '|') {
                inQuote = !inQuote;
            } else if (c == ';' && !inQuote) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static int countParens(String text) {
        int depth = 0;
        boolean inQuote = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '|') {
                inQuote = !inQuote;
            } else if (!inQuote) {
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                }
            }
        }
        return depth;
    }

    public static Map<String, String> metaOf(Transcript t) {
        return t.meta == null ? Map.of() : t.meta;
    }
}
