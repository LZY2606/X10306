package smtwitness.bundle;

import java.util.List;

/** Hard format error: the submission cannot be ingested. All problems are reported together. */
public class BundleFormatException extends RuntimeException {
    private final List<String> problems;

    public BundleFormatException(List<String> problems) {
        super(String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() { return problems; }
}
