package backend.backend.messaging.consumer.loan;

import backend.backend.configuration.SubbyProperties;
import backend.backend.events.FindocLoanReportReady;
import backend.backend.events.LoanPendingAdminDecision;
import backend.backend.events.LoanRiskRequested;
import backend.backend.messaging.BaseSqsHandler;
import backend.backend.messaging.IdempotencyGuard;
import backend.backend.messaging.NonRetriableException;
import backend.backend.messaging.OutboxEventPublisher;
import backend.backend.messaging.SnsEnvelopeParser;
import backend.backend.events.LoanRiskResult;
import backend.backend.model.LoanApplication;
import backend.backend.model.LoanLifecycleStatus;
import backend.backend.model.PendingLoanEvent;
import backend.backend.model.User;
import backend.backend.repository.LoanApplicationRepository;
import backend.backend.repository.PendingLoanEventRepository;
import backend.backend.repository.UserRepository;
import backend.backend.service.AdminCacheEvictionService;
import backend.backend.service.loan.KycIdentityGuard;
import backend.backend.service.loan.LoanFeatureExtractor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.awspring.cloud.sqs.annotation.SqsListener;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Drains {@code subby-loan-findoc-results}; first-pass moves the loan to DOCS_VERIFIED + LoanRiskRequested, re-eval is advisory and never silently flips a non-DOCS_REJECTED state. */
@Component
public class LoanFindocResultConsumer extends BaseSqsHandler<FindocLoanReportReady> {

    private final LoanApplicationRepository loanRepo;
    private final UserRepository userRepo;
    private final KycIdentityGuard identityGuard;
    private final LoanFeatureExtractor featureExtractor;
    private final OutboxEventPublisher outbox;
    private final SubbyProperties properties;
    private final PendingLoanEventRepository pendingRepo;
    private final LoanRiskResultConsumer riskConsumer;
    private final AdminCacheEvictionService cacheEvictor;

    public LoanFindocResultConsumer(ObjectMapper objectMapper,
                                    IdempotencyGuard idempotencyGuard,
                                    SnsEnvelopeParser envelopeParser,
                                    MeterRegistry meterRegistry,
                                    PlatformTransactionManager txManager,
                                    LoanApplicationRepository loanRepo,
                                    UserRepository userRepo,
                                    KycIdentityGuard identityGuard,
                                    LoanFeatureExtractor featureExtractor,
                                    OutboxEventPublisher outbox,
                                    SubbyProperties properties,
                                    PendingLoanEventRepository pendingRepo,
                                    LoanRiskResultConsumer riskConsumer,
                                    AdminCacheEvictionService cacheEvictor) {
        super(objectMapper, idempotencyGuard, envelopeParser, meterRegistry, txManager);
        this.loanRepo = loanRepo;
        this.userRepo = userRepo;
        this.identityGuard = identityGuard;
        this.featureExtractor = featureExtractor;
        this.outbox = outbox;
        this.properties = properties;
        this.pendingRepo = pendingRepo;
        this.riskConsumer = riskConsumer;
        this.cacheEvictor = cacheEvictor;
    }

    @SqsListener("${subby.queues.loan-findoc-results}")
    public void onMessage(String rawBody) {
        handle(rawBody);
    }

    @Override
    protected Class<FindocLoanReportReady> eventClass() {
        return FindocLoanReportReady.class;
    }

    @Override
    protected void process(FindocLoanReportReady event) {
        FindocLoanReportReady.Payload p = event.getPayload();
        if (p == null) {
            throw new NonRetriableException("FindocLoanReportReady: missing payload");
        }
        if (p.getUseCase() != null && !"loan".equalsIgnoreCase(p.getUseCase())) {
            log.info("loan.result.skip non-loan useCase={} applicationId={}",
                    p.getUseCase(), p.getApplicationId());
            return;
        }
        if (p.getCorrelationId() == null) {
            throw new NonRetriableException("FindocLoanReportReady missing correlationId");
        }

        LoanApplication loan = loanRepo.findByExternalId(p.getCorrelationId())
                .orElseThrow(() -> new NonRetriableException(
                        "LoanApplication not found for correlationId=" + p.getCorrelationId()));

        if (isReeval(p)) {
            handleReeval(loan, p);
            cacheEvictor.evictLoanCaches();
            return;
        }

        if (loan.getLifecycleStatus() != null && loan.getLifecycleStatus().isTerminal()) {
            log.info("loan.result.skip already terminal lifecycle={} loanAppId={}",
                    loan.getLifecycleStatus(), loan.getExternalId());
            return;
        }

        handleFirstPass(loan, p);
        cacheEvictor.evictLoanCaches();
    }

