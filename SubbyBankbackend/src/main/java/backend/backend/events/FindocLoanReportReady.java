package backend.backend.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.Instant;
import java.util.List;

/**
 * Inbound envelope published by findoc-verify on the {@code findoc-loan-report-ready}
 * SNS topic. Reaches Java via the {@code subby-loan-findoc-results} SQS queue.
 *
 * <p>Mirrors {@link FindocKycReportReady} but for the loan use-case. Payload
 * shape is built by {@code findoc-verify/src/workers/result_publisher.py}:
 * <pre>{@code
 *   { eventId, schemaVersion, eventType, occurredAt, payload: {
 *       applicationId, correlationId, useCase: "loan", status,
 *       recommendation,           // "verified" | "rejected" | "manual_review"
 *       overallScore,
 *       complianceChecks: [{name, status: "pass"|"fail"|"warn", details}],
 *       crossDocValidations: [{ruleName, status, details}],
 *       fraudSignals: [{signalName, severity, score, details}],
 *       report: { ... full VerificationReport.report_json ... },
 *       override?: { ... }
 *     }
 *   }
 * }</pre>
 *
 * <p>{@code report} and nested JSON collections stay as {@link JsonNode} — the
 * identity guard and feature extractor walk them generically rather than
 * binding to an evolving schema.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class FindocLoanReportReady {

    private String eventId;
    private int schemaVersion;
    private String eventType;
    private Instant occurredAt;
    private Payload payload;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Payload {
        private String applicationId;
        /** findoc-verify copies this from {@code Application.external_id} = our loanAppId. */
        private String correlationId;
        /** {@code "loan"}. Guarded against mis-routed KYC reports. */
        private String useCase;
        private String status;
        /** One of: {@code verified}, {@code rejected}, {@code manual_review}. */
        private String recommendation;
        private Double overallScore;
        private List<ComplianceItem> complianceChecks;
        private List<CrossDocItem> crossDocValidations;
        private List<FraudSignal> fraudSignals;
        /** Full verification_report.report_json. Extracted fields live here. */
        private JsonNode report;

        /** True when this report-ready event was emitted from an admin replay (run > 1). */
        private Boolean replayed;
        /** True when the recommendation was changed via the findoc-side admin override. */
        private Boolean overridden;
        /** 1 on first pass, 2+ on each replay. Null is treated as 1. */
        private Integer runNumber;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ComplianceItem {
        private String name;
        /** {@code pass | fail | warn}. */
        private String status;
        private JsonNode details;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CrossDocItem {
        private String ruleName;
        private String status;
        private JsonNode details;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FraudSignal {
        private String signalName;
        /** {@code low | medium | high | critical}. */
        private String severity;
        private Double score;
        private JsonNode details;
    }
}
