package backend.backend.controller;

import backend.backend.model.LoanApplication;
import backend.backend.model.LoanRepayment;
import backend.backend.Dtos.LoanSummaryDTO;
import backend.backend.Dtos.LoanRepaymentResponseDto;
import backend.backend.requests_response.PagedResponse;
import backend.backend.security.CustomUserDetails;
import backend.backend.service.LoanService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/repay")
public class LoanRepayController {
    @Autowired
    private LoanService loanService;

    @PreAuthorize("hasRole('USER')")
    @PostMapping("/repay/{loanId}")
    public LoanRepayment repayLoan(@PathVariable Long loanId, @RequestHeader("Idempotency-Key") String key, @RequestBody Map<String, Object> body) {
        double amount = Double.parseDouble(body.get("amount").toString());
        return loanService.repayLoan(loanId, amount, key);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public PagedResponse<LoanRepaymentResponseDto> getAllRepayments(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        return loanService.repayList(page, size);
    }

    @GetMapping("/repayments")
    @PreAuthorize("hasRole('USER')")
    public List<LoanRepayment> getUserRepayments(@AuthenticationPrincipal CustomUserDetails userDetails) {
        return loanService.userrepay(userDetails.getUsername());
    }

    @PreAuthorize("hasRole('USER')")
    @GetMapping("/summary/{loanId}")
    public LoanSummaryDTO getSummary(@PathVariable Long loanId) {
        return loanService.getLoanSummary(loanId);
    }

    @GetMapping("/user/approved")
    @PreAuthorize("hasRole('USER')")
    public List<LoanApplication> getUserApprovedLoans(@AuthenticationPrincipal CustomUserDetails userDetails) {
        String username = userDetails.getUsername();
        return loanService.getUserApprovedLoans(username,"APPROVED");
    }

}
