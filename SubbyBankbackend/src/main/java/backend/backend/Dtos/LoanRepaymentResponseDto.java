package backend.backend.Dtos;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDateTime;

public record LoanRepaymentResponseDto(
        @NotNull
         Long id,
        @NotNull
        @Positive
        Long loanId,
        @NotNull
         String username,
        @NotNull
        @Positive
        double amountPaid,
        @NotNull
         LocalDateTime paymentDate,
        @NotNull
        double remainingBalance

) {
}
