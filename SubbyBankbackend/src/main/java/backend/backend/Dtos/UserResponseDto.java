package backend.backend.Dtos;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDateTime;

public record UserResponseDto(
        @NotNull
        @Positive
        Long id,
        @NotNull
        String username,
        @NotNull
        String firstname,
        @NotNull
        String lastname,
        @NotNull
        String email,
        @NotNull
        String mobile,
        @NotNull
        String role,
        @NotNull
        int creditScore,
        LocalDateTime updatedAt,
        boolean hasLoan,
        double loanAmount,
        double remaining,
        LocalDateTime dueDate

) {
}
