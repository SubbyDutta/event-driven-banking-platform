package backend.backend.messaging.consumer.loan;

import backend.backend.configuration.SubbyProperties;
import backend.backend.events.LoanDecisionMade;
import backend.backend.events.LoanPendingAdminDecision;
import backend.backend.events.LoanRiskResult;
import backend.backend.messaging.BaseSqsHandler;
import backend.backend.messaging.IdempotencyGuard;
import backend.backend.messaging.NonRetriableException;
import backend.backend.messaging.OutboxEventPublisher;
import backend.backend.messaging.SnsEnvelopeParser;
import backend.backend.model.LoanApplication;
import backend.backend.model.LoanLifecycleStatus;
import backend.backend.model.PendingLoanEvent;
import backend.backend.repository.LoanApplicationRepository;
import backend.backend.repository.PendingLoanEventRepository;
import backend.backend.service.AdminCacheEvictionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.awspring.cloud.sqs.annotation.SqsListener;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Drains {@code subby-loan-risk-results}. Takes a {@link LoanRiskResult} from
 * SubbyPythonLoan, maps {@code risk_band} → interest rate via the locked
 * ladder (A=10.5, B=12.0, C=14.5, D=17.0, E=reject), writes the decision
 * fields on the loan row, and publishes a {@link LoanDecisionMade} so
 * {@code LoanDecisionConsumer} applies the approval side-effects.
 *
 * <p>Band E is a hard reject regardless of the ML's {@code decision} verdict:
 * ≥35% probability of default is above the product's risk appetite.
 */
@Component
public class LoanRiskResultConsumer extends BaseSqsHandler<LoanRiskResult> {

    private final LoanApplicationRepository loanRepo;
    private final OutboxEventPublisher outbox;
    private final SubbyProperties properties;
    private final PendingLoanEventRepository pendingRepo;
    private final AdminCacheEvictionService cacheEvictor;

    public LoanRiskResultConsumer(ObjectMapper objectMapper,
                                  IdempotencyGuard idempotencyGuard,
                                  SnsEnvelopeParser envelopeParser,
                                  MeterRegistry meterRegistry,
                                  PlatformTransactionManager txManager,
                                  LoanApplicationRepository loanRepo,
                                  OutboxEventPublisher outbox,
                                  SubbyProperties properties,
                                  PendingLoanEventRepository pendingRepo,
                                  AdminCacheEvictionService cacheEvictor) {
        super(objectMapper, idempotencyGuard, envelopeParser, meterRegistry, txManager);
        this.loanRepo = loanRepo;
        this.outbox = outbox;
        this.properties = properties;
        this.pendingRepo = pendingRepo;
        this.cacheEvictor = cacheEvictor;
    }

    @SqsListener("${subby.queues.loan-risk-results}")
    public void onMessage(String rawBody) {
        handle(rawBody);
    }

    @Override
    protected Class<LoanRiskResult> eventClass() {
        return LoanRiskResult.class;
    }

    @Override
    protected void process(LoanRiskResult event) {
        LoanRiskResult.Payload p = event.getPayload();
        if (p == null || p.getLoanAppId() == null) {
            throw new NonRetriableException("LoanRiskResult missing payload or loanAppId");
        }

        LoanApplication loan = loanRepo.findByExternalId(p.getLoanAppId())
                .orElseThrow(() -> new NonRetriableException(
                        "LoanApplication not found for loanAppId=" + p.getLoanAppId()));

        if (loan.getLifecycleStatus() != null && loan.getLifecycleStatus().isTerminal()) {
            log.info("loan.risk.skip already terminal lifecycle={} loanAppId={}",
                    loan.getLifecycleStatus(), p.getLoanAppId());
            return;
        }
        if (loan.getLifecycleStatus() != LoanLifecycleStatus.DOCS_VERIFIED) {
            try {
                PendingLoanEvent pending = new PendingLoanEvent();
                pending.setLoanId(loan.getId());
                pending.setEventType("LoanRiskResult");
                pending.setPayloadJson(objectMapper.writeValueAsString(event));
                pendingRepo.save(pending);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new NonRetriableException("Failed to serialize pending LoanRiskResult: " + e.getMessage());
            }
            log.info("loan.risk.buffered awaiting DOCS_VERIFIED loanAppId={} currentLifecycle={}",
                    p.getLoanAppId(), loan.getLifecycleStatus());
            return;
        }

        applyRiskResult(loan, p);
    }

