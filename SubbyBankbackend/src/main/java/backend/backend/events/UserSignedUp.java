package backend.backend.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

@EventType("UserSignedUp")
public class UserSignedUp extends DomainEvent {

    private final String userId;
    private final String email;
    private final String firstName;
    private final String username;
    private final String signedUpAt;

    @JsonCreator
    public UserSignedUp(
            @JsonProperty("eventId") UUID eventId,
            @JsonProperty("occurredAt") Instant occurredAt,
            @JsonProperty("schemaVersion") int schemaVersion,
            @JsonProperty("correlationId") String correlationId,
            @JsonProperty("userId") String userId,
            @JsonProperty("email") String email,
            @JsonProperty("firstName") String firstName,
            @JsonProperty("username") String username,
            @JsonProperty("signedUpAt") String signedUpAt) {
        super(eventId, occurredAt, schemaVersion, correlationId);
        this.userId = userId;
        this.email = email;
        this.firstName = firstName;
        this.username = username;
        this.signedUpAt = signedUpAt;
    }

    public static UserSignedUp forUser(String userId, String email, String firstName,
                                       String username, Instant signedUpAt) {
        return new UserSignedUp(null, null, 1, "user-" + userId,
                userId, email, firstName, username,
                signedUpAt == null ? null : signedUpAt.toString());
    }

    public String getUserId() { return userId; }
    public String getEmail() { return email; }
    public String getFirstName() { return firstName; }
    public String getUsername() { return username; }
    public String getSignedUpAt() { return signedUpAt; }

    @Override public String aggregateType() { return "user"; }
    @Override public String aggregateId() { return userId; }
}
