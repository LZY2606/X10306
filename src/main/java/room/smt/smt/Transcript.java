package room.smt.smt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses an imported SMT-LIB session transcript.
 * Two accepted shapes:
 *  - text: interleaved SMT-LIB commands and solver responses, where each response
 *    line starts with ";>" (everything after the marker up to end of line).
 *  - JSON envelope: {"format":"smt-testimony/v1", "meta":{...},
 *      "commands":[{"text":"(assert ...)","responses":["sat"]}], "quantities":[...]}
 * Everything (command order, push/pop, named assertions, check-sat-assuming,
 * model definitions, response order, logic declaration) is preserved verbatim.
 */
public final class Transcript {
    public static final String FORMAT = "smt-testimony/v1";

    public static final class Cmd {
        public int index;                 // 0-based order in the session
        public String text;               // verbatim command text
        public List<String> responses = new ArrayList<>(); // verbatim solver responses, in order
        public String id() { return "cmd-" + index; }
    }

    public String format;                 // "text" or FORMAT
    public String logic;                  // declared logic (from set-logic or envelope meta)
    public final List<Cmd> commands = new ArrayList<>();
    public final Map<String, String> meta = new LinkedHashMap<>();
    public final List<JsonNode> quantities = new ArrayList<>(); // domain quantity metadata (raw)
    public final List<String> diagnostics = new ArrayList<>();

    private static final ObjectMapper M = new ObjectMapper();

    public static Transcript parse(String input) {
        String trimmed = input == null ? "" : input.strip();
        if (trimmed.isEmpty()) throw new TranscriptException("empty input: nothing to import");
        if (trimmed.startsWith("{")) return parseEnvelope(trimmed);
        return parseText(input);
    }

    private static Transcript parseEnvelope(String json) {
        try {
            JsonNode root = M.readTree(json);
            String fmt = root.path("format").asText("");
            if (!FORMAT.equals(fmt))
                throw new TranscriptException("unsupported envelope format '" + fmt + "' (expected " + FORMAT + ")");
            Transcript t = new Transcript();
            t.format = FORMAT;
            JsonNode meta = root.path("meta");
            meta.fields().forEachRemaining(e -> t.meta.put(e.getKey(), e.getValue().asText()));
            t.logic = t.meta.get("logic");
            JsonNode cmds = root.path("commands");
            if (!cmds.isArray()) throw new TranscriptException("envelope 'commands' must be an array");
            int i = 0;
            for (JsonNode c : cmds) {
                Cmd cmd = new Cmd();
                cmd.index = i++;
                cmd.text = c.path("text").asText(null);
                if (cmd.text == null) throw new TranscriptException("command #" + cmd.index + " missing 'text'");
                for (JsonNode r : c.path("responses")) cmd.responses.add(r.asText());
                t.commands.add(cmd);
            }
            for (JsonNode q : root.path("quantities")) t.quantities.add(q);
            return t;
        } catch (TranscriptException e) {
            throw e;
        } catch (Exception e) {
            throw new TranscriptException("malformed JSON envelope: " + e.getMessage());
        }
    }

    private static Transcript parseText(String input) {
        Transcript t = new Transcript();
        t.format = "text";
        StringBuilder cmdBuf = new StringBuilder();
        int depth = 0;
        boolean inString = false, inQuoted = false;
        List<String> pendingResponses = new ArrayList<>();
        String[] lines = input.split("\r?\n", -1);
        for (String line : lines) {
            String stripped = line.strip();
            if (stripped.startsWith(";>")) {
                String resp = stripped.substring(2).strip();
                if (cmdBuf.length() == 0 && t.commands.isEmpty())
                    throw new TranscriptException("solver response before any command: " + resp);
                pendingResponses.add(resp);
                continue;
            }
            if (stripped.startsWith(";") || stripped.isEmpty()) {
                // plain comment / blank line: attach to whatever command is being accumulated
                if (cmdBuf.length() > 0) cmdBuf.append('\n').append(line);
                continue;
            }
            // accumulate command chars, tracking parens (respect strings/quoted symbols)
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                cmdBuf.append(c);
                if (inString) { if (c == '"') inString = false; continue; }
                if (inQuoted) { if (c == '|') inQuoted = false; continue; }
                switch (c) {
                    case '"' -> inString = true;
                    case '|' -> inQuoted = true;
                    case ';' -> { /* rest of line is comment */ i = line.length(); }
                    case '(' -> depth++;
                    case ')' -> depth--;
                }
                if (depth < 0) throw new TranscriptException("unbalanced ')' in command #" + t.commands.size());
            }
            cmdBuf.append('\n');
            if (depth == 0 && cmdBuf.toString().strip().length() > 0) {
                Cmd cmd = new Cmd();
                cmd.index = t.commands.size();
                cmd.text = cmdBuf.toString().strip();
                cmd.responses.addAll(pendingResponses);
                pendingResponses.clear();
                t.commands.add(cmd);
                cmdBuf.setLength(0);
            }
        }
        if (depth != 0) throw new TranscriptException("unbalanced parentheses: depth " + depth + " at end of input");
        if (cmdBuf.toString().strip().length() > 0)
            throw new TranscriptException("trailing incomplete command: " + cmdBuf.toString().strip());
        if (!pendingResponses.isEmpty())
            throw new TranscriptException("solver responses after last command: " + pendingResponses);
        // logic from set-logic command if present
        for (Cmd c : t.commands) {
            try {
                SExpr e = SExpr.parse(c.text);
                if (e.isList() && "set-logic".equals(e.head()) && e.size() >= 2) {
                    String lg = e.get(1).text;
                    if (t.logic == null) t.logic = lg;
                    else if (!t.logic.equals(lg))
                        t.diagnostics.add("conflicting set-logic: '" + lg + "' after '" + t.logic + "' (first kept)");
                }
            } catch (SExpr.ParseException pe) {
                t.diagnostics.add("command #" + c.index + " does not parse: " + pe.getMessage());
            }
        }
        return t;
    }

    public static final class TranscriptException extends RuntimeException {
        public TranscriptException(String msg) { super(msg); }
    }
}
