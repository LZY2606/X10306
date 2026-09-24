package smtw.verify;

import smtw.core.CoreVerification;
import smtw.model.EvalNode;
import smtw.scope.CheckPoint;

import java.util.List;

/** Complete, traceable verification record for one check-sat / check-sat-assuming command. */
public record CheckVerification(
        String id,
        int commandOrdinal,
        String requestHead,
        CheckPoint checkpoint,
        String solverClaim,            // sat / unsat / unknown / ERROR / MISSING
        String localVerdict,          // SAT / UNSAT / UNKNOWN / UNSUPPORTED / ERROR
        String agreement,             // AGREE / DISAGREE / INCONCLUSIVE / NOT-COMPARABLE
        List<EvalNode> modelTrees,
        CoreVerification coreVerification,
        boolean oneShotAssumptions,
        List<String> diagnostics) {

    public CheckVerification {
        modelTrees = List.copyOf(modelTrees);
        diagnostics = List.copyOf(diagnostics);
    }
}
