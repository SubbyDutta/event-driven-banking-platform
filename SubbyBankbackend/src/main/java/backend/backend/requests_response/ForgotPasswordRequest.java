package backend.backend.requests_response;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ForgotPasswordRequest {
    @NotNull
        private String email;
    }
