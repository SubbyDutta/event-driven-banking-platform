package backend.backend.Dtos;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record TransactionDto(
        @NotNull
        @Positive
        Long id,
        @NotNull
        String senderAccount,
        @NotNull
        String receiverAccount,
        @NotNull
        @Positive
        double amount,
        @NotNull
        double fraudProbability,
        @NotNull
        int isFraud,
        @NotNull
        int userId,
        @NotNull
        int isForeign,
        @NotNull
        int hour,

        String timestamp
) {}
