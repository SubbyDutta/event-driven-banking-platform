package backend.backend.model;

/**
 * Lifecycle of a loan application in the event-driven origination pipeline.
 * Persisted as VARCHAR via {@code @Enumerated(EnumType.STRING)}. Runs alongside
 * the legacy {@code status} column ({@code PENDING/APPROVED/PAID}) which
 * LoanRepayController still consumes unchanged.
 *
 * <p>Transitions:
 * <pre>
 *   DRAFT
 *     -> DOCS_UNDER_REVIEW              (LoanSubmittedConsumer, after findoc accept)
 *         -> DOCS_VERIFIED              (LoanFindocResultConsumer, findoc recommendation != "rejected")
 *             -> RISK_EVALUATED         (LoanRiskResultConsumer)
 *                 -> APPROVED | REJECTED | MANUAL_REVIEW
 *         -> DOCS_REJECTED              (findoc hard-reject short-circuit)
 *             -> REJECTED               (LoanDecisionConsumer applies final state)
 *   Any -> FAILED                       (non-retriable infra failure)
 * </pre>
 */
public enum LoanLifecycleStatus {
    DRAFT,
    DOCS_UNDER_REVIEW,
    DOCS_VERIFIED,
    DOCS_REJECTED,
    RISK_EVALUATED,
    PENDING_ADMIN_DECISION,
    PENDING_USER_ACCEPTANCE,
    APPROVED,
    REJECTED,
    MANUAL_REVIEW,
    FAILED;

    public boolean isTerminal() {
        return this == APPROVED || this == REJECTED || this == MANUAL_REVIEW || this == FAILED;
    }

    public boolean isInFlight() {
        return this == DRAFT
                || this == DOCS_UNDER_REVIEW
                || this == DOCS_VERIFIED
                || this == RISK_EVALUATED
                || this == PENDING_ADMIN_DECISION
                || this == PENDING_USER_ACCEPTANCE;
    }

    /**
     * Whether a user may start a new application while an existing one is in
     * this state. Matches the "one active application at a time" precondition
     * in {@code LoanController.apply}.
     */
    public boolean blocksNewApplication() {
        return this == DOCS_UNDER_REVIEW
                || this == DOCS_VERIFIED
                || this == RISK_EVALUATED
                || this == PENDING_ADMIN_DECISION
                || this == PENDING_USER_ACCEPTANCE
                || this == MANUAL_REVIEW;
    }
}
