package smtwitness.casepack;

import java.util.List;

/**
 * The portable, copyable SMT-LIB session envelope.
 * Commands and solver responses are kept as separate, equally ordered streams
 * so the original response order is preserved verbatim.
 */
public record SessionBundle(
        String format,
        String title,
        String description,
        List<RawLine> commands,
        List<ResponseLine> responses,
        List<String> tags) {

    public static final String FORMAT = "smt-witness-room/session/v1";

    public record RawLine(int seq, String raw) {
    }

    public record ResponseLine(int seq, String kind, String raw) {
    }
}
