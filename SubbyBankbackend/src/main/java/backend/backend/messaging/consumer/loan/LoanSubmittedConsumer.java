package backend.backend.messaging.consumer.loan;

import backend.backend.configuration.SubbyProperties;
import backend.backend.events.LoanApplicationSubmitted;
import backend.backend.events.LoanDecisionMade;
import backend.backend.messaging.BaseSqsHandler;
import backend.backend.messaging.IdempotencyGuard;
import backend.backend.messaging.NonRetriableException;
import backend.backend.messaging.OutboxEventPublisher;
import backend.backend.messaging.SnsEnvelopeParser;
import backend.backend.model.LoanApplication;
import backend.backend.model.LoanLifecycleStatus;
import backend.backend.repository.LoanApplicationRepository;
import backend.backend.service.findoc.FindocSubmitResponse;
import backend.backend.service.findoc.FindocVerifyClient;
import backend.backend.service.findoc.LoanSubmitRequest;
import backend.backend.storage.DocType;
import backend.backend.storage.S3DocumentStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.awspring.cloud.sqs.annotation.SqsListener;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Drains {@code subby-loan-submitted}. For each {@link LoanApplicationSubmitted}:
 *
 * <ol>
 *   <li>Claim idempotency on {@code (eventId, LoanSubmittedConsumer)}.</li>
 *   <li>Load the draft loan row by {@code external_id = loanAppId}.</li>
 *   <li>Skip if the row has already advanced past {@code DRAFT} (redelivery
 *       after a successful prior run).</li>
 *   <li>Stream the 9 loan documents out of S3.</li>
 *   <li>POST them to findoc-verify {@code /api/v1/loan-origination/submit},
 *       passing applicant identity pinned from the KYC record (Layer 1 of
 *       the identity-fraud defense — see {@code LoanFindocResultConsumer}).</li>
 *   <li>Record {@code findoc_loan_application_id} and transition lifecycle
 *       to {@link LoanLifecycleStatus#DOCS_UNDER_REVIEW}.</li>
 * </ol>
 *
 * <p>On findoc-verify 4xx ({@link NonRetriableException}) the row moves to
 * {@link LoanLifecycleStatus#FAILED} and we publish a rejection so the user
 * sees a terminal state instead of an indefinite "under review" spinner.
 * 5xx / timeouts bubble up so SQS redelivers.
 */
@Component
public class LoanSubmittedConsumer extends BaseSqsHandler<LoanApplicationSubmitted> {

    private final LoanApplicationRepository loanRepo;
    private final S3DocumentStorage storage;
    private final FindocVerifyClient findoc;
    private final OutboxEventPublisher outbox;
    private final SubbyProperties properties;

    public LoanSubmittedConsumer(ObjectMapper objectMapper,
                                 IdempotencyGuard idempotencyGuard,
                                 SnsEnvelopeParser envelopeParser,
                                 MeterRegistry meterRegistry,
                                 PlatformTransactionManager txManager,
                                 LoanApplicationRepository loanRepo,
                                 S3DocumentStorage storage,
                                 FindocVerifyClient findoc,
                                 OutboxEventPublisher outbox,
                                 SubbyProperties properties) {
        super(objectMapper, idempotencyGuard, envelopeParser, meterRegistry, txManager);
        this.loanRepo = loanRepo;
        this.storage = storage;
        this.findoc = findoc;
        this.outbox = outbox;
        this.properties = properties;
    }

    @SqsListener("${subby.queues.loan-submitted}")
    public void onMessage(String rawBody) {
        handle(rawBody);
    }

    @Override
    protected Class<LoanApplicationSubmitted> eventClass() {
        return LoanApplicationSubmitted.class;
    }

    @Override
    protected void process(LoanApplicationSubmitted event) {
        if (event.getLoanAppId() == null || event.getS3Keys() == null || event.getS3Keys().isEmpty()) {
            throw new NonRetriableException("LoanApplicationSubmitted missing loanAppId or s3Keys");
        }

        LoanApplication loan = loanRepo.findByExternalId(event.getLoanAppId())
                .orElseThrow(() -> new NonRetriableException(
                        "LoanApplication not found for loanAppId=" + event.getLoanAppId()));

        if (loan.getLifecycleStatus() != LoanLifecycleStatus.DRAFT) {
            log.info("loan.submit.skip already at lifecycle={} loanAppId={}",
                    loan.getLifecycleStatus(), event.getLoanAppId());
            return;
        }

        Map<String, LoanSubmitRequest.DocBytes> docs = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : event.getS3Keys().entrySet()) {
            byte[] bytes = storage.downloadBytes(e.getValue());
            docs.put(e.getKey(), new LoanSubmitRequest.DocBytes(
                    bytes, keyBasename(e.getValue()), contentTypeFor(e.getValue())));
        }

        if (event.getUserId() != null) {
            storage.findLatestKycKey(event.getUserId(), DocType.AADHAAR).ifPresent(key -> {
                byte[] bytes = storage.downloadBytes(key);
                docs.put("aadhaar", new LoanSubmitRequest.DocBytes(
                        bytes, keyBasename(key), contentTypeFor(key)));
            });
            storage.findLatestKycKey(event.getUserId(), DocType.PAN).ifPresent(key -> {
                byte[] bytes = storage.downloadBytes(key);
                docs.put("pan", new LoanSubmitRequest.DocBytes(
                        bytes, keyBasename(key), contentTypeFor(key)));
            });
        }

        LoanSubmitRequest req = new LoanSubmitRequest();
        req.setExternalId(event.getLoanAppId());
        req.setApplicantName(event.getApplicantName());
        req.setEmail(event.getEmail());
        req.setPhone(event.getPhone());
        req.setApplicantDob(event.getApplicantDob());
        req.setDocuments(docs);

        try {
            FindocSubmitResponse resp = findoc.submitLoan(req);
            if (resp == null || resp.getApplicationId() == null) {
                throw new IllegalStateException("findoc-verify /loan-origination/submit returned no applicationId");
            }

            loan.setFindocLoanApplicationId(resp.getApplicationId());
            loan.setLifecycleStatus(LoanLifecycleStatus.DOCS_UNDER_REVIEW);
            if (loan.getSubmittedAt() == null) {
                loan.setSubmittedAt(Instant.now());
            }
            loanRepo.save(loan);

            log.info("loan.submit.forwarded loanAppId={} findocAppId={} status={}",
                    event.getLoanAppId(), resp.getApplicationId(), resp.getStatus());

        } catch (NonRetriableException nre) {

            log.warn("loan.submit.rejected loanAppId={} reason={}", event.getLoanAppId(), nre.getMessage());
            loan.setLifecycleStatus(LoanLifecycleStatus.FAILED);
            loan.setDecisionReason("Document submission rejected by verification service");
            loan.setDecidedAt(Instant.now());
            loanRepo.save(loan);

            outbox.publish(properties.topics().loanEvents(),
                    LoanDecisionMade.fromFindocReject(event.getLoanAppId(), event.getUserId(),
                            "Document submission rejected by verification service"));
            throw nre;
        }
    }

    private static String keyBasename(String key) {
        if (key == null) return null;
        int slash = key.lastIndexOf('/');
        return slash >= 0 ? key.substring(slash + 1) : key;
    }

    /** Best-effort content type from the S3 key's file extension. */
    private static String contentTypeFor(String key) {
        if (key == null) return "application/octet-stream";
        String lower = key.toLowerCase();
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        return "application/octet-stream";
    }
}
