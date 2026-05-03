package backend.backend.requests_response;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class TransferRequest {
    @NotNull
    private String key;
    @NotNull
    private String senderAccount;
    @NotNull
    private String receiverAccount;
    @NotNull
    @Positive
    private double amount;
    @NotNull
    private String password;

}
