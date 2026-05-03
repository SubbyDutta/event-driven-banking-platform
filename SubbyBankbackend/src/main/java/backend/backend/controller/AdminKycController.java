package backend.backend.controller;

import backend.backend.model.KycStatus;
import backend.backend.repository.KycDecisionOverrideRepository;
import backend.backend.repository.UserRepository;
import backend.backend.service.AdminKycOverrideService;
import backend.backend.service.CachedLists;
import backend.backend.service.findoc.FindocVerifyClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import backend.backend.security.CustomUserDetails;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/kyc")
public class AdminKycController {

    private final UserRepository userRepository;
    private final KycDecisionOverrideRepository overrideRepository;
    private final FindocVerifyClient findoc;
    private final ObjectMapper objectMapper;
    private final CachedLists cachedLists;
    private final AdminKycOverrideService overrideService;

    public AdminKycController(UserRepository userRepository,
                              KycDecisionOverrideRepository overrideRepository,
                              FindocVerifyClient findoc,
                              ObjectMapper objectMapper,
                              CachedLists cachedLists,
                              AdminKycOverrideService overrideService) {
        this.userRepository = userRepository;
        this.overrideRepository = overrideRepository;
        this.findoc = findoc;
        this.objectMapper = objectMapper;
        this.cachedLists = cachedLists;
        this.overrideService = overrideService;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/users")
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) String kycStatus,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int pageSize) {

        KycStatus statusFilter = parseStatus(kycStatus);

        CachedLists.AdminKycUserPage result = cachedLists.getAdminKycUsersCached(
                statusFilter, q, page, pageSize);

        return ResponseEntity.ok(Map.of(
                "items", result.content(),
                "page", result.page(),
                "pageSize", result.size(),
                "total", result.totalElements(),
                "totalPages", result.totalPages()));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/users/{userId}")
    public ResponseEntity<?> detail(@PathVariable Long userId) {
        return overrideService.adminDetail(userId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "User not found")));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/users/{userId}/override")
    public ResponseEntity<?> override(@PathVariable Long userId,
                                      @Valid @RequestBody OverrideBody body,
                                      @AuthenticationPrincipal CustomUserDetails admin) {
        String adminName = admin != null ? admin.getUsername() : "system";
        AdminKycOverrideService.Result result = overrideService.override(
                userId, body.getDecision(), body.getReason(), body.isNotifyFindoc(), adminName);

        if (result.userNotFound) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "User not found"));
        }
        if (result.validationError != null) {
            return ResponseEntity.badRequest().body(Map.of("error", result.validationError));
        }
        return ResponseEntity.ok(result.toResponseBody(userId));
    }

    private static KycStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return KycStatus.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Data
    public static class OverrideBody {
        @NotBlank(message = "decision is required")
        private String decision;
        @NotNull(message = "reason is required")
        @NotBlank(message = "reason must not be blank")
        private String reason;
        private boolean notifyFindoc = false;
    }
}
