package backend.backend.requests_response;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record PaymentVerifyRequest(
        @NotNull
        String razorpay_order_id,
        @NotNull
        String razorpay_payment_id,
        @NotNull
        String razorpay_signature,
        @NotNull
        @Positive
        String amount,
        @NotNull
        String username,
        @NotNull
        String key
) {}
