package backend.backend.Dtos;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDateTime;

public record LoanApplicationResponseDto(

        @NotNull
        @Positive
        Long id,
        @NotNull
        String username,
        @NotNull
        @Positive
        double amount,
        @NotNull
        @Positive
        double due_amount,
        @NotNull
        boolean approved ,
        @NotNull
        String status,
        @NotNull
        @Positive
        int monthsRemaining,
        @NotNull
        @Positive
        double monthlyEmi,
        @NotNull
        LocalDateTime approvedAt,
        @NotNull
         LocalDateTime nextDueDate

) {
}