    private static boolean isReeval(FindocLoanReportReady.Payload p) {
        return Boolean.TRUE.equals(p.getReplayed())
                || Boolean.TRUE.equals(p.getOverridden())
                || (p.getRunNumber() != null && p.getRunNumber() > 1);
    }

    private void handleFirstPass(LoanApplication loan, FindocLoanReportReady.Payload p) {
        User user = loan.getUserId() == null ? null
                : userRepo.findById(loan.getUserId()).orElse(null);

        ObjectNode reportRoot = buildReportRoot(p);
        KycIdentityGuard.Report identityReport = identityGuard.evaluate(user, reportRoot);
        identityGuard.annotate(reportRoot, identityReport);

        BigDecimal fraudScore = maxFraudScore(p.getFraudSignals());
        Integer creditScore = deriveCreditScore(reportRoot, user);
        int complianceWarnings = countComplianceWarnings(p.getComplianceChecks());

        if (user != null && creditScore != null && creditScore > 0
                && !creditScore.equals(user.getCreditScore())) {
            user.setCreditScore(creditScore);
            userRepo.save(user);
        }

        loan.setFindocLoanApplicationId(p.getApplicationId());
        loan.setFraudScore(fraudScore);
        try {
            loan.setLoanReportJson(objectMapper.writeValueAsString(reportRoot));
        } catch (Exception e) {
            log.warn("loan.result could not serialize report JSON: {}", e.toString());
        }

        String recommendation = p.getRecommendation() == null ? "" : p.getRecommendation().toLowerCase();

        if (identityReport.isRejected()) {
            rejectLoan(loan, LoanLifecycleStatus.DOCS_REJECTED,
                    identityReport.getSummary(),
                    "layer-3-identity-guard");
            return;
        }
        if ("rejected".equals(recommendation) || "reject".equals(recommendation)) {
            String reason = describeComplianceFailures(p.getComplianceChecks());
            rejectLoan(loan, LoanLifecycleStatus.DOCS_REJECTED, reason, "findoc-recommendation");
            return;
        }

        loan.setLifecycleStatus(LoanLifecycleStatus.DOCS_VERIFIED);
        loanRepo.save(loan);

        Map<String, Object> features = featureExtractor.build(user, reportRoot,
                fraudScore == null ? null : fraudScore.doubleValue(),
                complianceWarnings);

        outbox.publish(properties.topics().riskRequested(),
                LoanRiskRequested.of(loan.getExternalId(),
                        loan.getAmount(), loan.getMonthsRemaining(), features));

        drainPendingEvents(loan);

        log.info("loan.result.docs-verified loanAppId={} recommendation={} fraudScore={} features={}",
                loan.getExternalId(), recommendation, fraudScore, features.keySet());
    }

    private void handleReeval(LoanApplication loan, FindocLoanReportReady.Payload p) {
        LoanLifecycleStatus state = loan.getLifecycleStatus();
        String loanAppId = loan.getExternalId();
        Integer runNumber = p.getRunNumber() == null ? Integer.valueOf(2) : p.getRunNumber();

        if (state == LoanLifecycleStatus.APPROVED
                || state == LoanLifecycleStatus.REJECTED
                || state == LoanLifecycleStatus.FAILED
                || state == LoanLifecycleStatus.PENDING_USER_ACCEPTANCE) {
            log.info("loan.result.re-eval-after-terminal-ignored loanAppId={} state={} runNumber={}",
                    loanAppId, state, runNumber);
            return;
        }

        String reevalRec = mapReevalRecommendation(p.getRecommendation());
        if (reevalRec == null) {
            log.warn("loan.result.re-eval-unknown-recommendation loanAppId={} recommendation={} runNumber={}",
                    loanAppId, p.getRecommendation(), runNumber);
            return;
        }

        String reevalReason = buildReevalReason(p, reevalRec);
        loan.setDocReevalResult(reevalRec);
        loan.setDocReevalReason(reevalReason);
        loan.setDocReevalRunNumber(runNumber);
        loan.setDocReevalAt(Instant.now());

        if (state == LoanLifecycleStatus.DOCS_REJECTED) {
            handleReevalFromDocsRejected(loan, p, reevalRec, reevalReason, runNumber);
        } else if (state == LoanLifecycleStatus.PENDING_ADMIN_DECISION
                || state == LoanLifecycleStatus.MANUAL_REVIEW
                || state == LoanLifecycleStatus.RISK_EVALUATED) {
            handleReevalAfterAdminPage(loan, reevalRec, reevalReason, runNumber);
        } else {
            loanRepo.save(loan);
            log.warn("loan.result.re-eval-unexpected-state loanAppId={} state={} runNumber={}",
                    loanAppId, state, runNumber);
        }
    }

