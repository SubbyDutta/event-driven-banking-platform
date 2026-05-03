package backend.backend.controller;

import backend.backend.model.LoanApplication;
import backend.backend.model.LoanLifecycleStatus;
import backend.backend.model.User;
import backend.backend.repository.BankAccountRepository;
import backend.backend.repository.LoanApplicationRepository;
import backend.backend.repository.UserRepository;
import backend.backend.security.CustomUserDetails;
import backend.backend.service.loan.LoanFinalizationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/loans")
@RequiredArgsConstructor
public class LoanOriginationController {

    private static final Logger log = LoggerFactory.getLogger(LoanOriginationController.class);

    private final UserRepository userRepository;
    private final LoanApplicationRepository loanRepo;
    private final BankAccountRepository bankAccountRepository;
    private final ObjectMapper objectMapper;
    private final LoanFinalizationService finalizer;

    @PreAuthorize("isAuthenticated()")
    @PostMapping(path = "/apply", consumes = "multipart/form-data")
    @Transactional
    public ResponseEntity<?> apply(
            @RequestParam("amount") double amount,
            @RequestParam("purpose") String purposeRaw,
            @RequestParam(value = "terms_accepted", defaultValue = "false") boolean termsAccepted,
            @RequestParam("bank_statement_1") MultipartFile bankStatement1,
            @RequestParam("bank_statement_2") MultipartFile bankStatement2,
            @RequestParam("bank_statement_3") MultipartFile bankStatement3,
            @RequestParam("payslip_1") MultipartFile payslip1,
            @RequestParam("payslip_2") MultipartFile payslip2,
            @RequestParam("payslip_3") MultipartFile payslip3,
            @RequestParam("employment_letter") MultipartFile employmentLetter,
            @RequestParam("itr") MultipartFile itr,
            @RequestParam("credit_report") MultipartFile creditReport,
            @RequestHeader(value = "Idempotency-Key", required = false) String idemKey,
            @AuthenticationPrincipal CustomUserDetails principal) {

        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Unauthorized"));
        }
        User user = userRepository.findById(principal.getUser_id())
                .orElseThrow(() -> new IllegalStateException("User not found"));

        Map<String, MultipartFile> files = new LinkedHashMap<>();
        files.put("bank_statement_1", bankStatement1);
        files.put("bank_statement_2", bankStatement2);
        files.put("bank_statement_3", bankStatement3);
        files.put("payslip_1", payslip1);
        files.put("payslip_2", payslip2);
        files.put("payslip_3", payslip3);
        files.put("employment_letter", employmentLetter);
        files.put("itr", itr);
        files.put("credit_report", creditReport);

        LoanFinalizationService.ApplyResult result = finalizer.applyWithDocuments(
                user, amount, purposeRaw, termsAccepted, files, idemKey);
        return toResponse(result);
    }

    private ResponseEntity<?> toResponse(LoanFinalizationService.ApplyResult r) {
        if (r.errorMessage != null) {
            Map<String, Object> body = new HashMap<>();
            body.put("error", r.errorMessage);
            if (r.errorCode != null) body.put("code", r.errorCode);
            if (r.kycStatus != null) body.put("kycStatus", r.kycStatus);
            return ResponseEntity.status(r.httpStatus).body(body);
        }
        return ResponseEntity.status(r.httpStatus).body(r.body);
    }

    @PreAuthorize("isAuthenticated()")
    @PostMapping("/{loanAppId}/accept")
    public ResponseEntity<?> accept(@PathVariable String loanAppId,
                                    @AuthenticationPrincipal CustomUserDetails principal) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        LoanApplication loan = loanRepo.findByExternalId(loanAppId).orElse(null);
        if (loan == null) return ResponseEntity.notFound().build();
        if (loan.getUserId() == null || !loan.getUserId().equals(principal.getUser_id())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (loan.getLifecycleStatus() != LoanLifecycleStatus.PENDING_USER_ACCEPTANCE) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "Loan is not awaiting your acceptance",
                    "lifecycleStatus", loan.getLifecycleStatus() == null ? "UNKNOWN" : loan.getLifecycleStatus().name()));
        }

        if (bankAccountRepository.findByUserUsername(principal.getUsername()).isEmpty()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "Open a bank account before accepting the offer",
                    "code", "BANK_ACCOUNT_REQUIRED"));
        }

        finalizer.finalize(loan, "APPROVED", "User accepted offer",
                loan.getInterestRate(), "user:" + principal.getUsername());

        LoanApplication after = loanRepo.findByExternalId(loanAppId).orElse(loan);
        log.info("loan.accept loanAppId={} userId={} lifecycle={}",
                loanAppId, principal.getUser_id(), after.getLifecycleStatus());
        return ResponseEntity.ok(Map.of(
                "loanAppId", loanAppId,
                "lifecycleStatus", after.getLifecycleStatus().name(),
                "message", "Offer accepted; funds disbursed to your account"));
    }

    @PreAuthorize("isAuthenticated()")
    @PostMapping("/{loanAppId}/decline")
    public ResponseEntity<?> decline(@PathVariable String loanAppId,
                                     @RequestBody(required = false) Map<String, Object> body,
                                     @AuthenticationPrincipal CustomUserDetails principal) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        LoanApplication loan = loanRepo.findByExternalId(loanAppId).orElse(null);
        if (loan == null) return ResponseEntity.notFound().build();
        if (loan.getUserId() == null || !loan.getUserId().equals(principal.getUser_id())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (loan.getLifecycleStatus() != LoanLifecycleStatus.PENDING_USER_ACCEPTANCE) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "Loan is not awaiting your acceptance",
                    "lifecycleStatus", loan.getLifecycleStatus() == null ? "UNKNOWN" : loan.getLifecycleStatus().name()));
        }

        String userReason = body == null ? null : trimToNull(String.valueOf(body.getOrDefault("reason", "")));
        String reason = userReason == null ? "user declined" : "user declined: " + userReason;
        finalizer.finalize(loan, "REJECTED", reason, null, "user:" + principal.getUsername());

        LoanApplication after = loanRepo.findByExternalId(loanAppId).orElse(loan);
        log.info("loan.decline loanAppId={} userId={} lifecycle={}",
                loanAppId, principal.getUser_id(), after.getLifecycleStatus());
        return ResponseEntity.ok(Map.of(
                "loanAppId", loanAppId,
                "lifecycleStatus", after.getLifecycleStatus().name(),
                "message", "Offer declined"));
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/{loanAppId}/status")
    public ResponseEntity<?> status(@PathVariable String loanAppId,
                                    @AuthenticationPrincipal CustomUserDetails principal) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        LoanApplication loan = loanRepo.findByExternalId(loanAppId).orElse(null);
        if (loan == null) return ResponseEntity.notFound().build();
        if (loan.getUserId() == null || !loan.getUserId().equals(principal.getUser_id())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(LoanFinalizationService.toUserStatusDto(loan, objectMapper));
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/my")
    public Map<String, Object> myLoans(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @AuthenticationPrincipal CustomUserDetails principal) {
        return finalizer.findMyLoansPage(principal.getUser_id(), page, size);
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
