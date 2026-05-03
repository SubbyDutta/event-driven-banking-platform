package backend.backend.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

@EventType("AdminKycReviewNeeded")
public class AdminKycReviewNeeded extends DomainEvent {

    private final String userId;
    private final String username;
    private final String applicantEmail;
    private final String findocApplicationId;
    private final String reason;

    @JsonCreator
    public AdminKycReviewNeeded(
            @JsonProperty("eventId") UUID eventId,
            @JsonProperty("occurredAt") Instant occurredAt,
            @JsonProperty("schemaVersion") int schemaVersion,
            @JsonProperty("correlationId") String correlationId,
            @JsonProperty("userId") String userId,
            @JsonProperty("username") String username,
            @JsonProperty("applicantEmail") String applicantEmail,
            @JsonProperty("findocApplicationId") String findocApplicationId,
            @JsonProperty("reason") String reason) {
        super(eventId, occurredAt, schemaVersion, correlationId);
        this.userId = userId;
        this.username = username;
        this.applicantEmail = applicantEmail;
        this.findocApplicationId = findocApplicationId;
        this.reason = reason;
    }

    public static AdminKycReviewNeeded forUser(String userId, String username,
                                               String applicantEmail,
                                               String findocApplicationId,
                                               String reason) {
        return new AdminKycReviewNeeded(null, null, 1, "user-" + userId,
                userId, username, applicantEmail, findocApplicationId, reason);
    }

    public String getUserId() { return userId; }
    public String getUsername() { return username; }
    public String getApplicantEmail() { return applicantEmail; }
    public String getFindocApplicationId() { return findocApplicationId; }
    public String getReason() { return reason; }

    @Override public String aggregateType() { return "user"; }
    @Override public String aggregateId() { return userId; }
}
