package backend.backend.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

@EventType("PasswordChanged")
public class PasswordChanged extends DomainEvent {

    private final String userId;
    private final String email;
    private final String firstName;
    private final String occurredAtIso;

    @JsonCreator
    public PasswordChanged(
            @JsonProperty("eventId") UUID eventId,
            @JsonProperty("occurredAt") Instant occurredAt,
            @JsonProperty("schemaVersion") int schemaVersion,
            @JsonProperty("correlationId") String correlationId,
            @JsonProperty("userId") String userId,
            @JsonProperty("email") String email,
            @JsonProperty("firstName") String firstName,
            @JsonProperty("occurredAtIso") String occurredAtIso) {
        super(eventId, occurredAt, schemaVersion, correlationId);
        this.userId = userId;
        this.email = email;
        this.firstName = firstName;
        this.occurredAtIso = occurredAtIso;
    }

    public static PasswordChanged forUser(String userId, String email, String firstName,
                                          Instant occurredAt) {
        return new PasswordChanged(null, null, 1, "user-" + userId,
                userId, email, firstName,
                occurredAt == null ? null : occurredAt.toString());
    }

    public String getUserId() { return userId; }
    public String getEmail() { return email; }
    public String getFirstName() { return firstName; }
    public String getOccurredAtIso() { return occurredAtIso; }

    @Override public String aggregateType() { return "user"; }
    @Override public String aggregateId() { return userId; }
}
