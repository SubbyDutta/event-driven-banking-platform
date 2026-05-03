package backend.backend.service.loan;

import backend.backend.Dtos.LoanApplyResponseDto;
import backend.backend.Dtos.LoanStatusDto;
import backend.backend.Exception.ResourceNotFoundException;
import backend.backend.configuration.SubbyProperties;
import backend.backend.events.LoanApplicationSubmitted;
import backend.backend.events.LoanDisbursed;
import backend.backend.events.LoanFinalized;
import backend.backend.messaging.OutboxEventPublisher;
import backend.backend.model.BankAccount;
import backend.backend.model.KycStatus;
import backend.backend.model.LoanApplication;
import backend.backend.model.LoanLifecycleStatus;
import backend.backend.model.LoanPurpose;
import backend.backend.model.LoanRepayment;
import backend.backend.model.Transaction;
import backend.backend.model.User;
import backend.backend.repository.BankAccountRepository;
import backend.backend.repository.LoanApplicationRepository;
import backend.backend.repository.LoanRepaymentRepository;
import backend.backend.repository.UserRepository;
import backend.backend.service.AdminCacheEvictionService;
import backend.backend.service.BankPoolService;
import backend.backend.service.BankService;
import backend.backend.service.BuisnessLoggingService;
import backend.backend.service.TransactionService;
import backend.backend.storage.DocType;
import backend.backend.storage.S3DocumentStorage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LoanFinalizationService {

    private static final Logger log = LoggerFactory.getLogger(LoanFinalizationService.class);
    private static final int TENURE_MONTHS = 6;

    private static final double AMOUNT_MIN = 10_000.0;
    private static final double AMOUNT_MAX = 10_00_000.0;
    private static final Set<String> ALLOWED_MIME = Set.of(
            "application/pdf", "image/png", "image/jpeg", "image/jpg");
    private static final List<String> DOC_FIELDS = List.of(
            "bank_statement_1", "bank_statement_2", "bank_statement_3",
            "payslip_1", "payslip_2", "payslip_3",
            "employment_letter", "itr", "credit_report");
    private static final Map<String, DocType> DOC_TYPE = Map.ofEntries(
            Map.entry("bank_statement_1", DocType.BANK_STATEMENT_1),
            Map.entry("bank_statement_2", DocType.BANK_STATEMENT_2),
            Map.entry("bank_statement_3", DocType.BANK_STATEMENT_3),
            Map.entry("payslip_1", DocType.PAYSLIP_1),
            Map.entry("payslip_2", DocType.PAYSLIP_2),
            Map.entry("payslip_3", DocType.PAYSLIP_3),
            Map.entry("employment_letter", DocType.EMPLOYMENT_LETTER),
            Map.entry("itr", DocType.ITR),
            Map.entry("credit_report", DocType.CREDIT_REPORT));

    private final LoanApplicationRepository loanRepo;
    private final UserRepository userRepo;
    private final BankAccountRepository bankRepo;
    private final LoanRepaymentRepository repaymentRepo;
    private final BankPoolService bankPoolService;
    private final BankService bankService;
    private final TransactionService transactionService;
    private final BuisnessLoggingService buisnessLoggingService;
    private final OutboxEventPublisher outbox;
    private final SubbyProperties properties;
    private final S3DocumentStorage s3;
    private final ObjectMapper objectMapper;
    private final AdminCacheEvictionService cacheEvictor;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finalize(LoanApplication loan, String decision, String reason,
                         BigDecimal interestRate, String source) {
        if (loan == null) throw new IllegalArgumentException("loan is required");
        if (decision == null) throw new IllegalArgumentException("decision is required");

        boolean isAdminOverride = source != null && source.startsWith("admin:");
        boolean isUserAcceptance = source != null && source.startsWith("user:");
        if (!isAdminOverride && !isUserAcceptance
                && loan.getLifecycleStatus() != null
                && loan.getLifecycleStatus().isTerminal()) {
            log.info("loan.finalize.skip already terminal lifecycle={} loanAppId={}",
                    loan.getLifecycleStatus(), loan.getExternalId());
            return;
        }

        if (isAdminOverride && "APPROVED".equals(decision)
                && loan.getLifecycleStatus() != LoanLifecycleStatus.PENDING_USER_ACCEPTANCE) {
            parkForUserAcceptance(loan, interestRate);
            cacheEvictor.evictLoanCaches();
            return;
        }

        switch (decision) {
            case "APPROVED" -> approve(loan, reason, interestRate, source);
            case "REJECTED" -> reject(loan, reason, source);
            case "MANUAL_REVIEW" -> manualReview(loan, reason);
            default -> throw new IllegalArgumentException("Unknown decision: " + decision);
        }
        cacheEvictor.evictLoanCaches();
    }

    private void parkForUserAcceptance(LoanApplication loan, BigDecimal interestRate) {
        if (interestRate == null) {
            throw new IllegalStateException(
                    "Cannot park loan for user acceptance without an interestRate (loanAppId="
                            + loan.getExternalId() + ")");
        }
        double emi = computeEmi(loan.getAmount(), interestRate, TENURE_MONTHS);
        double totalPayable = round2(emi * TENURE_MONTHS);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime firstDue = now.plusMonths(1);

        loan.setLifecycleStatus(LoanLifecycleStatus.PENDING_USER_ACCEPTANCE);
        loan.setInterestRate(interestRate);
        loan.setMonthlyEmi(round2(emi));
        loan.setDue_amount(totalPayable);
        loan.setMonthsRemaining(TENURE_MONTHS);
        loan.setNextDueDate(firstDue);
        loan.setDecisionReason("Offer ready — awaiting user acceptance");
        loanRepo.save(loan);

        log.info("loan.finalize.park_for_user_acceptance loanAppId={} rate={} emi={}",
                loan.getExternalId(), interestRate, emi);
    }

    private void approve(LoanApplication loan, String reason, BigDecimal interestRate, String source) {
        if (interestRate == null) {
            throw new IllegalStateException(
                    "Cannot approve loan without an interestRate (loanAppId=" + loan.getExternalId() + ")");
        }

        double emi = computeEmi(loan.getAmount(), interestRate, TENURE_MONTHS);
        double totalPayable = round2(emi * TENURE_MONTHS);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime firstDue = now.plusMonths(1);

        loan.setLifecycleStatus(LoanLifecycleStatus.APPROVED);
        loan.setInterestRate(interestRate);
        loan.setDecisionReason(reason);
        loan.setDecidedAt(Instant.now());

        loan.setStatus("APPROVED");
        loan.setApproved(true);
        loan.setDue_amount(totalPayable);
        loan.setMonthsRemaining(TENURE_MONTHS);
        loan.setMonthlyEmi(round2(emi));
        loan.setApprovedAt(now);
        loan.setNextDueDate(firstDue);

        loanRepo.save(loan);

        User user = userRepo.findById(loan.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "User not found for loan: userId=" + loan.getUserId()));
        BankAccount account = bankRepo.findByUserUsername(user.getUsername())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Bank account not found for user " + user.getUsername()));

        bankPoolService.deduct(loan.getAmount());
        account.setBalance(account.getBalance() + loan.getAmount());
        bankService.updateUserBalance(user.getUsername(), account.getBalance());

        Transaction tx = new Transaction();
        tx.setSenderAccount("BANK");
        tx.setReceiverAccount(account.getAccountNumber());
        tx.setAmount(loan.getAmount());
        tx.setBalance(account.getBalance());
        tx.setTimestamp(LocalDateTime.now());
        tx.setFraud_probability(0);
        tx.setIs_fraud(0);
        tx.setIsHighRisk(0);
        tx.setIsForeign(0);
        if (user.getId() != null) tx.setUserId(user.getId().intValue());
        transactionService.checkFraud(tx);

        outbox.publish(
                properties.topics().notifications(),
                LoanDisbursed.forLoan(
                        String.valueOf(user.getId()),
                        user.getEmail(),
                        loan.getExternalId(),
                        BigDecimal.valueOf(loan.getAmount()),
                        maskAccountNumber(account.getAccountNumber()),
                        Instant.now()));

        LoanRepayment repayment = new LoanRepayment();
        repayment.setLoanId(loan.getId());
        repayment.setUsername(user.getUsername());
        repayment.setAmountPaid(0);
        repayment.setRemainingBalance(totalPayable);
        repayment.setPaymentDate(now);
        repaymentRepo.save(repayment);

        user.setHasLoan(true);
        user.setLoanamount(loan.getAmount());
        user.setRemaining(totalPayable);
        user.setDueDate(firstDue);
        userRepo.save(user);

        buisnessLoggingService.log("LOAN APPROVAL", user.getUsername(),
                "Approved loan of " + loan.getAmount() + " @ " + interestRate + "% (source=" + source + ")");

        stageFinalized(loan, user, "APPROVED", reason, emi, firstDue);
    }

    private void reject(LoanApplication loan, String reason, String source) {
        boolean wasApproved = loan.getLifecycleStatus() == LoanLifecycleStatus.APPROVED;
        boolean adminOverride = source != null && source.startsWith("admin:");

        loan.setLifecycleStatus(LoanLifecycleStatus.REJECTED);
        loan.setStatus("REJECTED");
        loan.setApproved(false);
        loan.setDecisionReason(reason);
        loan.setDecidedAt(Instant.now());
        loanRepo.save(loan);

        User user = loan.getUserId() == null ? null
                : userRepo.findById(loan.getUserId()).orElse(null);

        if (wasApproved && adminOverride && user != null) {
            reverseDisbursement(loan, user);
        }

        stageFinalized(loan, user, "REJECTED", reason, null, null);
    }

    private void reverseDisbursement(LoanApplication loan, User user) {
        BankAccount account = bankRepo.findByUserUsername(user.getUsername())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Bank account not found for reversal user " + user.getUsername()));

        double amount = loan.getAmount();
        account.setBalance(account.getBalance() - amount);
        bankService.updateUserBalance(user.getUsername(), account.getBalance());
        bankPoolService.add(amount);

        Transaction tx = new Transaction();
        tx.setSenderAccount(account.getAccountNumber());
        tx.setReceiverAccount("BANK");
        tx.setAmount(amount);
        tx.setBalance(account.getBalance());
        tx.setTimestamp(LocalDateTime.now());
        tx.setFraud_probability(0);
        tx.setIs_fraud(0);
        tx.setIsHighRisk(0);
        tx.setIsForeign(0);
        if (user.getId() != null) tx.setUserId(user.getId().intValue());
        transactionService.saveTransaction(tx);

        user.setHasLoan(false);
        user.setLoanamount(0);
        user.setRemaining(0);
        userRepo.save(user);

        buisnessLoggingService.log("LOAN REVERSAL", user.getUsername(),
                "admin:override:reversal loanAppId=" + loan.getExternalId() + " amount=" + amount);
    }

    private void manualReview(LoanApplication loan, String reason) {
        loan.setLifecycleStatus(LoanLifecycleStatus.MANUAL_REVIEW);
        loan.setDecisionReason(reason);
        loan.setDecidedAt(Instant.now());
        loanRepo.save(loan);

        User user = loan.getUserId() == null ? null
                : userRepo.findById(loan.getUserId()).orElse(null);
        stageFinalized(loan, user, "MANUAL_REVIEW", reason, null, null);
    }

    private void stageFinalized(LoanApplication loan, User user, String decision,
                                String reason, Double emi, LocalDateTime firstDue) {
        String userIdStr = user == null ? (loan.getUserId() == null ? null : String.valueOf(loan.getUserId()))
                : String.valueOf(user.getId());
        String firstDueIso = firstDue == null ? null : firstDue.toLocalDate().toString();
        LoanFinalized fin = new LoanFinalized(null, null, 1, loan.getExternalId(),
                loan.getExternalId(),
                userIdStr,
                decision,
                reason,
                loan.getId(),
                loan.getAmount(),
                TENURE_MONTHS,
                loan.getInterestRate(),
                emi,
                firstDueIso);
        outbox.publish(properties.topics().notifications(), fin);
    }

    public static double computeEmi(double principal, BigDecimal annualRatePercent, int tenureMonths) {
        BigDecimal r = annualRatePercent.divide(BigDecimal.valueOf(12 * 100), 10, RoundingMode.HALF_UP);
        double rd = r.doubleValue();
        if (rd == 0) return round2(principal / tenureMonths);
        double pow = Math.pow(1 + rd, tenureMonths);
        return round2(principal * rd * pow / (pow - 1));
    }

    private static double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static String maskAccountNumber(String accountNumber) {
        if (accountNumber == null || accountNumber.isBlank()) return "XXXX";
        int len = accountNumber.length();
        if (len <= 4) return "XXXX" + accountNumber;
        return "XXXX" + accountNumber.substring(len - 4);
    }

    public static class ApplyResult {
        public final int httpStatus;
        public final String errorMessage;
        public final String errorCode;
        public final String kycStatus;
        public final LoanApplyResponseDto body;

        private ApplyResult(int httpStatus, String errorMessage, String errorCode,
                            String kycStatus, LoanApplyResponseDto body) {
            this.httpStatus = httpStatus;
            this.errorMessage = errorMessage;
            this.errorCode = errorCode;
            this.kycStatus = kycStatus;
            this.body = body;
        }

        public static ApplyResult ok(LoanApplyResponseDto body) {
            return new ApplyResult(202, null, null, null, body);
        }

        public static ApplyResult duplicate(LoanApplyResponseDto body) {
            return new ApplyResult(200, null, null, null, body);
        }

        public static ApplyResult forbidden(String message, String kycStatus) {
            return new ApplyResult(403, message, null, kycStatus, null);
        }

        public static ApplyResult conflict(String message, String code) {
            return new ApplyResult(409, message, code, null, null);
        }

        public static ApplyResult badRequest(String message) {
            return new ApplyResult(400, message, null, null, null);
        }
    }

    @Transactional
    public ApplyResult applyWithDocuments(User user,
                                          double amount,
                                          String purposeRaw,
                                          boolean termsAccepted,
                                          Map<String, MultipartFile> files,
                                          String idemKey) {
        if (user.getKycStatus() == null || user.getKycStatus() != KycStatus.KYC_APPROVED) {
            return ApplyResult.forbidden("Complete KYC before applying for a loan",
                    user.getKycStatus() == null ? "NONE" : user.getKycStatus().name());
        }
        if (bankRepo.findByUserUsername(user.getUsername()).isEmpty()) {
            return ApplyResult.conflict("Open a bank account before applying for a loan",
                    "BANK_ACCOUNT_REQUIRED");
        }
        if (!termsAccepted) {
            return ApplyResult.badRequest("Terms must be accepted");
        }
        if (amount < AMOUNT_MIN || amount > AMOUNT_MAX) {
            return ApplyResult.badRequest("Amount must be between ₹" + (int) AMOUNT_MIN
                    + " and ₹" + (int) AMOUNT_MAX);
        }
        LoanPurpose purpose;
        try {
            purpose = LoanPurpose.valueOf(purposeRaw);
        } catch (IllegalArgumentException e) {
            return ApplyResult.badRequest("Invalid purpose. Allowed: "
                    + Arrays.toString(LoanPurpose.values()));
        }

        Set<LoanLifecycleStatus> blocking = EnumSet.noneOf(LoanLifecycleStatus.class);
        for (LoanLifecycleStatus s : LoanLifecycleStatus.values()) {
            if (s.blocksNewApplication()) blocking.add(s);
        }
        if (loanRepo.existsByUserIdAndLifecycleStatusIn(user.getId(), blocking)) {
            return ApplyResult.conflict("You already have a loan application in progress", null);
        }

        boolean hasUnpaid = loanRepo.findByUserIdOrderByIdDesc(user.getId()).stream()
                .anyMatch(l -> "APPROVED".equalsIgnoreCase(l.getStatus()) && l.getDue_amount() > 0);
        if (hasUnpaid) {
            return ApplyResult.conflict("Repay your existing loan before applying for a new one", null);
        }

        for (String field : DOC_FIELDS) {
            String err = validateLoanFile(field, files.get(field));
            if (err != null) return ApplyResult.badRequest(err);
        }

        String loanAppId = (idemKey == null || idemKey.isBlank())
                ? UUID.randomUUID().toString()
                : idemKey;
        Optional<LoanApplication> prior = loanRepo.findByExternalId(loanAppId);
        if (prior.isPresent()) {
            LoanApplication p = prior.get();
            return ApplyResult.duplicate(new LoanApplyResponseDto(
                    p.getExternalId(), p.getExternalId(), TENURE_MONTHS,
                    p.getLifecycleStatus().name(),
                    "/api/loans/" + p.getExternalId() + "/status"));
        }

        LoanApplication loan = new LoanApplication();
        loan.setExternalId(loanAppId);
        loan.setUserId(user.getId());
        loan.setUsername(user.getUsername());
        loan.setAmount(amount);
        loan.setPurpose(purpose);
        loan.setMonthsRemaining(TENURE_MONTHS);
        loan.setLifecycleStatus(LoanLifecycleStatus.DRAFT);
        loan.setStatus("PENDING");
        loan.setApproved(false);
        loan.setDue_amount(0);
        loan.setSubmittedAt(Instant.now());
        loan = loanRepo.save(loan);

        Map<String, String> s3Keys = new LinkedHashMap<>();
        for (String field : DOC_FIELDS) {
            DocType dt = DOC_TYPE.get(field);
            String key = s3.putLoanDocument(loanAppId, dt, files.get(field));
            s3Keys.put(field, key);
        }

        String applicantName = trimToNull((safe(user.getFirstname()) + " " + safe(user.getLastname())).trim());
        String applicantDob = user.getDob() == null ? null : user.getDob().toString();

        LoanApplicationSubmitted event = LoanApplicationSubmitted.of(
                loanAppId, String.valueOf(user.getId()), user.getUsername(),
                amount, purpose.name(), TENURE_MONTHS, s3Keys,
                applicantName, user.getEmail(), user.getMobile(), applicantDob);
        outbox.publish(properties.topics().loanEvents(), event);

        log.info("loan.apply loanAppId={} userId={} amount={} purpose={} docs={}",
                loanAppId, user.getId(), amount, purpose, s3Keys.keySet());

        cacheEvictor.evictLoanCaches();

        return ApplyResult.ok(new LoanApplyResponseDto(
                loanAppId, loanAppId, TENURE_MONTHS,
                LoanLifecycleStatus.DRAFT.name(),
                "/api/loans/" + loanAppId + "/status"));
    }

    public Map<String, Object> findMyLoansPage(Long userId, int page, int size) {
        if (size <= 0) size = 10;
        if (size > 100) size = 100;
        Page<LoanApplication> pg = loanRepo.findByUserIdOrderByIdDesc(userId, PageRequest.of(page, size));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (LoanApplication l : pg.getContent()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("loanAppId", l.getExternalId());
            r.put("loanId", l.getId());
            r.put("amount", l.getAmount());
            r.put("purpose", l.getPurpose() == null ? null : l.getPurpose().name());
            r.put("lifecycleStatus", l.getLifecycleStatus() == null ? null : l.getLifecycleStatus().name());
            r.put("status", l.getStatus());
            r.put("submittedAt", l.getSubmittedAt());
            r.put("decidedAt", l.getDecidedAt());
            rows.add(r);
        }
        return Map.of(
                "content", rows,
                "page", pg.getNumber(),
                "size", pg.getSize(),
                "totalElements", pg.getTotalElements(),
                "totalPages", pg.getTotalPages());
    }

    public static LoanStatusDto toUserStatusDto(LoanApplication l, ObjectMapper objectMapper) {
        JsonNode preview = null;
        if (l.getLoanReportJson() != null && !l.getLoanReportJson().isBlank()
                && l.getLifecycleStatus() != LoanLifecycleStatus.DRAFT
                && l.getLifecycleStatus() != LoanLifecycleStatus.DOCS_UNDER_REVIEW) {
            try {
                JsonNode full = objectMapper.readTree(l.getLoanReportJson());
                preview = buildUserPreview(full, objectMapper);
            } catch (Exception ignored) {
            }
        }
        Integer creditScore = null;
        try {
            if (l.getLoanReportJson() != null) {
                JsonNode root = objectMapper.readTree(l.getLoanReportJson());
                var cs = LoanFeatureExtractor.firstNumber(
                        root, "creditScore", "credit_score", "cibilScore", "cibil_score");
                if (cs != null) creditScore = cs.intValue();
            }
        } catch (Exception ignored) {
        }

        return new LoanStatusDto(
                l.getExternalId(),
                l.getLifecycleStatus() == null ? null : l.getLifecycleStatus().name(),
                l.getAmount(),
                TENURE_MONTHS,
                l.getPurpose() == null ? null : l.getPurpose().name(),
                l.getSubmittedAt(),
                l.getDecidedAt(),
                l.getLifecycleStatus() == null ? null : l.getLifecycleStatus().name(),
                l.getDecisionReason(),
                l.getFraudScore(),
                creditScore,
                l.getRiskBand(),
                l.getRiskProbability(),
                l.getInterestRate(),
                l.getMonthlyEmi() == 0 ? null : l.getMonthlyEmi(),
                l.getNextDueDate(),
                l.getLifecycleStatus() == LoanLifecycleStatus.APPROVED ? l.getId() : null,
                preview);
    }

    private static JsonNode buildUserPreview(JsonNode root, ObjectMapper objectMapper) {
        if (root == null || root.isNull()) return null;
        ObjectNode out = objectMapper.createObjectNode();
        copyIfPresent(root, out, "recommendation");
        copyIfPresent(root, out, "overallScore");
        copyIfPresent(root, out, "complianceChecks");
        copyIfPresent(root, out, "identityChecks");
        JsonNode signals = root.get("fraudSignals");
        if (signals != null && signals.isArray()) {
            var arr = out.putArray("fraudSignals");
            for (JsonNode s : signals) {
                ObjectNode item = objectMapper.createObjectNode();
                if (s.has("signalName")) item.set("signalName", s.get("signalName"));
                if (s.has("severity")) item.set("severity", s.get("severity"));
                arr.add(item);
            }
        }
        return out;
    }

    private static void copyIfPresent(JsonNode src, ObjectNode dst, String key) {
        JsonNode v = src.get(key);
        if (v != null && !v.isNull()) dst.set(key, v);
    }

    private static String validateLoanFile(String field, MultipartFile f) {
        if (f == null || f.isEmpty()) return "Missing required document: " + field;
        if (f.getSize() > 10 * 1024 * 1024) return field + " exceeds 10 MB";
        String ct = f.getContentType();
        if (ct == null || !ALLOWED_MIME.contains(ct.toLowerCase())) {
            return field + " must be PDF, PNG, or JPG (got " + ct + ")";
        }
        return null;
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
