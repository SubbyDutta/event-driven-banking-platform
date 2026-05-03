package backend.backend.controller;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import backend.backend.service.RazorpayService;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/payment")
@RequiredArgsConstructor
public class RazorpayWebhookController {

    private static final Logger log = LoggerFactory.getLogger(RazorpayWebhookController.class);

    private final RazorpayService razorpayService;

    @PreAuthorize("permitAll()")
    @PostMapping("/webhook")
    public ResponseEntity<?> handle(@RequestBody String rawBody,
                                    @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature) {
        if (signature == null || signature.isBlank()) {
            log.warn("razorpay webhook missing X-Razorpay-Signature header");
            return ResponseEntity.status(401).body(Map.of("error", "missing_signature"));
        }
        if (!razorpayService.verifyWebhookSignature(rawBody, signature)) {
            log.warn("razorpay webhook signature verification failed");
            return ResponseEntity.status(401).body(Map.of("error", "invalid_signature"));
        }

        RazorpayService.WebhookOutcome outcome = razorpayService.processWebhookPayload(rawBody);
        return ResponseEntity.status(outcome.httpStatus).body(outcome.body);
    }
}
