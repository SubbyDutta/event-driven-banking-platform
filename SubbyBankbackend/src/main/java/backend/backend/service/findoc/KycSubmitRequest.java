package backend.backend.service.findoc;

import lombok.Builder;
import lombok.Data;

/**
 * Java-side request payload for {@code POST /api/v1/kyc/submit} on findoc-verify.
 * Assembled by {@code KycSubmittedConsumer} from the KYC event plus document
 * bytes pulled from S3; consumed by {@link FindocVerifyClient#submitKyc}.
 *
 * <p>{@code externalId} must be {@code "user-{userId}"} — findoc-verify uses it
 * as the {@code correlationId} on its outbound report event so
 * {@code KycFindocResultConsumer} can map it back to a user.
 */
@Data
@Builder
public class KycSubmitRequest {
    private String externalId;
    private String applicantName;
    private String email;
    private String phone;
    /** ISO-8601 string. Serialized as a form field to findoc-verify. */
    private String applicantDob;

    private byte[] aadhaarBytes;
    private String aadhaarFilename;
    private String aadhaarContentType;

    private byte[] panBytes;
    private String panFilename;
    private String panContentType;

    private byte[] selfieBytes;
    private String selfieFilename;
    private String selfieContentType;
}
