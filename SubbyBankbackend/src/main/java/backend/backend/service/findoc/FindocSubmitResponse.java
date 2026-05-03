package backend.backend.service.findoc;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * Response from {@code POST /api/v1/kyc/submit} (or {@code /loan-origination/submit}).
 * Mirrors findoc-verify's {@code SubmitResponse} Pydantic model. We only care
 * about {@code applicationId} + {@code status}; extra fields are ignored.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class FindocSubmitResponse {
    private String applicationId;
    private String externalId;
    private String useCase;
    private String status;
    private int documentsAccepted;
    private Boolean idempotentReplay;
}
