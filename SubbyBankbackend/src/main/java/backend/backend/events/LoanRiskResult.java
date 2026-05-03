package backend.backend.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.time.Instant;
import java.util.List;

/**
 * Inbound envelope published by SubbyPythonLoan on {@code subby-risk-result}
 * SNS topic. Reaches Java via {@code subby-loan-risk-results} SQS queue.
 *
 * <p>Wire shape from {@code SubbyPythonLoan/src/messaging/schemas.py}:
 * <pre>{@code
 *   { eventId, schemaVersion, occurredAt, eventType: "LoanRiskResult",
 *     correlationId, payload: {
 *       loanAppId, decision,                     // approve | reject | manual_review
 *       probability_of_default,                  // 0..1
 *       risk_band,                               // A..E
 *       modelVersion, featuresUsed: [...], reason } }
 * }</pre>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class LoanRiskResult {

    private String eventId;
    private int schemaVersion;
    private String eventType;
    private Instant occurredAt;
    private String correlationId;
    private Payload payload;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Payload {
        private String loanAppId;
        /** {@code approve | reject | manual_review}. */
        private String decision;
        /** Wire field is snake_case — bind explicitly. */
        private Double probability_of_default;
        /** A..E. */
        private String risk_band;
        private String modelVersion;
        private List<String> featuresUsed;
        private String reason;
    }
}
