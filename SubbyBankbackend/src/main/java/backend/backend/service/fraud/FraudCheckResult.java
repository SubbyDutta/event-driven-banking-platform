package backend.backend.service.fraud;

/**
 * Outcome of a fraud check, together with how that outcome was reached.
 *
 * <p>{@link Status} matters at the audit level — a {@code DEGRADED} approval
 * went through during a fraud-service outage, not via a real model decision.
 * The transaction is allowed but flagged so the operations team can re-score
 * later if needed.
 */
public record FraudCheckResult(double probability, int fraudLabel, Status status, Throwable cause) {

    public enum Status {
        /** Real model decision from FraudPython. */
        CHECKED,
        /** System-originated transfer (BANK / RAZORPAY_TOPUP); ML call skipped. */
        SKIPPED_SYSTEM,
        /** Fraud service unavailable; low-risk transfer allowed without scoring. */
        DEGRADED,
        /** Fraud service unavailable; transfer rejected to protect funds. */
        UNAVAILABLE
    }

    public static FraudCheckResult checked(double probability, int fraudLabel) {
        return new FraudCheckResult(probability, fraudLabel, Status.CHECKED, null);
    }

    public static FraudCheckResult skippedSystem() {
        return new FraudCheckResult(0.0, 0, Status.SKIPPED_SYSTEM, null);
    }

    public static FraudCheckResult degraded() {
        return new FraudCheckResult(0.0, 0, Status.DEGRADED, null);
    }

    public static FraudCheckResult unavailable(Throwable cause) {
        return new FraudCheckResult(0.0, 0, Status.UNAVAILABLE, cause);
    }

    public boolean isFraud() {
        return fraudLabel == 1;
    }

    public boolean isAllowed() {
        return status == Status.CHECKED || status == Status.SKIPPED_SYSTEM || status == Status.DEGRADED;
    }
}
