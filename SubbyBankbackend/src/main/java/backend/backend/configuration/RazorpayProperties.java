package backend.backend.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "razorpay")
public class RazorpayProperties {

    private String keyId;
    private String secret;

    /**
     * Razorpay webhook secret. Configured separately from the API secret in
     * the Razorpay dashboard (Settings → Webhooks). Used to verify the HMAC
     * signature on inbound webhook callbacks. Leave blank in environments
     * where webhooks aren't wired up — the handler will reject every call
     * with 401.
     */
    private String webhookSecret;

    public String getKeyId() { return keyId; }
    public void setKeyId(String keyId) { this.keyId = keyId; }

    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret; }

    public String getWebhookSecret() { return webhookSecret; }
    public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }
}
