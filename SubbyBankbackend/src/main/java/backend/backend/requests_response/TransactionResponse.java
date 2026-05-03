package backend.backend.requests_response;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class TransactionResponse {
    @NotNull
    private String senderAccount;
    @NotNull
    private String receiverAccount;
    @NotNull
    private double amount;
    @NotNull
    private double balanceAfter;
    @NotNull
    private LocalDateTime timestamp;
    @NotNull
    private int isForeign;
}
