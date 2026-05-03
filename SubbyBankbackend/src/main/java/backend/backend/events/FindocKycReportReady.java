package backend.backend.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.Instant;

/**
 * Inbound envelope published by findoc-verify on the {@code findoc-kyc-report-ready}
 * SNS topic. Reaches Java via the {@code subby-kyc-findoc-results} SQS queue.
 *
 * <p>Unlike Java's own {@link DomainEvent}s (which are flat), findoc-verify
 * wraps its payload one level deep — see
 * {@code findoc-verify/src/messaging/sns_publisher.py}:
 * <pre>{@code
 *   { eventId, schemaVersion, eventType, occurredAt, payload: {
 *       applicationId, correlationId, useCase, status, recommendation,
 *       overallScore, report: {...} } }
 * }</pre>
 *
 * <p>This class mirrors that shape exactly so Jackson round-trips it without
 * custom deserializers. {@code report} is kept as a {@link JsonNode} — it is
 * stored verbatim in {@code users.kyc_report_json} and only read by admin UI.
 *
 * <p>Unknown fields are ignored so minor additions on the Python side don't
 * cause poison-message rejects here.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class FindocKycReportReady {

    private String eventId;
    private int schemaVersion;
    private String eventType;
    private Instant occurredAt;
    private Payload payload;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Payload {
        private String applicationId;
        /** findoc-verify copies this from {@code Application.external_id} = "user-{id}". */
        private String correlationId;
        /** {@code "kyc"} for KYC reports; used to filter against mis-routed loan reports. */
        private String useCase;
        private String status;
        /** One of: {@code approve}, {@code reject}, {@code manual_review}, {@code verified}. */
        private String recommendation;
        private Double overallScore;
        private JsonNode report;
        /**
         * KYC-specific pre-extracted summary published by findoc-verify at payload
         * level (not inside {@code report}). Shape: {@code { aadhaar: { last4, valid },
         * pan: { valid }, nameMatchScore, dobMatches }}. Held as a raw JsonNode so
         * schema drift on the Python side doesn't break deserialization.
         */
        private JsonNode kycDetails;
    }
}
