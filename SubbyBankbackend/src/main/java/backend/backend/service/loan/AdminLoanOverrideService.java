package backend.backend.service.loan;

import backend.backend.configuration.SubbyProperties;
import backend.backend.events.LoanDecisionMade;
import backend.backend.messaging.OutboxEventPublisher;
import backend.backend.model.LoanApplication;
import backend.backend.model.LoanDecisionOverride;
import backend.backend.repository.LoanApplicationRepository;
import backend.backend.repository.LoanDecisionOverrideRepository;
import backend.backend.repository.UserRepository;
import backend.backend.service.AdminCacheEvictionService;
import backend.backend.service.CachedLists;
import backend.backend.service.findoc.FindocVerifyClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class AdminLoanOverrideService {

    private static final Logger log = LoggerFactory.getLogger(AdminLoanOverrideService.class);
    private static final BigDecimal MIN_RATE = BigDecimal.ZERO;
    private static final BigDecimal MAX_RATE = new BigDecimal("50");

    private final LoanApplicationRepository loanRepo;
    private final LoanDecisionOverrideRepository overrideRepo;
    private final OutboxEventPublisher outbox;
    private final SubbyProperties properties;
    private final LoanFinalizationService finalizer;
    private final AdminCacheEvictionService cacheEvictor;
    private final UserRepository userRepo;
    private final ObjectMapper objectMapper;
    private final FindocVerifyClient findoc;

    public AdminLoanOverrideService(LoanApplicationRepository loanRepo,
                                    LoanDecisionOverrideRepository overrideRepo,
                                    OutboxEventPublisher outbox,
                                    SubbyProperties properties,
                                    LoanFinalizationService finalizer,
                                    AdminCacheEvictionService cacheEvictor,
                                    UserRepository userRepo,
                                    ObjectMapper objectMapper,
                                    FindocVerifyClient findoc) {
        this.loanRepo = loanRepo;
        this.overrideRepo = overrideRepo;
        this.outbox = outbox;
        this.properties = properties;
        this.finalizer = finalizer;
        this.cacheEvictor = cacheEvictor;
        this.userRepo = userRepo;
        this.objectMapper = objectMapper;
        this.findoc = findoc;
    }

    public static class Result {
        public final boolean notFound;
        public final String validationError;
        public final boolean idempotent;
        public final String originalDecision;
        public final String newDecision;
        public final String overriddenBy;

        private Result(boolean notFound, String validationError, boolean idempotent,
                       String originalDecision, String newDecision, String overriddenBy) {
            this.notFound = notFound;
            this.validationError = validationError;
            this.idempotent = idempotent;
            this.originalDecision = originalDecision;
            this.newDecision = newDecision;
            this.overriddenBy = overriddenBy;
        }

        public static Result notFound() { return new Result(true, null, false, null, null, null); }
        public static Result invalid(String error) { return new Result(false, error, false, null, null, null); }
        public static Result idempotent(String original, String decision, String admin) {
            return new Result(false, null, true, original, decision, admin);
        }
        public static Result applied(String original, String decision, String admin) {
            return new Result(false, null, false, original, decision, admin);
        }

        public Map<String, Object> toResponseBody(String loanAppId) {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("loanAppId", loanAppId);
            resp.put("originalDecision", originalDecision);
            resp.put("newDecision", newDecision);
            resp.put("overriddenBy", overriddenBy);
            if (idempotent) resp.put("idempotent", true);
            return resp;
        }
    }

    @Transactional
    public Result override(String loanAppId, String decisionRaw, String reason,
                           BigDecimal interestRateOverride, String adminName) {
        if (reason == null || reason.isBlank()) {
            return Result.invalid("reason is required");
        }
        String decision = normalizeDecision(decisionRaw);
        if (decision == null) {
            return Result.invalid("decision must be APPROVE or REJECT");
        }

        LoanApplication loan = loanRepo.findByExternalId(loanAppId).orElse(null);
        if (loan == null) return Result.notFound();

        BigDecimal rate = null;
        if ("APPROVED".equals(decision)) {
            String rateError = validateInterestRate(interestRateOverride);
            if (rateError != null) return Result.invalid(rateError);
            rate = interestRateOverride;
        }

        String originalDecision = loan.getLifecycleStatus() == null ? null : loan.getLifecycleStatus().name();

        Optional<LoanDecisionOverride> prior = overrideRepo
                .findFirstByLoanApplicationIdAndOverriddenByAndNewDecisionOrderByIdDesc(
                        loan.getId(), adminName, decision);
        boolean alreadyApplied = prior.isPresent()
                && loan.getLifecycleStatus() != null
                && loan.getLifecycleStatus().name().equals(decision);
        if (alreadyApplied) {
            LoanDecisionOverride existing = prior.get();
            return Result.idempotent(existing.getOriginalDecision(), existing.getNewDecision(), existing.getOverriddenBy());
        }

        LoanDecisionOverride row = new LoanDecisionOverride();
        row.setLoanApplicationId(loan.getId());
        row.setOriginalDecision(originalDecision);
        row.setNewDecision(decision);
        row.setReason(reason);
        row.setOverriddenBy(adminName);
        overrideRepo.save(row);

        finalizer.finalize(loan, decision, reason, rate, "admin:" + adminName);
        cacheEvictor.evictLoanCaches();

        String userIdStr = loan.getUserId() == null ? null : String.valueOf(loan.getUserId());
        outbox.publish(properties.topics().loanEvents(),
                LoanDecisionMade.fromAdminOverride(loan.getExternalId(), userIdStr, decision,
                        reason, rate, adminName));

        log.info("admin.loan.override loanAppId={} {} -> {} by={} rate={}",
                loanAppId, originalDecision, decision, adminName, rate);

        return Result.applied(originalDecision, decision, adminName);
    }

    private static String normalizeDecision(String raw) {
        if (raw == null) return null;
        String d = raw.trim().toUpperCase();
        return switch (d) {
            case "APPROVE", "APPROVED" -> "APPROVED";
            case "REJECT", "REJECTED" -> "REJECTED";
            default -> null;
        };
    }

    private static String validateInterestRate(BigDecimal rate) {
        if (rate == null) {
            return "interestRate is required when decision=APPROVE";
        }
        if (rate.compareTo(MIN_RATE) <= 0) {
            return "interestRate must be greater than 0";
        }
        if (rate.compareTo(MAX_RATE) > 0) {
            return "interestRate must be less than or equal to 50";
        }
        return null;
    }

    public Optional<Map<String, Object>> adminDetail(String loanAppId) {
        LoanApplication loan = loanRepo.findByExternalId(loanAppId).orElse(null);
        if (loan == null) return Optional.empty();

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("loan", toDetailItem(loan));
        resp.put("overrides", overrideRepo.findByLoanApplicationIdOrderByIdDesc(loan.getId()));

        if (loan.getUserId() != null) {
            userRepo.findById(loan.getUserId()).ifPresent(u -> {
                Map<String, Object> um = new LinkedHashMap<>();
                um.put("userId", u.getId());
                um.put("username", u.getUsername());
                um.put("email", u.getEmail());
                um.put("mobile", u.getMobile());
                um.put("fullName", (safe(u.getFirstname()) + " " + safe(u.getLastname())).trim());
                um.put("kycStatus", u.getKycStatus() == null ? null : u.getKycStatus().name());
                resp.put("user", um);
            });
        }

        if (loan.getFindocLoanApplicationId() != null) {
            try {
                JsonNode findocDetail = findoc.getApplicationDetail(loan.getFindocLoanApplicationId());
                resp.put("findocApplication", findocDetail);
            } catch (Exception e) {
                log.warn("admin.loans.detail findoc proxy failed loanAppId={} err={}", loanAppId, e.toString());
                resp.put("findocApplicationError", e.getMessage());
            }
        }
        return Optional.of(resp);
    }

    private Map<String, Object> toDetailItem(LoanApplication l) {
        Map<String, Object> m = new LinkedHashMap<>(CachedLists.adminLoanToListItem(l));
        m.put("fraudScore", l.getFraudScore());
        m.put("riskProbability", l.getRiskProbability());
        m.put("decisionReason", l.getDecisionReason());
        m.put("monthlyEmi", l.getMonthlyEmi());
        m.put("nextDueDate", l.getNextDueDate());
        m.put("createdAt", l.getCreatedAt());
        m.put("updatedAt", l.getUpdatedAt());
        m.put("docReevalReason", l.getDocReevalReason());

        if (l.getLoanReportJson() != null && !l.getLoanReportJson().isBlank()) {
            try { m.put("loanReport", objectMapper.readTree(l.getLoanReportJson())); }
            catch (Exception ignored) { m.put("loanReport", null); }
        }
        return m;
    }

    private static String safe(String s) { return s == null ? "" : s; }
}
