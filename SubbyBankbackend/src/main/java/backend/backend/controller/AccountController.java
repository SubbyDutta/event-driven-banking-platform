package backend.backend.controller;

import backend.backend.Dtos.UserResponseDto;
import backend.backend.model.BankAccount;
import backend.backend.model.User;
import backend.backend.repository.BankAccountRepository;
import backend.backend.requests_response.AccountRequest;
import backend.backend.requests_response.PagedResponse;
import backend.backend.Dtos.TransactionDto;
import backend.backend.security.CustomUserDetails;
import backend.backend.service.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import backend.backend.service.BankService;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDate;
import java.util.Map;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/user")
public class AccountController {
    private final AccountService accountService;
    private final BankService bankService;
    private final UserService userService;
    private final TransactionService transactionService;

    @PreAuthorize("hasRole('USER')")
    @GetMapping("/me/account")
    public ResponseEntity<?> getMyAccount(@AuthenticationPrincipal CustomUserDetails userDetails) {
        if (userDetails == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Unauthorized"));
        String username = userDetails.getUsername();
        String accountNumber=accountService.getAccount(username);
        if(accountNumber.equals("null"))
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "bank account not found"));
        return ResponseEntity.ok(accountNumber);
    }

    @PreAuthorize("hasRole('USER')")
    @GetMapping("/balance")
    public ResponseEntity<String> getBalance(@AuthenticationPrincipal CustomUserDetails userDetails) {
        Long userId = userDetails.getUser_id() ;
        double balance = accountService.getBalance(userId);
        return ResponseEntity.ok("Your current balance is ₹" + balance);
    }

    @PreAuthorize("hasRole('USER')")
    @GetMapping("/creditscore")
    public Map<String, Object> getCreditScore(@AuthenticationPrincipal CustomUserDetails userDetails) {
        if (userDetails == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized");
        }
        int score = userService.fetchCreditScore(userDetails.getUsername());
        return Map.of("creditScore", score);
    }

    @PreAuthorize("hasRole('USER')")
    @GetMapping("/{username}/transactions")
    public PagedResponse<TransactionDto> getUserTransactions(
            @PathVariable String username,
            @RequestParam(defaultValue = "0") Integer page,
            @RequestParam(defaultValue = "5") Integer size,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Double minAmount,
            @RequestParam(required = false) Double maxAmount,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized");
        }
        boolean isAdmin = principal.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (!isAdmin && !principal.getUsername().equals(username)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot view another user's transactions");
        }
        return transactionService.getUserTransactionsFiltered(
                username, page, size, from, to, minAmount, maxAmount
        );
    }

    @PreAuthorize("isAuthenticated()")
    @PostMapping("/create-account")
    public ResponseEntity<?> createBankAccount(@Valid @RequestBody AccountRequest request) {
        try {
            User user = userService.ifUserExists(request.getUsername());

            BankAccount account = bankService.createAccount(user, request.getType());
            return ResponseEntity.ok(account);

        } catch (RuntimeException e) {

            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {

            return ResponseEntity.status(500).body(Map.of("error", "Something went wrong. Please try again."));
        }
    }

}
