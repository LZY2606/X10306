package witness.domain;

/** Direct provenance pointer: source document id, version, command index, range. */
public record SourceRef(String sourceId, int version, int commandIndex, int lineFrom, int lineTo) {
    public String display() {
        return sourceId + "@v" + version + "#cmd" + commandIndex + "(L" + lineFrom + "-" + lineTo + ")";
    }
}
