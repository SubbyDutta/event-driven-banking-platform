package backend.backend.controller;

import backend.backend.model.LoanApplication;
import backend.backend.model.LoanEligibilityRequest;
import backend.backend.Dtos.LoanApplicationResponseDto;
import backend.backend.requests_response.PagedResponse;
import backend.backend.security.CustomUserDetails;
import backend.backend.security.JwtUtil;
import backend.backend.service.LoanService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/loan")
public class LoanController {

    @Autowired
    private LoanService loanService;
    @Autowired
    private JwtUtil jwtutil;

    /**
     * Deprecated pre-V4 eligibility check. The event-driven origination flow
     * makes it implicit — {@code POST /api/loans/apply} runs eligibility as
     * part of the pipeline (findoc verification + ML risk scoring) and
     * returns a 202 with a status URL instead of a synchronous "eligible"
     * boolean. Returns 410 Gone to steer any client still calling this.
     */
    @PreAuthorize("hasRole('USER')")
    @PostMapping("/check")
    @Deprecated
    public ResponseEntity<?> checkEligibility(@RequestBody Map<String, Object> body,
                                              @RequestHeader("Authorization") String token) {
        return ResponseEntity.status(HttpStatus.GONE).body(Map.of(
                "error", "This endpoint has been replaced by the event-driven loan flow",
                "redirectTo", "/api/loans/apply",
                "docs", "Upload all loan documents via POST /api/loans/apply (multipart). Poll /api/loans/{loanAppId}/status for progress."));
    }

    /**
     * Deprecated pre-V4 loan-apply. Superseded by {@code POST /api/loans/apply}.
     */
    @PreAuthorize("hasRole('USER')")
    @PostMapping("/apply/{eligibilityId}")
    @Deprecated
    public ResponseEntity<?> applyLoan(@PathVariable Long eligibilityId,
                                       @RequestHeader("Authorization") String token) {
        return ResponseEntity.status(HttpStatus.GONE).body(Map.of(
                "error", "This endpoint has been replaced by the event-driven loan flow",
                "redirectTo", "/api/loans/apply"));
    }

    @GetMapping("/pending")
    @PreAuthorize("hasRole('ADMIN')")
    public PagedResponse<LoanApplicationResponseDto> pendingLoans(
            @RequestParam(defaultValue = "0") Integer page,
            @RequestParam(defaultValue = "10") Integer size
    ) {
        return loanService.getPendingLoans(page, size);
    }

    @PreAuthorize("hasRole('USER')")
    @GetMapping("/loans/myuserloan")
    public List<LoanApplicationResponseDto> myLoans(
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
          return loanService.userLoans(userDetails.getUsername());
    }
}
