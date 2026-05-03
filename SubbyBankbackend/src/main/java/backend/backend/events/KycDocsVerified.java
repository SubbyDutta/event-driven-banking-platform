package backend.backend.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

/**
 * Emitted once findoc-verify confirms the docs were received and queued for
 * the pipeline — i.e. after {@code KycSubmittedConsumer} gets a 2xx from
 * {@code POST /api/v1/kyc/submit} and stores the {@code applicationId}.
 *
 * <p>The spec lists this event for parity with the loan flow; on the happy path
 * it is informational (audit/observers). Consumers that want a final outcome
 * should subscribe to {@link KycDecisionMade} instead.
 */
@EventType("KycDocsVerified")
public class KycDocsVerified extends DomainEvent {

    private final String userId;
    private final String findocAppId;
    private final Double nameMatchScore;
    private final Boolean dobMatches;

    @JsonCreator
    public KycDocsVerified(
            @JsonProperty("eventId") UUID eventId,
            @JsonProperty("occurredAt") Instant occurredAt,
            @JsonProperty("schemaVersion") int schemaVersion,
            @JsonProperty("correlationId") String correlationId,
            @JsonProperty("userId") String userId,
            @JsonProperty("findocAppId") String findocAppId,
            @JsonProperty("nameMatchScore") Double nameMatchScore,
            @JsonProperty("dobMatches") Boolean dobMatches) {
        super(eventId, occurredAt, schemaVersion, correlationId);
        this.userId = userId;
        this.findocAppId = findocAppId;
        this.nameMatchScore = nameMatchScore;
        this.dobMatches = dobMatches;
    }

    public static KycDocsVerified forUser(String userId, String findocAppId) {
        return new KycDocsVerified(null, null, 1, "user-" + userId,
                userId, findocAppId, null, null);
    }

    public String getUserId() { return userId; }
    public String getFindocAppId() { return findocAppId; }
    public Double getNameMatchScore() { return nameMatchScore; }
    public Boolean getDobMatches() { return dobMatches; }

    @Override public String aggregateType() { return "user"; }
    @Override public String aggregateId() { return userId; }
}
