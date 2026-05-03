package backend.backend.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Outbound to {@code subby-risk-requested} SNS topic. Picked up by
 * SubbyPythonLoan's {@code subby-risk-requests} queue.
 *
 * <p>Field shape matches {@code SubbyPythonLoan/src/messaging/schemas.py}:
 * {@code LoanRiskRequestedPayload} expects {@code amountRequested},
 * {@code tenureMonths}, {@code features} — NOT {@code amount}. Keep these
 * names in sync with the Python schema or the consumer will NonRetriable-DLQ
 * every message.
 *
 * <p>Features are a flat {@code Map<String, Object>} here so we can add signals
 * without a schema change; SubbyPythonLoan uses {@code ConfigDict(extra="allow")}.
 */
@EventType("LoanRiskRequested")
public class LoanRiskRequested extends DomainEvent {

    private final String loanAppId;
    private final double amountRequested;
    private final int tenureMonths;
    private final Map<String, Object> features;

    @JsonCreator
    public LoanRiskRequested(
            @JsonProperty("eventId") UUID eventId,
            @JsonProperty("occurredAt") Instant occurredAt,
            @JsonProperty("schemaVersion") int schemaVersion,
            @JsonProperty("correlationId") String correlationId,
            @JsonProperty("loanAppId") String loanAppId,
            @JsonProperty("amountRequested") double amountRequested,
            @JsonProperty("tenureMonths") int tenureMonths,
            @JsonProperty("features") Map<String, Object> features) {
        super(eventId, occurredAt, schemaVersion, correlationId);
        this.loanAppId = loanAppId;
        this.amountRequested = amountRequested;
        this.tenureMonths = tenureMonths;
        this.features = features;
    }

    public static LoanRiskRequested of(String loanAppId, double amountRequested,
                                       int tenureMonths, Map<String, Object> features) {
        return new LoanRiskRequested(null, null, 1, loanAppId,
                loanAppId, amountRequested, tenureMonths, features);
    }

    public String getLoanAppId() { return loanAppId; }
    public double getAmountRequested() { return amountRequested; }
    public int getTenureMonths() { return tenureMonths; }
    public Map<String, Object> getFeatures() { return features; }

    @Override public String aggregateType() { return "loan_application"; }
    @Override public String aggregateId() { return loanAppId; }
}
