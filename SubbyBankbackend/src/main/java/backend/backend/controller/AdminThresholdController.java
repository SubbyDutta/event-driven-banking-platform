package backend.backend.controller;

import backend.backend.service.findoc.FindocVerifyClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/thresholds")
@PreAuthorize("hasRole('ADMIN')")
public class AdminThresholdController {

    private final FindocVerifyClient findoc;

    public AdminThresholdController(FindocVerifyClient findoc) {
        this.findoc = findoc;
    }

    @GetMapping
    public ResponseEntity<JsonNode> list() {
        return ResponseEntity.ok(findoc.listPolicyThresholds());
    }

    @PutMapping("/{key}")
    public ResponseEntity<JsonNode> update(@PathVariable String key,
                                           @RequestBody Map<String, Object> body) {
        Object value = body.get("value");
        Object reason = body.get("reason");
        if (value == null) {
            return ResponseEntity.badRequest().body(null);
        }
        if (reason == null || reason.toString().isBlank()) {
            return ResponseEntity.badRequest().body(null);
        }
        return ResponseEntity.ok(findoc.updatePolicyThreshold(key,
                Map.of("value", value, "reason", reason)));
    }
}
