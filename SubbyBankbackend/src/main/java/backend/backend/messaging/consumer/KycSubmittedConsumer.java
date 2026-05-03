package backend.backend.messaging.consumer;

import backend.backend.events.KycDocsVerified;
import backend.backend.events.KycSubmitted;
import backend.backend.configuration.SubbyProperties;
import backend.backend.messaging.BaseSqsHandler;
import backend.backend.messaging.IdempotencyGuard;
import backend.backend.messaging.NonRetriableException;
import backend.backend.messaging.OutboxEventPublisher;
import backend.backend.messaging.SnsEnvelopeParser;
import backend.backend.model.KycStatus;
import backend.backend.model.User;
import backend.backend.repository.UserRepository;
import backend.backend.service.findoc.FindocSubmitResponse;
import backend.backend.service.findoc.FindocVerifyClient;
import backend.backend.service.findoc.KycSubmitRequest;
import backend.backend.storage.S3DocumentStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.awspring.cloud.sqs.annotation.SqsListener;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Map;

/**
 * Drains {@code subby-kyc-submitted} (filtered to {@code eventType=KycSubmitted}
 * at the subscription). For each event:
 *
 * <ol>
 *   <li>Claim idempotency on {@code (eventId, KycSubmittedConsumer)}.</li>
 *   <li>Download the staged documents from S3.</li>
 *   <li>POST them to findoc-verify {@code /api/v1/kyc/submit}.</li>
 *   <li>Store the returned {@code applicationId} on the user and transition
 *       status to {@link KycStatus#KYC_DOCS_UNDER_REVIEW}.</li>
 *   <li>Stage a {@link KycDocsVerified} outbox event for observers.</li>
 * </ol>
 *
 * <p>Error handling splits by exception type:
 * <ul>
 *   <li>{@code NonRetriableException} — fast-track to DLQ. Happens on 4xx from
 *       findoc-verify (bad doc set, unauthorised, conflict).</li>
 *   <li>Anything else (RetriableException, S3 errors, DB errors) — re-thrown so
 *       SQS redelivers up to the redrive policy's {@code maxReceiveCount}.</li>
 * </ul>
 */
@Component
public class KycSubmittedConsumer extends BaseSqsHandler<KycSubmitted> {

    private final UserRepository userRepository;
    private final S3DocumentStorage storage;
    private final FindocVerifyClient findoc;
    private final OutboxEventPublisher outbox;
    private final SubbyProperties properties;

    public KycSubmittedConsumer(ObjectMapper objectMapper,
                                IdempotencyGuard idempotencyGuard,
                                SnsEnvelopeParser envelopeParser,
                                MeterRegistry meterRegistry,
                                PlatformTransactionManager txManager,
                                UserRepository userRepository,
                                S3DocumentStorage storage,
                                FindocVerifyClient findoc,
                                OutboxEventPublisher outbox,
                                SubbyProperties properties) {
        super(objectMapper, idempotencyGuard, envelopeParser, meterRegistry, txManager);
        this.userRepository = userRepository;
        this.storage = storage;
        this.findoc = findoc;
        this.outbox = outbox;
        this.properties = properties;
    }

    @SqsListener("${subby.queues.kyc-submitted}")
    public void onMessage(String rawBody) {
        handle(rawBody);
    }

    @Override
    protected Class<KycSubmitted> eventClass() {
        return KycSubmitted.class;
    }

    @Override
    protected void process(KycSubmitted event) {
        if (event.getUserId() == null || event.getS3Keys() == null) {
            throw new NonRetriableException("KycSubmitted missing userId or s3Keys");
        }
        Map<String, String> keys = event.getS3Keys();
        if (!keys.containsKey("aadhaar") || !keys.containsKey("pan")) {
            throw new NonRetriableException(
                    "KycSubmitted s3Keys missing required aadhaar/pan: " + keys.keySet());
        }

        long userId;
        try {
            userId = Long.parseLong(event.getUserId());
        } catch (NumberFormatException e) {
            throw new NonRetriableException("KycSubmitted.userId is not numeric: " + event.getUserId(), e);
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NonRetriableException(
                        "User not found for KycSubmitted: userId=" + userId));

        if (user.getKycStatus() != null
                && user.getKycStatus() != KycStatus.NONE
                && user.getKycStatus() != KycStatus.KYC_SUBMITTED) {
            log.info("kyc.submit.skip user already at status={} userId={}",
                    user.getKycStatus(), userId);
            return;
        }

        byte[] aadhaarBytes = storage.downloadBytes(keys.get("aadhaar"));
        byte[] panBytes = storage.downloadBytes(keys.get("pan"));
        byte[] selfieBytes = keys.containsKey("selfie") ? storage.downloadBytes(keys.get("selfie")) : null;

        KycSubmitRequest req = KycSubmitRequest.builder()
                .externalId("user-" + userId)
                .applicantName(event.getApplicantName())
                .email(event.getEmail())
                .phone(event.getPhone())
                .applicantDob(event.getApplicantDob())
                .aadhaarBytes(aadhaarBytes)
                .aadhaarFilename(keyBasename(keys.get("aadhaar")))
                .panBytes(panBytes)
                .panFilename(keyBasename(keys.get("pan")))
                .selfieBytes(selfieBytes)
                .selfieFilename(selfieBytes == null ? null : keyBasename(keys.get("selfie")))
                .build();

        FindocSubmitResponse resp = findoc.submitKyc(req);
        if (resp == null || resp.getApplicationId() == null) {

            throw new IllegalStateException("findoc-verify /kyc/submit returned no applicationId");
        }

        user.setFindocKycApplicationId(resp.getApplicationId());
        user.setKycStatus(KycStatus.KYC_DOCS_UNDER_REVIEW);
        userRepository.save(user);

        outbox.publish(properties.topics().kycEvents(),
                KycDocsVerified.forUser(String.valueOf(userId), resp.getApplicationId()));

        log.info("kyc.submit.forwarded userId={} findocAppId={} status={}",
                userId, resp.getApplicationId(), resp.getStatus());
    }

    private static String keyBasename(String key) {
        if (key == null) return null;
        int slash = key.lastIndexOf('/');
        return slash >= 0 ? key.substring(slash + 1) : key;
    }
}
