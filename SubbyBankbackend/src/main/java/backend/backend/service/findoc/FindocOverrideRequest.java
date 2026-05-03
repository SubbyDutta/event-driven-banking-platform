package backend.backend.service.findoc;

import lombok.Builder;
import lombok.Data;

/**
 * Body for {@code POST /api/v1/applications/{id}/override} on findoc-verify.
 * Matches findoc-verify's {@code OverrideRequest} (fields: newRecommendation,
 * reason). {@code newRecommendation} must be one of {@code approve|reject|
 * manual_review|verified}; findoc-verify normalises "approve" to "verified"
 * internally for KYC applications.
 */
@Data
@Builder
public class FindocOverrideRequest {
    private String newRecommendation;
    private String reason;
}
