package backend.backend.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Maker-checker hold notification. Staged by {@code LoanRiskResultConsumer}
 * when ML recommends APPROVED — the loan parks at PENDING_ADMIN_DECISION
 * until an admin acts on it. Semantically distinct from {@link LoanFinalized}
 * (which represents the terminal disbursement / rejection moment).
 *
 * <p>Not consumed by the email/SMS subscriptions today (their SNS filter is
 * pinned to {@code eventType=LoanFinalized}). Reserved for future staff-alert
 * / audit consumers that want a "loan needs review" signal.
 */
@EventType("LoanPendingAdminDecision")
public class LoanPendingAdminDecision extends DomainEvent {

    private final String loanAppId;
    private final String userId;
    private final String reason;
    private final Long loanId;
    private final double amount;
    private final Integer tenureMonths;
    private final BigDecimal interestRate;

    @JsonCreator
    public LoanPendingAdminDecision(
            @JsonProperty("eventId") UUID eventId,
            @JsonProperty("occurredAt") Instant occurredAt,
            @JsonProperty("schemaVersion") int schemaVersion,
            @JsonProperty("correlationId") String correlationId,
            @JsonProperty("loanAppId") String loanAppId,
            @JsonProperty("userId") String userId,
            @JsonProperty("reason") String reason,
            @JsonProperty("loanId") Long loanId,
            @JsonProperty("amount") double amount,
            @JsonProperty("tenureMonths") Integer tenureMonths,
            @JsonProperty("interestRate") BigDecimal interestRate) {
        super(eventId, occurredAt, schemaVersion, correlationId);
        this.loanAppId = loanAppId;
        this.userId = userId;
        this.reason = reason;
        this.loanId = loanId;
        this.amount = amount;
        this.tenureMonths = tenureMonths;
        this.interestRate = interestRate;
    }

    public String getLoanAppId() { return loanAppId; }
    public String getUserId() { return userId; }
    public String getReason() { return reason; }
    public Long getLoanId() { return loanId; }
    public double getAmount() { return amount; }
    public Integer getTenureMonths() { return tenureMonths; }
    public BigDecimal getInterestRate() { return interestRate; }

    @Override public String aggregateType() { return "loan_application"; }
    @Override public String aggregateId() { return loanAppId; }
}
