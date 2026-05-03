package backend.backend.messaging.consumer;

import backend.backend.configuration.SubbyProperties;
import backend.backend.events.AdminKycReviewNeeded;
import backend.backend.events.FindocKycReportReady;
import backend.backend.events.KycDecisionMade;
import backend.backend.events.KycFinalized;
import backend.backend.messaging.BaseSqsHandler;
import backend.backend.messaging.IdempotencyGuard;
import backend.backend.messaging.NonRetriableException;
import backend.backend.messaging.OutboxEventPublisher;
import backend.backend.messaging.SnsEnvelopeParser;
import backend.backend.model.KycStatus;
import backend.backend.model.User;
import backend.backend.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.awspring.cloud.sqs.annotation.SqsListener;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;

/**
 * Drains {@code subby-kyc-findoc-results} — the queue subscribed to findoc-verify's
 * {@code findoc-kyc-report-ready} SNS topic. This is the critical cross-service
 * handler that decides a user's KYC outcome.
 *
 * <p>Responsibilities:
 * <ol>
 *   <li>Look up the user via the {@code correlationId} (format: {@code user-{id}}).</li>
 *   <li>Persist the raw report JSON on the user for admin / audit.</li>
 *   <li>Map findoc's {@code recommendation} (approve/verified → APPROVED,
 *       reject → REJECTED, anything else → MANUAL_REVIEW) to our enum.</li>
 *   <li>If APPROVED: flip {@code account_active=true}.</li>
 *   <li>Try to extract and encrypt the Aadhaar / PAN number from the report.</li>
 *   <li>Stage a {@link KycDecisionMade} outbox event — that is what fans out to
 *       the email / SMS / audit consumers via the {@code subby-kyc-events} topic.</li>
 *   <li>If APPROVED, stage a {@link KycFinalized} event as well so dependent
 *       flows (welcome bonus, etc.) can react to the "account is live" moment.</li>
 * </ol>
 */
@Component
public class KycFindocResultConsumer extends BaseSqsHandler<FindocKycReportReady> {

    private final UserRepository userRepository;
    private final OutboxEventPublisher outbox;
    private final SubbyProperties properties;

    public KycFindocResultConsumer(ObjectMapper objectMapper,
                                   IdempotencyGuard idempotencyGuard,
                                   SnsEnvelopeParser envelopeParser,
                                   MeterRegistry meterRegistry,
                                   PlatformTransactionManager txManager,
                                   UserRepository userRepository,
                                   OutboxEventPublisher outbox,
                                   SubbyProperties properties) {
        super(objectMapper, idempotencyGuard, envelopeParser, meterRegistry, txManager);
        this.userRepository = userRepository;
        this.outbox = outbox;
        this.properties = properties;
    }

    @SqsListener("${subby.queues.kyc-findoc-results}")
    public void onMessage(String rawBody) {
        handle(rawBody);
    }

    @Override
    protected Class<FindocKycReportReady> eventClass() {
        return FindocKycReportReady.class;
    }

    @Override
    protected void process(FindocKycReportReady event) {
        FindocKycReportReady.Payload p = event.getPayload();
        if (p == null) {
            throw new NonRetriableException("FindocKycReportReady: missing payload");
        }

        if (p.getUseCase() != null && !"kyc".equalsIgnoreCase(p.getUseCase())) {
            log.info("kyc.result.skip non-KYC useCase={} applicationId={}",
                    p.getUseCase(), p.getApplicationId());
            return;
        }

        Long userId = parseUserId(p.getCorrelationId());
        if (userId == null) {
            throw new NonRetriableException(
                    "FindocKycReportReady: correlationId is not 'user-{id}': " + p.getCorrelationId());
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NonRetriableException(
                        "User not found for FindocKycReportReady: userId=" + userId));

        if (p.getReport() != null) {
            try {
                user.setKycReportJson(objectMapper.writeValueAsString(p.getReport()));
            } catch (Exception e) {
                log.warn("kyc.result could not serialize report — storing null. userId={} err={}",
                        userId, e.toString());
            }
        }
        user.setKycDecidedAt(Instant.now());
        if (p.getApplicationId() != null) {
            user.setFindocKycApplicationId(p.getApplicationId());
        }

        KycStatus newStatus = mapRecommendation(p.getRecommendation());
        String decisionReason = extractReason(p.getReport(), newStatus);

        if (newStatus != KycStatus.KYC_REJECTED) {
            String aadhaar = extractAadhaar(p.getKycDetails(), p.getReport());
            String pan = extractPan(p.getKycDetails(), p.getReport());

            String dupReason = detectDuplicate(aadhaar, pan, userId);
            if (dupReason != null) {
                newStatus = KycStatus.KYC_REJECTED;
                decisionReason = dupReason;

            } else if (aadhaar != null || pan != null) {
                user.setAadhaarNumber(aadhaar);
                user.setPanNumber(pan);
            }
        }

        user.setKycStatus(newStatus);
        user.setKycDecisionReason(decisionReason);

        if (newStatus == KycStatus.KYC_APPROVED) {
            user.setAccountActive(true);
        } else {

            user.setAccountActive(false);
        }

        try {
            userRepository.save(user);
            userRepository.flush();
        } catch (DataIntegrityViolationException dup) {

            log.warn("kyc.result.duplicate_race userId={} err={}", userId, dup.getMostSpecificCause().getMessage());
            user.setAadhaarNumber(null);
            user.setPanNumber(null);
            user.setKycStatus(KycStatus.KYC_REJECTED);
            user.setAccountActive(false);
            user.setKycDecisionReason("Aadhaar or PAN is already linked to another account");
            userRepository.save(user);
            newStatus = KycStatus.KYC_REJECTED;
            decisionReason = user.getKycDecisionReason();
        }

        String userIdStr = String.valueOf(userId);
        outbox.publish(properties.topics().kycEvents(),
                KycDecisionMade.fromPipeline(userIdStr, newStatus.name(), decisionReason));
        if (newStatus == KycStatus.KYC_APPROVED) {
            outbox.publish(properties.topics().kycEvents(),
                    KycFinalized.forUser(userIdStr, true));
        }
        if (newStatus == KycStatus.KYC_MANUAL_REVIEW) {
            outbox.publish(properties.topics().notifications(),
                    AdminKycReviewNeeded.forUser(
                            userIdStr,
                            user.getUsername(),
                            user.getEmail(),
                            p.getApplicationId(),
                            decisionReason));
        }

        log.info("kyc.result.applied userId={} findocAppId={} recommendation={} status={} accountActive={} reason={}",
                userId, p.getApplicationId(), p.getRecommendation(), newStatus, user.isAccountActive(), decisionReason);
    }

