package smtw.verify;

import smtw.smt.Theories;
import smtw.scope.ScopeMachine;

import java.util.List;

/** Output of replaying one transcript+response pair. */
public record VerificationReport(
        String transcriptId,
        Theories.LogicSupport logicSupport,
        String logicName,
        ScopeMachine.Replay scope,
        List<CheckVerification> checks,
        List<String> globalDiagnostics,
        String algoVersion,
        String paramFingerprint) {

    public VerificationReport {
        checks = List.copyOf(checks);
        globalDiagnostics = List.copyOf(globalDiagnostics);
    }
}
