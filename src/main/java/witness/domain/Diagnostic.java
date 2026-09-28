package witness.domain;

/** Structured diagnostic attached to parse/import/derivation results. */
public record Diagnostic(String code, String message, SourceRef ref) {
    public String display() {
        String at = ref == null ? "" : " @ " + ref.display();
        return "[" + code + "] " + message + at;
    }
}
