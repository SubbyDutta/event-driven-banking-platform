package backend.backend.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Staged by {@code LoanController.apply} after the row is inserted and the 9
 * loan documents are pushed to S3. Drives {@link backend.backend.messaging.consumer.loan.LoanSubmittedConsumer}
 * which forwards the set to findoc-verify.
 *
 * <p>We carry S3 keys (not bytes) so the event stays well below the SNS 256 KB
 * ceiling and the consumer can stream bytes straight from S3 into the
 * multipart request. Applicant identity fields come from the authenticated
 * User row, NOT from form input — see {@code LoanController.apply} — so a
 * post-KYC attacker cannot declare a different name to findoc-verify.
 *
 * <p>Topic: {@code subby-loan-events} (filter: {@code eventType=LoanApplicationSubmitted}).
 * Aggregate: the loan_application row (not the user) — one user can submit
 * many applications over time.
 */
@EventType("LoanApplicationSubmitted")
public class LoanApplicationSubmitted extends DomainEvent {

    private final String loanAppId;
    private final String userId;
    private final String username;
    private final double amount;
    private final String purpose;
    private final int tenureMonths;
    /** Map of docType (lowercase) → S3 key. 9 entries expected. */
    private final Map<String, String> s3Keys;
    /** KYC-authoritative applicant fields, pinned by the controller. */
    private final String applicantName;
    private final String email;
    private final String phone;
    /** ISO-8601 YYYY-MM-DD. Null if the user's row has no DOB yet. */
    private final String applicantDob;

    @JsonCreator
    public LoanApplicationSubmitted(
            @JsonProperty("eventId") UUID eventId,
            @JsonProperty("occurredAt") Instant occurredAt,
            @JsonProperty("schemaVersion") int schemaVersion,
            @JsonProperty("correlationId") String correlationId,
            @JsonProperty("loanAppId") String loanAppId,
            @JsonProperty("userId") String userId,
            @JsonProperty("username") String username,
            @JsonProperty("amount") double amount,
            @JsonProperty("purpose") String purpose,
            @JsonProperty("tenureMonths") int tenureMonths,
            @JsonProperty("s3Keys") Map<String, String> s3Keys,
            @JsonProperty("applicantName") String applicantName,
            @JsonProperty("email") String email,
            @JsonProperty("phone") String phone,
            @JsonProperty("applicantDob") String applicantDob) {
        super(eventId, occurredAt, schemaVersion, correlationId);
        this.loanAppId = loanAppId;
        this.userId = userId;
        this.username = username;
        this.amount = amount;
        this.purpose = purpose;
        this.tenureMonths = tenureMonths;
        this.s3Keys = s3Keys;
        this.applicantName = applicantName;
        this.email = email;
        this.phone = phone;
        this.applicantDob = applicantDob;
    }

    public static LoanApplicationSubmitted of(String loanAppId, String userId, String username,
                                              double amount, String purpose, int tenureMonths,
                                              Map<String, String> s3Keys,
                                              String applicantName, String email, String phone,
                                              String applicantDob) {
        return new LoanApplicationSubmitted(null, null, 1, loanAppId,
                loanAppId, userId, username, amount, purpose, tenureMonths,
                s3Keys, applicantName, email, phone, applicantDob);
    }

    public String getLoanAppId() { return loanAppId; }
    public String getUserId() { return userId; }
    public String getUsername() { return username; }
    public double getAmount() { return amount; }
    public String getPurpose() { return purpose; }
    public int getTenureMonths() { return tenureMonths; }
    public Map<String, String> getS3Keys() { return s3Keys; }
    public String getApplicantName() { return applicantName; }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }
    public String getApplicantDob() { return applicantDob; }

    @Override public String aggregateType() { return "loan_application"; }
    @Override public String aggregateId() { return loanAppId; }
}
