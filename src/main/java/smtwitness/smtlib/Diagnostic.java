package smtwitness.smtlib;

/**
 * A machine-readable diagnostic. Severity, code and a stable subject id let the
 * UI surface unconvertible values in place without dropping original data.
 */
public record Diagnostic(String code, String severity, String subjectId, String message) {
    public static Diagnostic error(String code, String subjectId, String message) {
        return new Diagnostic(code, "error", subjectId, message);
    }

    public static Diagnostic warn(String code, String subjectId, String message) {
        return new Diagnostic(code, "warn", subjectId, message);
    }

    public static Diagnostic info(String code, String subjectId, String message) {
        return new Diagnostic(code, "info", subjectId, message);
    }
}
