package smtwitness.smt;

import java.util.List;

/**
 * A block of the imported transcript. type is one of:
 * command | response-model | response-core | response-other.
 * Raw text, block order and type are preserved verbatim.
 */
public record Block(int seq, String type, String text) {
    public static final String CMD = "command";
    public static final String RESP_MODEL = "response-model";
    public static final String RESP_CORE = "response-core";
    public static final String RESP_OTHER = "response-other";

    public static List<String> validTypes() {
        return List.of(CMD, RESP_MODEL, RESP_CORE, RESP_OTHER);
    }
}
