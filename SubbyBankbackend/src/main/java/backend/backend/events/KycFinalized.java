package backend.backend.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

/**
 * Fired after all follow-ups for a KYC decision have committed — includes the
 * {@code accountActive} flip that unlocks the user's banking features. Consumers
 * that care only about "KYC is done, you may now transact" (e.g. a welcome-bonus
 * issuer) should listen to this rather than {@link KycDecisionMade}.
 */
@EventType("KycFinalized")
public class KycFinalized extends DomainEvent {

    private final String userId;
    private final boolean accountActivated;

    @JsonCreator
    public KycFinalized(
            @JsonProperty("eventId") UUID eventId,
            @JsonProperty("occurredAt") Instant occurredAt,
            @JsonProperty("schemaVersion") int schemaVersion,
            @JsonProperty("correlationId") String correlationId,
            @JsonProperty("userId") String userId,
            @JsonProperty("accountActivated") boolean accountActivated) {
        super(eventId, occurredAt, schemaVersion, correlationId);
        this.userId = userId;
        this.accountActivated = accountActivated;
    }

    public static KycFinalized forUser(String userId, boolean accountActivated) {
        return new KycFinalized(null, null, 1, "user-" + userId, userId, accountActivated);
    }

    public String getUserId() { return userId; }
    public boolean isAccountActivated() { return accountActivated; }

    @Override public String aggregateType() { return "user"; }
    @Override public String aggregateId() { return userId; }
}
