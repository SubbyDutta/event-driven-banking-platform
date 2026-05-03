package backend.backend.model;

/**
 * Lifecycle states for a user's KYC verification. Persisted as VARCHAR via
 * {@code @Enumerated(EnumType.STRING)}; the V2 migration adds a CHECK
 * constraint that mirrors these values at the database layer.
 *
 * <p>Transitions (driven by KycController, KycSubmittedConsumer,
 * KycFindocResultConsumer, and admin override):
 * <pre>
 *   NONE -> KYC_SUBMITTED -> KYC_DOCS_UNDER_REVIEW ->
 *       KYC_APPROVED | KYC_REJECTED | KYC_MANUAL_REVIEW
 * </pre>
 * Account activation happens exactly when the user transitions into
 * {@code KYC_APPROVED}.
 */
public enum KycStatus {
    NONE,
    KYC_SUBMITTED,
    KYC_DOCS_UNDER_REVIEW,
    KYC_APPROVED,
    KYC_REJECTED,
    KYC_MANUAL_REVIEW;

    public boolean isTerminal() {
        return this == KYC_APPROVED || this == KYC_REJECTED || this == KYC_MANUAL_REVIEW;
    }

    public boolean isInFlight() {
        return this == KYC_SUBMITTED || this == KYC_DOCS_UNDER_REVIEW;
    }
}
