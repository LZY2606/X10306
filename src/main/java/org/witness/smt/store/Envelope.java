package org.witness.smt.store;

import org.witness.smt.json.Json;
import org.witness.smt.parse.SExpr;
import org.witness.smt.parse.SmtParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The portable, copy-pasteable case format.  Every item keeps its exact raw text;
 * commands and responses appear in their original interleaved order.  Measured
 * domain quantities carry a unit / coordinate-basis descriptor, but the raw
 * representation is never rewritten on import.
 *
 * <pre>
 * { "format": "smt-witness-envelope/v1",
 *   "sessionId": "...", "title": "...", "logic": "WITNESS_ROOM",
 *   "items": [ {"kind":"command"|"response", "raw":"..."}, ... ],
 *   "measures": {"x": {"unit":"mm", "basis":"frame-A", "rawText":"12 mm"}},
 *   "params": {"maxCandidates": 8, "maxFmCombinations": 64},
 *   "notes": "..." }
 * </pre>
 */
public final class Envelope {
    public record Item(String kind, String raw) { }
    public record Measure(String unit, String basis, String rawText, String diagnostic) { }

    public String format;
    public String sessionId;
    public String title;
    public String logic;
    public final List<Item> items = new ArrayList<>();
    public final Map<String, Measure> measures = new LinkedHashMap<>();
    public final Json.Obj params = new Json.Obj();
    public String notes;

    public static final class EnvelopeError extends RuntimeException {
        public final String code;
        public EnvelopeError(String code, String message) { super(message); this.code = code; }
    }

    public static Envelope fromJson(String text) {
        Json.Obj o;
        try {
            o = Json.asObj(Json.parse(text));
        } catch (RuntimeException e) {
            throw new EnvelopeError("MALFORMED_JSON", "envelope is not valid JSON: " + e.getMessage());
        }
        String fmt = o.getStr("format");
        if (fmt == null) throw new EnvelopeError("MISSING_FORMAT", "field 'format' is required");
        if (!fmt.equals("smt-witness-envelope/v1"))
            throw new EnvelopeError("UNSUPPORTED_FORMAT", "unknown format '" + fmt + "'");

        Envelope e = new Envelope();
        e.format = fmt;
        e.sessionId = o.getStr("sessionId");
        e.title = o.getStr("title");
        e.logic = o.getStr("logic");
        e.notes = o.getStr("notes");
        if (o.has("params")) e.params.putAll(o.getObj("params"));

        List<Object> items = o.getArr("items");
        if (items.isEmpty()) throw new EnvelopeError("EMPTY_SESSION", "items[] must not be empty");
        for (int i = 0; i < items.size(); i++) {
            Json.Obj it = Json.asObj(items.get(i));
            String kind = it.getStr("kind");
            String raw = it.getStr("raw");
            if (kind == null || raw == null)
                throw new EnvelopeError("BAD_ITEM", "item " + i + " needs 'kind' and 'raw'");
            if (!kind.equals("command") && !kind.equals("response"))
                throw new EnvelopeError("BAD_ITEM_KIND", "item " + i + " has illegal kind '" + kind + "'");
            e.items.add(new Item(kind, raw));
        }
        Json.Obj measures = o.getObj("measures");
        for (String key : measures.keySet()) {
            Json.Obj m = Json.asObj(measures.get(key));
            e.measures.put(key, new Measure(m.getStr("unit"), m.getStr("basis"),
                    m.getStr("rawText"), m.getStr("diagnostic")));
        }
        e.validateCommands();
        return e;
    }

    /** Parse every command item once, failing fast with byte offsets on syntax errors. */
    private void validateCommands() {
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            if (!it.kind.equals("command")) continue;
            try {
                SmtParser.parseAll(it.raw());
            } catch (SmtParser.ParseFailure f) {
                throw new EnvelopeError("MALFORMED_SMT",
                        "item " + i + ": " + f.getMessage());
            }
        }
    }

    public String toJson(boolean pretty) {
        Json.Obj o = Json.obj(
                "format", format,
                "sessionId", sessionId == null ? "" : sessionId,
                "title", title == null ? "" : title,
                "logic", logic == null ? "" : logic,
                "items", itemsAsJson(),
                "measures", measuresAsJson(),
                "params", params,
                "notes", notes == null ? "" : notes);
        return Json.write(o, pretty);
    }

    private List<Object> itemsAsJson() {
        List<Object> a = new ArrayList<>();
        for (Item it : items) a.add(Json.obj("kind", it.kind, "raw", it.raw));
        return a;
    }

    private Json.Obj measuresAsJson() {
        Json.Obj m = new Json.Obj();
        for (Map.Entry<String, Measure> en : measures.entrySet()) {
            Measure x = en.getValue();
            m.put(en.getKey(), Json.obj(
                    "unit", x.unit == null ? "" : x.unit,
                    "basis", x.basis == null ? "" : x.basis,
                    "rawText", x.rawText == null ? "" : x.rawText,
                    "diagnostic", x.diagnostic == null ? "" : x.diagnostic));
        }
        return m;
    }

    // ------------------------------------------------------------------
    // Plain-text convenience import: command lines / `(...)` expressions,
    // solver responses introduced with a leading `>>` marker.
    // ------------------------------------------------------------------

    public static Envelope fromPlainText(String text) {
        if (text == null || text.isBlank())
            throw new EnvelopeError("EMPTY_SESSION", "input is empty");
        Envelope e = new Envelope();
        e.format = "smt-witness-envelope/v1";
        String[] lines = text.split("\n", -1);
        StringBuilder cmd = new StringBuilder();
        int depth = 0;
        int startLine = -1;
        for (int li = 0; li < lines.length; li++) {
            String rawLine = lines[li];
            String line = stripComment(rawLine).trim();
            if (line.isEmpty() && depth == 0) continue;
            if (depth == 0 && line.startsWith(">>")) {
                e.items.add(new Item("response", line.substring(2).trim()));
                continue;
            }
            if (depth == 0) startLine = li + 1;
            cmd.append(rawLine).append('\n');
            depth += countUnquoted(line, '(') - countUnquoted(line, ')');
            if (depth < 0)
                throw new EnvelopeError("MALFORMED_SMT", "unbalanced ')' near line " + (li + 1));
            if (depth == 0) {
                String body = cmd.toString().trim();
                try {
                    SmtParser.parseAll(body);
                } catch (SmtParser.ParseFailure f) {
                    throw new EnvelopeError("MALFORMED_SMT",
                            "command starting at line " + startLine + ": " + f.getMessage());
                }
                e.items.add(new Item("command", body));
                cmd.setLength(0);
            }
        }
        if (depth != 0)
            throw new EnvelopeError("MALFORMED_SMT", "unterminated expression from line " + startLine);
        if (e.items.isEmpty()) throw new EnvelopeError("EMPTY_SESSION", "no commands found");
        return e;
    }

    private static String stripComment(String line) {
        boolean inStr = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') inStr = !inStr;
            if (!inStr && c == ';') return line.substring(0, i);
        }
        return line;
    }

    private static int countUnquoted(String s, char target) {
        int n = 0;
        boolean inStr = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') inStr = !inStr;
            if (!inStr && c == target) n++;
        }
        return n;
    }
}
