package smtwitness.casepack;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Structural validation for imported session bundles. Produces explicit errors. */
public final class BundleValidation {
    private static final Set<String> RESPONSE_KINDS =
            Set.of("success", "sat", "unsat", "unknown", "model", "core", "error");

    private BundleValidation() {
    }

    public static List<String> validate(SessionBundle b) {
        List<String> errors = new ArrayList<>();
        if (b == null) {
            errors.add("bundle is empty");
            return errors;
        }
        if (!SessionBundle.FORMAT.equals(b.format())) {
            errors.add("unsupported format: " + b.format() + " (expected " + SessionBundle.FORMAT + ")");
        }
        if (b.commands() == null || b.commands().isEmpty()) {
            errors.add("commands stream is empty");
        }
        if (b.responses() == null || b.responses().isEmpty()) {
            errors.add("responses stream is empty");
        }
        if (b.commands() != null && b.responses() != null && b.commands().size() != b.responses().size()) {
            errors.add("commands (" + b.commands().size() + ") and responses (" + b.responses().size()
                    + ") must be paired one-to-one");
        }
        validateSequence("command", b.commands(), errors, true);
        validateSequence("response", b.responses(), errors, false);
        return errors;
    }

    private static void validateSequence(String label, List<? extends Record> lines, List<String> errors, boolean checkRaw) {
        if (lines == null) {
            return;
        }
        for (int i = 0; i < lines.size(); i++) {
            Record r = lines.get(i);
            Integer seq = r instanceof SessionBundle.RawLine rl ? rl.seq()
                    : ((SessionBundle.ResponseLine) r).seq();
            String raw = r instanceof SessionBundle.RawLine rl ? rl.raw()
                    : ((SessionBundle.ResponseLine) r).raw();
            String kind = r instanceof SessionBundle.ResponseLine rs ? rs.kind() : "success";
            if (seq != i) {
                errors.add(label + " at position " + i + " has seq=" + seq + ", expected " + i);
            }
            if (raw == null || raw.isBlank()) {
                errors.add(label + " seq=" + seq + " has empty raw text");
            }
            if (!RESPONSE_KINDS.contains(kind)) {
                errors.add("response seq=" + seq + " has unknown kind '" + kind + "'");
            }
        }
    }
}
