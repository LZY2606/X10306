package smtvault.domain;

import java.util.List;
import java.util.Map;

/**
 * Baseline import artifact. Nothing here is ever overwritten: revisions are
 * stored as new transcripts linked by parent id.
 */
public final class Transcript {
    public String id;
    public String parentId;
    public String label;
    public String logic;
    public String rawText;
    public List<CmdRecord> commands;
    public Map<String, String> meta;
    public List<String> diagnostics;
}
