package backend.backend.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class FindocKeyController {

    @Value("${subby.findoc.api-key:}")
    private String findocApiKey;

    @GetMapping("/findoc-key")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, String> getFindocKey() {
        return Map.of("key", findocApiKey);
    }
}
