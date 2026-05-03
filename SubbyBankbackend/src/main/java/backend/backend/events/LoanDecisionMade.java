package backend.backend.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Terminal decision for a loan application. Published from the result-handling
 * consumers (findoc short-circuit, risk evaluator, admin override) on
 * {@code subby-loan-events}. The {@code subby-loan-decision} queue subscribes
 * with an event-type filter, where {@link backend.backend.messaging.consumer.loan.LoanDecisionConsumer}
 * does the actual approve / reject side-effects (EMI creation, disbursement,
 * user flags) via {@code LoanFinalizationService}.
 *
 * <p>Separating the "decision reached" signal from the "apply the decision"
 * write is deliberate — it lets admin overrides, auto-pipeline, and integration
 * tests all drive the same single finalization code path.
 */
@EventType("LoanDecisionMade")
public class LoanDecisionMade extends DomainEvent {

    private final String loanAppId;
    private final String userId;
    /** One of: {@code APPROVED}, {@code REJECTED}, {@code MANUAL_REVIEW}. */
    private final String decision;
    private final String reason;
    /** {@code A..E} when decision is APPROVED/REJECTED from the risk path; null otherwise. */
    private final String riskBand;
    /** Annualized %, non-null only when decision is APPROVED. */
    private final BigDecimal interestRate;
    /** Where this decision originated: {@code "findoc-reject"}, {@code "risk"}, {@code "admin:<user>"}. */
    private final String source;
    private final Boolean overridden;

    @JsonCreator
    public LoanDecisionMade(
            @JsonProperty("eventId") UUID eventId,
            @JsonProperty("occurredAt") Instant occurredAt,
            @JsonProperty("schemaVersion") int schemaVersion,
            @JsonProperty("correlationId") String correlationId,
            @JsonProperty("loanAppId") String loanAppId,
            @JsonProperty("userId") String userId,
            @JsonProperty("decision") String decision,
            @JsonProperty("reason") String reason,
            @JsonProperty("riskBand") String riskBand,
            @JsonProperty("interestRate") BigDecimal interestRate,
            @JsonProperty("source") String source,
            @JsonProperty("overridden") Boolean overridden) {
        super(eventId, occurredAt, schemaVersion, correlationId);
        this.loanAppId = loanAppId;
        this.userId = userId;
        this.decision = decision;
        this.reason = reason;
        this.riskBand = riskBand;
        this.interestRate = interestRate;
        this.source = source;
        this.overridden = overridden;
    }

    public static LoanDecisionMade fromFindocReject(String loanAppId, String userId, String reason) {
        return new LoanDecisionMade(null, null, 1, loanAppId,
                loanAppId, userId, "REJECTED", reason, null, null, "findoc-reject", false);
    }

    public static LoanDecisionMade fromRisk(String loanAppId, String userId, String decision,
                                            String reason, String riskBand, BigDecimal interestRate) {
        return new LoanDecisionMade(null, null, 1, loanAppId,
                loanAppId, userId, decision, reason, riskBand, interestRate, "risk", false);
    }

    public static LoanDecisionMade fromAdminOverride(String loanAppId, String userId, String decision,
                                                     String reason, BigDecimal interestRate, String admin) {
        return new LoanDecisionMade(null, null, 1, loanAppId,
                loanAppId, userId, decision, reason, null, interestRate, "admin:" + admin, true);
    }

    public String getLoanAppId() { return loanAppId; }
    public String getUserId() { return userId; }
    public String getDecision() { return decision; }
    public String getReason() { return reason; }
    public String getRiskBand() { return riskBand; }
    public BigDecimal getInterestRate() { return interestRate; }
    public String getSource() { return source; }
    public Boolean getOverridden() { return overridden; }

    @Override public String aggregateType() { return "loan_application"; }
    @Override public String aggregateId() { return loanAppId; }
}
