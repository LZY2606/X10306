package smtw.engine;

/** A human verdict on a specific check/core candidate. Conflicts are first-class. */
public record DecisionRecord(String id, String subject, String candidateId,
                             String reviewer, String verdict, String note,
                             String eventId, long atMs, String supersedesId) {

    public boolean conflictsWith(DecisionRecord other) {
        return subject.equals(other.subject) && !verdict.equals(other.verdict);
    }
}
