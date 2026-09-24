package smtroom.core;

import smtroom.smt.Script;
import smtroom.smt.Transcript;
import java.util.List;

/**
 * Import document: a copy-pasteable small sample with explicit sections.
 * The raw document is stored verbatim and never overwritten; revisions are
 * new versions linked to a parent.
 *
 * === script ===     (required) SMT-LIB command stream
 * === responses ===  (optional) solver responses, aligned by order or #cmd-index
 * === quantities === (optional) name = value unit lines
 */
public record ImportDoc(String raw, Script script, Transcript transcript,
                        List<Quantities.Quantity> quantities, List<String> formatErrors) {

    public static ImportDoc parse(String raw) {
        String scriptSec = section(raw, "script");
        String respSec = section(raw, "responses");
        String qtySec = section(raw, "quantities");
        List<String> errors = new ArrayList2<>();
        if (scriptSec == null) {
            errors.add("missing === script === section");
            return new ImportDoc(raw, null, null, List.of(), errors);
        }
        Script script;
        try {
            script = Script.parse(scriptSec);
        } catch (RuntimeException e) {
            errors.add("script parse error: " + e.getMessage());
            return new ImportDoc(raw, null, null, List.of(), errors);
        }
        Transcript transcript;
        try {
            transcript = respSec == null ? new Transcript(List.of()) : Transcript.parse(respSec, script);
        } catch (RuntimeException e) {
            errors.add("responses parse error: " + e.getMessage());
            transcript = new Transcript(List.of());
        }
        List<Quantities.Quantity> quantities = qtySec == null ? List.of() : Quantities.parseSection(qtySec);
        return new ImportDoc(raw, script, transcript, quantities, errors);
    }

    static String section(String raw, String name) {
        String marker = "=== " + name + " ===";
        int start = raw.indexOf(marker);
        if (start < 0) return null;
        start += marker.length();
        int end = raw.indexOf("=== ", start);
        String s = end < 0 ? raw.substring(start) : raw.substring(start, end);
        return s.strip();
    }

    private static final class ArrayList2<T> extends java.util.ArrayList<T> {}
}
