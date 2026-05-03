package backend.backend.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * Published the moment a user uploads KYC docs and the controller commits the
 * S3 uploads + status transition. Carries the S3 keys (not bytes) and basic
 * applicant fields so {@code KycSubmittedConsumer} can forward the set to
 * findoc-verify without re-reading the HTTP request.
 *
 * <p>Topic: {@code subby-kyc-events} (filtered to {@code eventType=KycSubmitted}
 * at the SQS subscription). Aggregate is the user (not a KYC-application entity
 * — KYC state lives directly on {@code users}).
 */
@EventType("KycSubmitted")
public class KycSubmitted extends DomainEvent {

    private final String userId;
    private final Map<String, String> s3Keys;
    private final String applicantName;
    private final String email;
    private final String phone;
    /** ISO-8601 (YYYY-MM-DD). Sent as a string so the event survives schema
     *  drift on either side without date-format negotiation. */
    private final String applicantDob;

    @JsonCreator
    public KycSubmitted(
            @JsonProperty("eventId") UUID eventId,
            @JsonProperty("occurredAt") Instant occurredAt,
            @JsonProperty("schemaVersion") int schemaVersion,
            @JsonProperty("correlationId") String correlationId,
            @JsonProperty("userId") String userId,
            @JsonProperty("s3Keys") Map<String, String> s3Keys,
            @JsonProperty("applicantName") String applicantName,
            @JsonProperty("email") String email,
            @JsonProperty("phone") String phone,
            @JsonProperty("applicantDob") String applicantDob) {
        super(eventId, occurredAt, schemaVersion, correlationId);
        this.userId = userId;
        this.s3Keys = s3Keys;
        this.applicantName = applicantName;
        this.email = email;
        this.phone = phone;
        this.applicantDob = applicantDob;
    }

    /** Convenience builder used by the controller. */
    public static KycSubmitted forUser(String userId, Map<String, String> s3Keys,
                                       String applicantName, String email, String phone,
                                       LocalDate applicantDob) {
        return new KycSubmitted(null, null, 1, "user-" + userId,
                userId, s3Keys, applicantName, email, phone,
                applicantDob == null ? null : applicantDob.toString());
    }

    public String getUserId() { return userId; }
    public Map<String, String> getS3Keys() { return s3Keys; }
    public String getApplicantName() { return applicantName; }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }
    public String getApplicantDob() { return applicantDob; }

    @Override public String aggregateType() { return "user"; }
    @Override public String aggregateId() { return userId; }
}
