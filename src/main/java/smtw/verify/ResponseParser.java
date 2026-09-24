package smtw.verify;

import smtw.smt.Sexpr;
import smtw.smt.SexprParser;
import smtw.smt.SmtCommand;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses a captured solver response stream and pairs each response token with the matching
 * request command in transcript order: check-sat* gets sat/unsat/unknown, get-model gets a
 * model s-expression, get-unsat-core gets a label list. Everything is kept in response order.
 */
public final class ResponseParser {

    private ResponseParser() {}

    public record PairedResponse(int requestOrdinal, String requestHead, String kind,
                                 String raw, List<String> parseErrors) {
        public PairedResponse { parseErrors = List.copyOf(parseErrors); }
    }

    public static List<PairedResponse> parse(String responseText, List<SmtCommand> requests) {
        List<Sexpr> exprs;
        List<String> fatal = new ArrayList<>();
        try {
            exprs = SexprParser.parseAll(responseText);
        } catch (RuntimeException e) {
            return List.of(new PairedResponse(-1, "<none>", "PARSE_ERROR", responseText,
                    List.of("response parse failure: " + e.getMessage())));
        }
        List<PairedResponse> out = new ArrayList<>();
        int ri = 0;
        for (SmtCommand cmd : requests) {
            String expected = switch (cmd.kind()) {
                case CHECK_SAT, CHECK_SAT_ASSUMING -> "check";
                case GET_MODEL -> "model";
                case GET_UNSAT_CORE -> "core";
                default -> null;
            };
            if (expected == null) continue;
            if (ri >= exprs.size()) {
                out.add(new PairedResponse(cmd.ordinal(), cmd.head(), expected, "",
                        List.of("missing solver response for " + cmd.head())));
                continue;
            }
            Sexpr e = exprs.get(ri++);
            List<String> errs = new ArrayList<>();
            String raw = e.raw();
            String kind = expected;
            switch (expected) {
                case "check" -> {
                    if (e instanceof Sexpr.Atom a) {
                        switch (a.token()) {
                            case "sat", "unsat", "unknown" -> { }
                            default -> errs.add("unexpected check result token: " + a.token());
                        }
                    } else {
                        errs.add("check result must be a bare atom");
                    }
                }
                case "model" -> {
                    if (!(e instanceof Sexpr.List)) errs.add("model response must be a list");
                }
                case "core" -> {
                    if (!(e instanceof Sexpr.List)) errs.add("unsat core response must be a list");
                }
            }
            out.add(new PairedResponse(cmd.ordinal(), cmd.head(), kind, raw, errs));
        }
        while (ri < exprs.size()) {
            fatal.add("unpaired extra response kept as diagnostic: " + exprs.get(ri).raw());
            ri++;
        }
        if (!fatal.isEmpty()) {
            out.add(new PairedResponse(-1, "<none>", "EXTRA", "", fatal));
        }
        return out;
    }
}
