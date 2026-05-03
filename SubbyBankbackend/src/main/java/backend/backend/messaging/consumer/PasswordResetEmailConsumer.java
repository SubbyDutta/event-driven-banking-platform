package backend.backend.messaging.consumer;

import backend.backend.configuration.FrontEndProperties;
import backend.backend.events.PasswordResetRequested;
import backend.backend.messaging.BaseSqsHandler;
import backend.backend.messaging.IdempotencyGuard;
import backend.backend.messaging.SnsEnvelopeParser;
import backend.backend.service.EmailService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.awspring.cloud.sqs.annotation.SqsListener;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Map;

@Component
public class PasswordResetEmailConsumer extends BaseSqsHandler<PasswordResetRequested> {

    private final EmailService emailService;
    private final FrontEndProperties frontEndProperties;

    public PasswordResetEmailConsumer(ObjectMapper objectMapper,
                                      IdempotencyGuard idempotencyGuard,
                                      SnsEnvelopeParser envelopeParser,
                                      MeterRegistry meterRegistry,
                                      PlatformTransactionManager txManager,
                                      EmailService emailService,
                                      FrontEndProperties frontEndProperties) {
        super(objectMapper, idempotencyGuard, envelopeParser, meterRegistry, txManager);
        this.emailService = emailService;
        this.frontEndProperties = frontEndProperties;
    }

    @SqsListener("${subby.queues.password-reset-email}")
    public void onMessage(String rawBody) {
        handle(rawBody);
    }

    @Override
    protected Class<PasswordResetRequested> eventClass() {
        return PasswordResetRequested.class;
    }

    @Override
    protected void process(PasswordResetRequested event) {
        if (event.getEmail() == null || event.getEmail().isBlank()) {
            log.warn("password-reset.email skipped: missing email userId={}", event.getUserId());
            return;
        }
        if (event.getOtp() == null || event.getOtp().isBlank()) {
            log.warn("password-reset.email skipped: missing otp userId={}", event.getUserId());
            return;
        }

        String frontend = frontEndProperties.geturl();
        if (frontend == null) frontend = "";

        String body = EmailTemplates.render("password-reset", Map.of(
                "name", safe(event.getFirstName(), "there"),
                "otp", event.getOtp(),
                "expiresAt", safe(event.getExpiresAt(), ""),
                "resetUrl", frontend + "/reset-password"));

        emailService.sendEmail(event.getEmail(), "Reset your SubbyBank password", body);
        log.info("password-reset.email.sent userId={} to={}", event.getUserId(), event.getEmail());
    }

    private static String safe(String v, String fallback) {
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