    private void handleReevalFromDocsRejected(LoanApplication loan,
                                              FindocLoanReportReady.Payload p,
                                              String reevalRec,
                                              String reevalReason,
                                              Integer runNumber) {
        String loanAppId = loan.getExternalId();
        switch (reevalRec) {
            case "APPROVE" -> {
                User user = loan.getUserId() == null ? null
                        : userRepo.findById(loan.getUserId()).orElse(null);
                ObjectNode reportRoot = buildReportRoot(p);
                BigDecimal fraudScore = maxFraudScore(p.getFraudSignals());
                int complianceWarnings = countComplianceWarnings(p.getComplianceChecks());

                loan.setFindocLoanApplicationId(p.getApplicationId());
                loan.setFraudScore(fraudScore);
                try {
                    loan.setLoanReportJson(objectMapper.writeValueAsString(reportRoot));
                } catch (Exception e) {
                    log.warn("loan.result.re-eval-serialize-failed loanAppId={} err={}",
                            loanAppId, e.toString());
                }
                loan.setLifecycleStatus(LoanLifecycleStatus.DOCS_VERIFIED);
                loanRepo.save(loan);

                Map<String, Object> features = featureExtractor.build(user, reportRoot,
                        fraudScore == null ? null : fraudScore.doubleValue(),
                        complianceWarnings);
                outbox.publish(properties.topics().riskRequested(),
                        LoanRiskRequested.of(loan.getExternalId(),
                                loan.getAmount(), loan.getMonthsRemaining(), features));
                log.info("loan.result.re-eval-approve-flips-to-risk loanAppId={} runNumber={}",
                        loanAppId, runNumber);
            }
            case "REJECT" -> {
                loanRepo.save(loan);
                log.info("loan.result.re-rejected loanAppId={} runNumber={}", loanAppId, runNumber);
            }
            case "MANUAL_REVIEW" -> {
                loan.setLifecycleStatus(LoanLifecycleStatus.MANUAL_REVIEW);
                loan.setDecisionReason(reevalReason);
                loanRepo.save(loan);
                publishPendingAdminDecision(loan, reevalReason);
                log.info("loan.result.re-eval-manual-review-from-rejected loanAppId={} runNumber={}",
                        loanAppId, runNumber);
            }
            default -> loanRepo.save(loan);
        }
    }

    private void handleReevalAfterAdminPage(LoanApplication loan,
                                            String reevalRec,
                                            String reevalReason,
                                            Integer runNumber) {
        String loanAppId = loan.getExternalId();
        loanRepo.save(loan);
        switch (reevalRec) {
            case "REJECT", "MANUAL_REVIEW" -> {
                publishPendingAdminDecision(loan, reevalReason);
                log.info("loan.result.re-eval-repaging-admin loanAppId={} reevalRec={} runNumber={}",
                        loanAppId, reevalRec, runNumber);
            }
            case "APPROVE" -> log.info(
                    "loan.result.re-approved-noop loanAppId={} runNumber={}", loanAppId, runNumber);
            default -> { }
        }
    }

    private void publishPendingAdminDecision(LoanApplication loan, String reason) {
        String userIdStr = loan.getUserId() == null ? null : String.valueOf(loan.getUserId());
        outbox.publish(properties.topics().notifications(),
                new LoanPendingAdminDecision(null, null, 1, loan.getExternalId(),
                        loan.getExternalId(), userIdStr, reason,
                        loan.getId(), loan.getAmount(), loan.getMonthsRemaining(),
                        loan.getInterestRate()));
    }

    private static String mapReevalRecommendation(String raw) {
        if (raw == null) return null;
        String r = raw.toLowerCase();
        return switch (r) {
            case "approve", "verified" -> "APPROVE";
            case "reject", "rejected" -> "REJECT";
            case "manual_review" -> "MANUAL_REVIEW";
            default -> null;
        };
    }

