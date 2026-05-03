package backend.backend.requests_response;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * After V3 the bank account creation flow no longer re-collects Aadhaar/PAN —
 * those values come off the KYC-verified user row. Only the account type
 * (SAVINGS / CURRENT / etc.) is still a runtime choice. Username identifies
 * the user for admin/self-serve distinction; the KYC gate lives in BankService.
 */
@Data
public class AccountRequest {
    @NotNull
    private String username;
    @NotNull
    private String type;
}
