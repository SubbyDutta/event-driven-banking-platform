package backend.backend.controller;

import backend.backend.Dtos.BankAccountResponseDto;
import backend.backend.Dtos.BuisnessLoggingResponseDto;
import backend.backend.Dtos.LoanApplicationResponseDto;
import backend.backend.Dtos.UserResponseDto;
import backend.backend.model.LoanApplication;
import backend.backend.model.Stats;
import backend.backend.model.User;
import backend.backend.requests_response.*;
import backend.backend.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final  UserService userService;
    private final AccountService accountService;
    private final BankService bankService;
    private final BuisnessLoggingService buisnessLoggingService;
    private final LoanService loanService;
    private final AppStats appStats;

    @GetMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    public PagedResponse<UserResponseDto> getAllUsers(
            @RequestParam(defaultValue = "0") Integer page,
            @RequestParam(defaultValue = "10") Integer size
    ) {
        return userService.getAllUsers(page,size);
    }

    @GetMapping("/user/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public UserResponseDto getUser(@PathVariable Long id) {
        return userService.getUserById(id);
    }

    @PutMapping("/user/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public UserResponseDto updateUser(@PathVariable Long id, @RequestBody User updated) {
        return userService.updateUser(id, updated);
    }

    @DeleteMapping("/user/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public String deleteUser(@PathVariable Long id) {
        userService.deleteUser(id);
        return "User deleted";
    }

@PreAuthorize("hasRole('ADMIN')")
@GetMapping("/alllogs")
public ResponseEntity<PagedResponse<BuisnessLoggingResponseDto>> getAllLogs(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
) {
    return ResponseEntity.ok(
            buisnessLoggingService.getAllLogs(page, size)
    );
}

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/logs/action")
    public ResponseEntity<PagedResponse<BuisnessLoggingResponseDto>> getLogsByAction(
            @RequestParam("value") String action,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(
                buisnessLoggingService.getBuisnessLogsByAction(page, size, action)
        );
    }

    @PatchMapping("/block/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public String toggleBlock(@PathVariable Long id) {
          boolean acc=  bankService.ToggleBlock(id);
        return acc? "Account blocked" : "Account unblocked";
    }

    @GetMapping("/balance/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public double getBalance(@PathVariable Long id) {
        return bankService.getAccountByid(id).balance();
    }

    @GetMapping("/bankaccounts")
    @PreAuthorize("hasRole('ADMIN')")
    public PagedResponse<BankAccountResponseDto> getAllAccounts(
            @RequestParam(defaultValue = "0") Integer page,
            @RequestParam(defaultValue = "1") Integer size
    ) {
        return accountService.getAllAccountsPaged(page,size);
    }

    @PatchMapping("/balance/")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> updateUserBalance(@RequestBody BalanceUpdateRequest request) {
        bankService.updateUserBalance(userService.getUserById(request.getUserId()).username(), request.getAmount());
        return ResponseEntity.ok("Balance updated to ₹" + request.getAmount());
    }

    @GetMapping("/accounts/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public BankAccountResponseDto getAccountById(@PathVariable Long id) {
        return bankService.getAccountByid(id);
    }

    @DeleteMapping("/accounts/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> deleteAccountById(@PathVariable Long id) {
      bankService.deleteAccount(id);
        return ResponseEntity.ok("Bank account deleted successfully with ID: " + id);
    }

    @PostMapping("/approve/{loanId}")
    @PreAuthorize("hasRole('ADMIN')")
    public LoanApplication approveLoan(@PathVariable Long loanId) {
        return loanService.approveLoan(loanId);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/loans/search")
    public PagedResponse<LoanApplicationResponseDto> searchLoans(
            @RequestParam(required = false) String username,
            @RequestParam(required = false) Double minAmount,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        return loanService.searchLoans(username, minAmount, page, size);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/analytics/stats")
    public Stats getStats()
    {
        return appStats.getStats();
    }
}
