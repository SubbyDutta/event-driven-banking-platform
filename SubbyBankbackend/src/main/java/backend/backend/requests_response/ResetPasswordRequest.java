package backend.backend.requests_response;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ResetPasswordRequest {
    @NotNull
    private String email;
    @NotNull
    private String otp;
    @NotNull
    private String newPassword;
}
