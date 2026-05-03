package backend.backend.Dtos;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record BankAccountResponseDto(
        @NotNull
        @Positive
        Long id,
        @NotNull
        @Positive
        String accountNumber,
        @NotNull
        String type,
        @NotNull
        @Positive
        double balance,
        @NotNull
        String username,
        boolean isBlocked,
        boolean isVerified

) {
}
