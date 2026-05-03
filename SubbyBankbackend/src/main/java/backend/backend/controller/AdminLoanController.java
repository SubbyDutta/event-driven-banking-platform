package backend.backend.controller;

import backend.backend.model.LoanApplication;
import backend.backend.model.LoanLifecycleStatus;
import backend.backend.repository.LoanApplicationRepository;
import backend.backend.repository.LoanDecisionOverrideRepository;
import backend.backend.repository.UserRepository;
import backend.backend.security.CustomUserDetails;
import backend.backend.service.CachedLists;
import backend.backend.service.findoc.FindocVerifyClient;
import backend.backend.service.loan.AdminLoanOverrideService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/loans")
@PreAuthorize("hasRole('ADMIN')")
public class AdminLoanController {

    private final LoanApplicationRepository loanRepo;
    private final LoanDecisionOverrideRepository overrideRepo;
    private final UserRepository userRepo;
    private final FindocVerifyClient findoc;
    private final ObjectMapper objectMapper;
    private final CachedLists cachedLists;
    private final AdminLoanOverrideService overrideService;

    public AdminLoanController(LoanApplicationRepository loanRepo,
                               LoanDecisionOverrideRepository overrideRepo,
                               UserRepository userRepo,
                               FindocVerifyClient findoc,
                               ObjectMapper objectMapper,
                               CachedLists cachedLists,
                               AdminLoanOverrideService overrideService) {
        this.loanRepo = loanRepo;
        this.overrideRepo = overrideRepo;
        this.userRepo = userRepo;
        this.findoc = findoc;
        this.objectMapper = objectMapper;
        this.cachedLists = cachedLists;
        this.overrideService = overrideService;
    }

    @GetMapping
    public Map<String, Object> list(
            @RequestParam(required = false) String lifecycleStatus,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        LoanLifecycleStatus statusFilter = null;
        if (lifecycleStatus != null && !lifecycleStatus.isBlank()) {
            try { statusFilter = LoanLifecycleStatus.valueOf(lifecycleStatus); }
            catch (IllegalArgumentException e) {
                return Map.of("error", "Unknown lifecycleStatus: " + lifecycleStatus);
            }
        }

        CachedLists.AdminLoanPage pg = cachedLists.getAdminLoansCached(statusFilter, page, size);

        return Map.of(
                "content", pg.content(),
                "page", pg.page(),
                "size", pg.size(),
                "totalElements", pg.totalElements(),
                "totalPages", pg.totalPages());
    }

    @GetMapping("/manual-review")
    public Map<String, Object> manualReview(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Page<LoanApplication> pg = loanRepo.findByLifecycleStatus(
                LoanLifecycleStatus.MANUAL_REVIEW, PageRequest.of(page, Math.min(size, 200)));
        return Map.of(
                "content", pg.getContent().stream().map(this::toListItem).toList(),
                "total", pg.getTotalElements());
    }

    @GetMapping("/{loanAppId}")
    public ResponseEntity<?> detail(@PathVariable String loanAppId) {
        return overrideService.adminDetail(loanAppId)
                .map(resp -> (ResponseEntity<?>) ResponseEntity.ok(resp))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{loanAppId}/override")
    public ResponseEntity<?> override(@PathVariable String loanAppId,
                                      @RequestBody OverrideBody body,
                                      @AuthenticationPrincipal CustomUserDetails admin) {
        String adminName = admin != null ? admin.getUsername() : "system";
        AdminLoanOverrideService.Result result = overrideService.override(
                loanAppId, body.getDecision(), body.getReason(),
                body.getInterestRate(), adminName);

        if (result.notFound) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Not found"));
        }
        if (result.validationError != null) {
            return ResponseEntity.badRequest().body(Map.of("error", result.validationError));
        }
        return ResponseEntity.ok(result.toResponseBody(loanAppId));
    }

    @GetMapping("/{loanAppId}/documents/{field}/download")
    public ResponseEntity<?> downloadUrl(@PathVariable String loanAppId,
                                         @PathVariable String field) {

        LoanApplication loan = loanRepo.findByExternalId(loanAppId).orElse(null);
        if (loan == null) return ResponseEntity.notFound().build();
        if (loan.getFindocLoanApplicationId() == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "findoc-verify has no record for this loan yet"));
        }
        return ResponseEntity.ok(Map.of(
                "proxyThrough", "findoc-verify",
                "downloadPath", "/api/v1/loan-origination/" + loan.getFindocLoanApplicationId()
                        + "/documents/" + field + "/download"));
    }

    private Map<String, Object> toListItem(LoanApplication l) {
        return CachedLists.adminLoanToListItem(l);
    }

    @Data
    public static class OverrideBody {
        @NotBlank
        private String decision;
        @NotBlank
        private String reason;
        private BigDecimal interestRate;
    }
}