    private static Long parseUserId(String correlationId) {
        if (correlationId == null || !correlationId.startsWith("user-")) return null;
        try {
            return Long.parseLong(correlationId.substring("user-".length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static KycStatus mapRecommendation(String recommendation) {
        if (recommendation == null) return KycStatus.KYC_MANUAL_REVIEW;
        return switch (recommendation.toLowerCase()) {
            case "approve", "verified" -> KycStatus.KYC_APPROVED;
            case "reject" -> KycStatus.KYC_REJECTED;
            default -> KycStatus.KYC_MANUAL_REVIEW;
        };
    }

    private static String extractReason(JsonNode report, KycStatus status) {
        if (report == null || report.isNull()) return status.name();

        for (String key : new String[]{"reason", "rationale", "summary"}) {
            JsonNode v = report.get(key);
            if (v != null && v.isTextual() && !v.asText().isBlank()) {
                return v.asText();
            }
        }
        JsonNode compliance = report.get("complianceSummary");
        if (compliance == null) compliance = report.get("compliance");
        if (compliance != null && compliance.isArray()) {
            for (JsonNode check : compliance) {
                JsonNode checkStatus = check.get("status");
                JsonNode checkDetails = check.get("details");
                if (checkStatus != null && "failed".equalsIgnoreCase(checkStatus.asText())
                        && checkDetails != null && !checkDetails.isNull()) {
                    return checkDetails.asText();
                }
            }
        }
        return status.name();
    }

    /**
     * Extract the Aadhaar number from the findoc-verify envelope.
     * Preferred path: {@code payload.kycDetails.aadhaar.number} (findoc current).
     * Fallbacks: older nested {@code payload.report.kycDetails.aadhaar.number},
     * {@code payload.report.aadhaarNumber}, or masked {@code last4} as a
     * last resort. Null means "not present" — leave the column untouched.
     */
    private static String extractAadhaar(JsonNode kycDetails, JsonNode report) {
        if (kycDetails != null && !kycDetails.isNull()) {
            String v = firstTextAt(kycDetails,
                    new String[]{"aadhaar", "number"},
                    new String[]{"aadhaar", "last4"});
            if (v != null) return v;
        }
        if (report != null && !report.isNull()) {
            return firstTextAt(report,
                    new String[]{"kycDetails", "aadhaar", "number"},
                    new String[]{"aadhaarNumber"},
                    new String[]{"extracted", "aadhaarNumber"},
                    new String[]{"kycDetails", "aadhaar", "last4"});
        }
        return null;
    }

    /** See {@link #extractAadhaar(JsonNode, JsonNode)}. */
    private static String extractPan(JsonNode kycDetails, JsonNode report) {
        if (kycDetails != null && !kycDetails.isNull()) {
            String v = firstTextAt(kycDetails,
                    new String[]{"pan", "number"},
                    new String[]{"pan", "value"});
            if (v != null) return v;
        }
        if (report != null && !report.isNull()) {
            return firstTextAt(report,
                    new String[]{"kycDetails", "pan", "number"},
                    new String[]{"panNumber"},
                    new String[]{"extracted", "panNumber"});
        }
        return null;
    }

    /**
     * Check whether {@code aadhaar} or {@code pan} are already linked to
     * another user. PiiConverter is deterministic, so the derived-query
     * {@code existsByXxxAndIdNot} matches ciphertext-to-ciphertext. Returns
     * the decision reason when a duplicate is found, null when the record
     * is clear. Never returns "false positive" — a match is always real.
     */
    private String detectDuplicate(String aadhaar, String pan, Long currentUserId) {
        if (aadhaar != null && userRepository.existsByAadhaarNumberAndIdNot(aadhaar, currentUserId)) {
            return "Aadhaar number is already linked to another account";
        }
        if (pan != null && userRepository.existsByPanNumberAndIdNot(pan, currentUserId)) {
            return "PAN is already linked to another account";
        }
        return null;
    }

    private static String firstTextAt(JsonNode root, String[]... paths) {
        for (String[] path : paths) {
            JsonNode node = root;
            for (String seg : path) {
                if (node == null || node.isNull()) break;
                node = node.get(seg);
            }
            if (node != null && node.isTextual() && !node.asText().isBlank()) {
                return node.asText();
            }
        }
        return null;
    }
}
