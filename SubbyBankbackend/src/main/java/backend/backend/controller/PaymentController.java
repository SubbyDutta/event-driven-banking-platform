package backend.backend.controller;

import backend.backend.requests_response.PaymentVerifyRequest;
import backend.backend.security.CustomUserDetails;
import backend.backend.service.RazorpayService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/payment")
@RequiredArgsConstructor
public class PaymentController {

    private final RazorpayService paymentService;

    @PreAuthorize("hasRole('USER')")
    @PostMapping("/create-order")
    public ResponseEntity<?> createOrder(@RequestBody Map<String, Object> data,
                                         @AuthenticationPrincipal CustomUserDetails userDetails) {

        return ResponseEntity.ok(paymentService.prepareCheckoutOrder(data, userDetails.getUsername()));
    }

    @PreAuthorize("hasRole('USER')")
    @PostMapping("/verify")
    public ResponseEntity<?> verifyPayment(
            @RequestBody @Valid PaymentVerifyRequest request
    ) {
        paymentService.verifyAndCredit(request,request.key());
        return ResponseEntity.ok(
                Map.of("success", true, "message", "Payment verified & balance updated")
        );
    }
}
