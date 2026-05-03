package backend.backend.controller;

import backend.backend.model.User;
import backend.backend.repository.UserRepository;
import backend.backend.security.CustomUserDetails;
import backend.backend.service.AdminKycOverrideService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/kyc")
@RequiredArgsConstructor
public class KycController {

    private final UserRepository userRepository;
    private final AdminKycOverrideService kycService;

    @PreAuthorize("isAuthenticated()")
    @PostMapping(path = "/apply", consumes = "multipart/form-data")
    @Transactional
    public ResponseEntity<?> apply(
            @RequestParam("aadhaar") MultipartFile aadhaar,
            @RequestParam("pan") MultipartFile pan,
            @RequestParam(value = "selfie", required = false) MultipartFile selfie,
            @AuthenticationPrincipal CustomUserDetails principal) {

        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Unauthorized"));
        }
        User user = userRepository.findById(principal.getUser_id())
                .orElseThrow(() -> new IllegalStateException("User not found: " + principal.getUsername()));

        AdminKycOverrideService.ApplyResult r = kycService.applyKycWithDocuments(user, aadhaar, pan, selfie);
        return ResponseEntity.status(r.httpStatus).body(r.body);
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/status")
    public ResponseEntity<?> status(@AuthenticationPrincipal CustomUserDetails principal) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Unauthorized"));
        }
        User user = userRepository.findById(principal.getUser_id())
                .orElseThrow(() -> new IllegalStateException("User not found"));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kycStatus", user.getKycStatus().name());
        body.put("kycSubmittedAt", user.getKycSubmittedAt());
        body.put("kycDecidedAt", user.getKycDecidedAt());
        body.put("accountActive", user.isAccountActive());

        if (user.getKycStatus().isTerminal()) {
            body.put("decision", user.getKycStatus().name());
            body.put("reason", user.getKycDecisionReason());
        }

        body.put("report", kycService.kycReportPreview(user.getKycReportJson()));
        return ResponseEntity.ok(body);
    }
}
