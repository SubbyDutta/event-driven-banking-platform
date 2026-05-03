package backend.backend.controller;

import backend.backend.model.BankAccount;
import backend.backend.model.User;
import backend.backend.requests_response.*;
import backend.backend.service.BankService;
import backend.backend.service.UserService;
import backend.backend.security.JwtUtil;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;
    private final JwtUtil jwtUtil;
    private final BankService bankService;

    @PreAuthorize("permitAll()")
    @GetMapping("/health")
    public String health() {
        return "OK";
    }

    @PreAuthorize("permitAll()")
    @PostMapping("/signup")
    public ResponseEntity<String> signup(@RequestBody User user) {
        userService.registerUser(user);
        return ResponseEntity.ok("Signup successful!");
    }

    @PreAuthorize("permitAll()")
    @PostMapping("/login")
    public TokenResponse login(@RequestBody AuthRequest request) {
        User user = userService.authenticate(request.getUsername(), request.getPassword());
        if (user == null) throw new RuntimeException("Invalid username or password");

        String token = jwtUtil.generateToken(user.getUsername(), user.getRole());
        return new TokenResponse(token, user.getRole());
    }

    // TODO rate-limit /forgot-password (per-IP and per-email) before prod.
    @PreAuthorize("permitAll()")
    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, String>> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        userService.sendPasswordResetOtp(request.getEmail());
        return ResponseEntity.ok(Map.of("message", "If the email exists, an OTP has been sent."));
    }

    @PreAuthorize("permitAll()")
    @PostMapping("/reset-password")
    public ResponseEntity<Map<String, String>> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        userService.resetPasswordWithOtp(request.getEmail(), request.getOtp(), request.getNewPassword());
        return ResponseEntity.ok(Map.of("message", "Password updated."));
    }

    @PreAuthorize("hasAuthority('USER')")
    @PostMapping("/create-account")
    public ResponseEntity<?> createBankAccount(@Valid @RequestBody AccountRequest request) {
        User user = userService.ifUserExists(request.getUsername());
        BankAccount account = bankService.createAccount(user, request.getType());
        return ResponseEntity.ok(account);
    }

    public static class TokenResponse {
        private final String token;
        private final String role;
        public TokenResponse(String token, String role) {
            this.token = token;
            this.role = role;
        }
        public String getToken() { return token; }
        public String getRole() { return role; }
    }
}