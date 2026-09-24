package smtw.core;

/** Provenance of one core label: which raw command and binding produced it, with the asserted term text. */
public record LabelSource(String label, String bindingId, String commandId,
                          int commandOrdinal, String termText) {
}