    void applyRiskResult(LoanApplication loan, LoanRiskResult.Payload p) {
        String band = p.getRisk_band() == null ? null : p.getRisk_band().toUpperCase();
        loan.setRiskBand(band);
        if (p.getProbability_of_default() != null) {
            loan.setRiskProbability(BigDecimal.valueOf(p.getProbability_of_default())
                    .setScale(3, RoundingMode.HALF_UP));
        }
        loan.setMlRecommendation(p.getDecision() == null ? null : p.getDecision().toLowerCase());

        String decision = resolveDecision(p.getDecision(), band);
        String reason = buildReason(p, decision, band);
        if ("APPROVED".equals(decision) && findocRecommendedManualReview(loan)) {
            decision = "MANUAL_REVIEW";
            reason = reason + " (manual_review at docs layer)";
        }
        BigDecimal rate = interestRateFor(band);
        if (decision.equals("APPROVED")) {
            loan.setInterestRate(rate);
        }
        loan.setDecisionReason(reason);

        String userIdStr = loan.getUserId() == null ? null : String.valueOf(loan.getUserId());

        if ("APPROVED".equals(decision)) {
            loan.setLifecycleStatus(LoanLifecycleStatus.PENDING_ADMIN_DECISION);
            loanRepo.save(loan);
            outbox.publish(properties.topics().notifications(),
                    new LoanPendingAdminDecision(null, null, 1, loan.getExternalId(),
                            loan.getExternalId(), userIdStr,
                            reason, loan.getId(), loan.getAmount(), 6, rate));
            log.info("loan.risk.pending-admin loanAppId={} band={} pod={} rate={}",
                    p.getLoanAppId(), band, p.getProbability_of_default(), rate);
            cacheEvictor.evictLoanCaches();
            return;
        }

        loan.setLifecycleStatus(LoanLifecycleStatus.RISK_EVALUATED);
        loanRepo.save(loan);

        outbox.publish(properties.topics().loanEvents(),
                LoanDecisionMade.fromRisk(loan.getExternalId(), userIdStr, decision, reason, band, rate));

        log.info("loan.risk.evaluated loanAppId={} band={} pod={} decision={} rate={}",
                p.getLoanAppId(), band, p.getProbability_of_default(), decision, rate);
        cacheEvictor.evictLoanCaches();
    }

    /**
     * Risk band → annual interest rate ladder:
     * <pre>
     *   A  →  10.5%
     *   B  →  12.0%
     *   C  →  14.5%
     *   D  →  17.0%
     *   E  →  (hard reject, no rate)
     * </pre>
     */
    static BigDecimal interestRateFor(String band) {
        if (band == null) return null;
        return switch (band) {
            case "A" -> new BigDecimal("10.50");
            case "B" -> new BigDecimal("12.00");
            case "C" -> new BigDecimal("14.50");
            case "D" -> new BigDecimal("17.00");
            default -> null;
        };
    }

    /**
     * Policy table: the ML {@code decision} must agree with {@code risk_band}
     * to approve. Band E is a hard reject even if the model voted "approve",
     * and any "manual_review" signal wins.
     */
    static String resolveDecision(String mlDecision, String band) {
        String ml = mlDecision == null ? "" : mlDecision.toLowerCase();
        if ("manual_review".equals(ml)) return "MANUAL_REVIEW";
        if ("E".equals(band)) return "REJECTED";
        if ("reject".equals(ml)) return "REJECTED";
        if ("approve".equals(ml) && (band != null && "ABCD".contains(band))) return "APPROVED";
        return "MANUAL_REVIEW";
    }

    private boolean findocRecommendedManualReview(LoanApplication loan) {
        String json = loan.getLoanReportJson();
        if (json == null || json.isBlank()) return false;
        try {
            JsonNode rec = objectMapper.readTree(json).get("recommendation");
            return rec != null && !rec.isNull() && "manual_review".equalsIgnoreCase(rec.asText());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return false;
        }
    }

    private static String buildReason(LoanRiskResult.Payload p, String decision, String band) {
        String base = "Risk band " + band + ", P(default)=" + p.getProbability_of_default()
                + ", model=" + p.getModelVersion();
        String explicit = p.getReason();
        return (explicit == null || explicit.isBlank()) ? base : (base + " — " + explicit);
    }
}
