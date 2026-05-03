package backend.backend.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

/**
 * Terminal KYC outcome, published after the user record has been updated.
 * Fans out via {@code subby-kyc-events} (filtered at the subscription) to the
 * {@code subby-kyc-decision} queue, where {@code KycEmailNotificationConsumer}
 * picks it up and sends the welcome/reject/review email.
 */
@EventType("KycDecisionMade")
public class KycDecisionMade extends DomainEvent {

    private final String userId;
    /** One of: {@code KYC_APPROVED}, {@code KYC_REJECTED}, {@code KYC_MANUAL_REVIEW}. */
    private final String decision;
    private final String reason;
    /** Where the decision originated — useful for auditing admin overrides. */
    private final String source;
    private final Boolean overridden;

    @JsonCreator
    public KycDecisionMade(
            @JsonProperty("eventId") UUID eventId,
            @JsonProperty("occurredAt") Instant occurredAt,
            @JsonProperty("schemaVersion") int schemaVersion,
            @JsonProperty("correlationId") String correlationId,
            @JsonProperty("userId") String userId,
            @JsonProperty("decision") String decision,
            @JsonProperty("reason") String reason,
            @JsonProperty("source") String source,
            @JsonProperty("overridden") Boolean overridden) {
        super(eventId, occurredAt, schemaVersion, correlationId);
        this.userId = userId;
        this.decision = decision;
        this.reason = reason;
        this.source = source;
        this.overridden = overridden;
    }

    public static KycDecisionMade fromPipeline(String userId, String decision, String reason) {
        return new KycDecisionMade(null, null, 1, "user-" + userId,
                userId, decision, reason, "findoc-pipeline", false);
    }

    public static KycDecisionMade fromAdminOverride(String userId, String decision, String reason, String admin) {
        return new KycDecisionMade(null, null, 1, "user-" + userId,
                userId, decision, reason, "admin:" + admin, true);
    }

    public String getUserId() { return userId; }
    public String getDecision() { return decision; }
    public String getReason() { return reason; }
    public String getSource() { return source; }
    public Boolean getOverridden() { return overridden; }

    @Override public String aggregateType() { return "user"; }
    @Override public String aggregateId() { return userId; }
}