    private String buildReevalReason(FindocLoanReportReady.Payload p, String reevalRec) {
        String complianceSummary = describeComplianceFailures(p.getComplianceChecks());
        if (complianceSummary != null && !complianceSummary.isBlank()
                && !"Document verification failed".equals(complianceSummary)) {
            return "Doc re-evaluation: " + reevalRec + " — " + complianceSummary;
        }
        return "Doc re-evaluation: " + reevalRec;
    }

    private void drainPendingEvents(LoanApplication loan) {
        List<PendingLoanEvent> buffered = pendingRepo.findByLoanIdOrderByIdAsc(loan.getId());
        if (buffered.isEmpty()) return;

        for (PendingLoanEvent pe : buffered) {
            if (!"LoanRiskResult".equals(pe.getEventType())) {
                log.warn("loan.pending.unknown-type loanId={} pendingId={} eventType={} — discarding",
                        loan.getId(), pe.getId(), pe.getEventType());
                continue;
            }
            LoanRiskResult event;
            try {
                event = objectMapper.readValue(pe.getPayloadJson(), LoanRiskResult.class);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new NonRetriableException("Corrupt buffered LoanRiskResult id=" + pe.getId());
            }
            riskConsumer.applyRiskResult(loan, event.getPayload());
        }
        pendingRepo.deleteAll(buffered);
    }

    private void rejectLoan(LoanApplication loan, LoanLifecycleStatus status,
                            String reason, String source) {
        loan.setLifecycleStatus(status);
        loan.setDecisionReason(reason);
        loanRepo.save(loan);

        log.info("loan.result.docs-rejected-pending-admin loanAppId={} source={} reason={}",
                loan.getExternalId(), source, reason);
    }

    private ObjectNode buildReportRoot(FindocLoanReportReady.Payload p) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("applicationId", p.getApplicationId());
        root.put("recommendation", p.getRecommendation());
        if (p.getOverallScore() != null) root.put("overallScore", p.getOverallScore());
        root.set("complianceChecks", objectMapper.valueToTree(p.getComplianceChecks()));
        root.set("crossDocValidations", objectMapper.valueToTree(p.getCrossDocValidations()));
        root.set("fraudSignals", objectMapper.valueToTree(p.getFraudSignals()));
        if (p.getReport() != null) root.set("report", p.getReport());
        return root;
    }

    private static BigDecimal maxFraudScore(List<FindocLoanReportReady.FraudSignal> signals) {
        if (signals == null || signals.isEmpty()) return null;
        double max = 0.0;
        boolean any = false;
        for (FindocLoanReportReady.FraudSignal s : signals) {
            if (s.getScore() != null) {
                any = true;
                if (s.getScore() > max) max = s.getScore();
            }
        }
        if (!any) return null;
        return BigDecimal.valueOf(Math.min(1.0, Math.max(0.0, max))).setScale(3, RoundingMode.HALF_UP);
    }

    private static Integer deriveCreditScore(JsonNode root, User user) {

        Double n = LoanFeatureExtractor.firstNumber(root,
                "creditScore", "credit_score", "cibilScore", "cibil_score", "bureauScore");
        if (n != null) return n.intValue();
        if (user != null && user.getCreditScore() > 0) return user.getCreditScore();
        return null;
    }

    private static int countComplianceWarnings(List<FindocLoanReportReady.ComplianceItem> items) {
        if (items == null) return 0;
        int c = 0;
        for (FindocLoanReportReady.ComplianceItem it : items) {
            if (it.getStatus() != null && !"pass".equalsIgnoreCase(it.getStatus())) c++;
        }
        return c;
    }

    private static String describeComplianceFailures(List<FindocLoanReportReady.ComplianceItem> items) {
        if (items == null || items.isEmpty()) {
            return "Document verification failed";
        }
        StringBuilder sb = new StringBuilder("Document verification failed: ");
        int shown = 0;
        for (FindocLoanReportReady.ComplianceItem it : items) {
            if ("fail".equalsIgnoreCase(it.getStatus())) {
                if (shown > 0) sb.append("; ");
                sb.append(it.getName());
                if (++shown >= 3) break;
            }
        }
        if (shown == 0) sb.append("overall recommendation was 'rejected'");
        return sb.toString();
    }
}
