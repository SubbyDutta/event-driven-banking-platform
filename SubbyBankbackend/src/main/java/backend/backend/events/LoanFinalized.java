package backend.backend.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Post-decision fan-out event. Staged by {@code LoanFinalizationService} after
 * the terminal state is persisted (including EMI creation + disbursement on
 * APPROVED). Published on {@code subby-notifications}; the email/SMS/audit
 * consumers pick it up without having to re-read the loan row.
 *
 * <p>Unlike {@link LoanDecisionMade} (internal, drives finalization), this
 * event is the authoritative "the side-effects are done" moment.
 */
@EventType("LoanFinalized")
public class LoanFinalized extends DomainEvent {

    private final String loanAppId;
    private final String userId;
    private final String decision;
    private final String reason;
    private final Long loanId;
    private final double amount;
    private final Integer tenureMonths;
    private final BigDecimal interestRate;
    private final Double monthlyEmi;
    private final String firstDueDate;

    @JsonCreator
    public LoanFinalized(
            @JsonProperty("eventId") UUID eventId,
            @JsonProperty("occurredAt") Instant occurredAt,
            @JsonProperty("schemaVersion") int schemaVersion,
            @JsonProperty("correlationId") String correlationId,
            @JsonProperty("loanAppId") String loanAppId,
            @JsonProperty("userId") String userId,
            @JsonProperty("decision") String decision,
            @JsonProperty("reason") String reason,
            @JsonProperty("loanId") Long loanId,
            @JsonProperty("amount") double amount,
            @JsonProperty("tenureMonths") Integer tenureMonths,
            @JsonProperty("interestRate") BigDecimal interestRate,
            @JsonProperty("monthlyEmi") Double monthlyEmi,
            @JsonProperty("firstDueDate") String firstDueDate) {
        super(eventId, occurredAt, schemaVersion, correlationId);
        this.loanAppId = loanAppId;
        this.userId = userId;
        this.decision = decision;
        this.reason = reason;
        this.loanId = loanId;
        this.amount = amount;
        this.tenureMonths = tenureMonths;
        this.interestRate = interestRate;
        this.monthlyEmi = monthlyEmi;
        this.firstDueDate = firstDueDate;
    }

    public String getLoanAppId() { return loanAppId; }
    public String getUserId() { return userId; }
    public String getDecision() { return decision; }
    public String getReason() { return reason; }
    public Long getLoanId() { return loanId; }
    public double getAmount() { return amount; }
    public Integer getTenureMonths() { return tenureMonths; }
    public BigDecimal getInterestRate() { return interestRate; }
    public Double getMonthlyEmi() { return monthlyEmi; }
    public String getFirstDueDate() { return firstDueDate; }

    @Override public String aggregateType() { return "loan_application"; }
    @Override public String aggregateId() { return loanAppId; }
}
