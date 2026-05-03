package backend.backend.requests_response;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class BalanceUpdateRequest {
    @NotNull
    private Long userId;
    @NotNull
    @Positive
    private double amount;
}
