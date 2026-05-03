package backend.backend.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

@EventType("PasswordResetRequested")
public class PasswordResetRequested extends DomainEvent {

    private final String userId;
    private final String email;
    private final String firstName;
    private final String otp;
    private final String expiresAt;

    @JsonCreator
    public PasswordResetRequested(
            @JsonProperty("eventId") UUID eventId,
            @JsonProperty("occurredAt") Instant occurredAt,
            @JsonProperty("schemaVersion") int schemaVersion,
            @JsonProperty("correlationId") String correlationId,
            @JsonProperty("userId") String userId,
            @JsonProperty("email") String email,
            @JsonProperty("firstName") String firstName,
            @JsonProperty("otp") String otp,
            @JsonProperty("expiresAt") String expiresAt) {
        super(eventId, occurredAt, schemaVersion, correlationId);
        this.userId = userId;
        this.email = email;
        this.firstName = firstName;
        this.otp = otp;
        this.expiresAt = expiresAt;
    }

    public static PasswordResetRequested forUser(String userId, String email,
                                                 String firstName, String otp,
                                                 Instant expiresAt) {
        return new PasswordResetRequested(null, null, 1, "user-" + userId,
                userId, email, firstName, otp,
                expiresAt == null ? null : expiresAt.toString());
    }

    public String getUserId() { return userId; }
    public String getEmail() { return email; }
    public String getFirstName() { return firstName; }
    public String getOtp() { return otp; }
    public String getExpiresAt() { return expiresAt; }

    @Override public String aggregateType() { return "user"; }
    @Override public String aggregateId() { return userId; }
}
