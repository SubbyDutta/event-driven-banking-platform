package backend.backend.requests_response;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class AccountDetailsResponse {
    @NotNull
    @Positive
    private String accountNumber;
    @NotNull
    @Positive
    private double balance;
    @NotNull
    private String ownerName;
    private List<TransactionResponse> transactions;
}
