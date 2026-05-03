package backend.backend.messaging.consumer;

import backend.backend.configuration.FrontEndProperties;
import backend.backend.events.PasswordChanged;
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
public class PasswordChangedEmailConsumer extends BaseSqsHandler<PasswordChanged> {

    private final EmailService emailService;
    private final FrontEndProperties frontEndProperties;

    public PasswordChangedEmailConsumer(ObjectMapper objectMapper,
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

    @SqsListener("${subby.queues.password-changed-email}")
    public void onMessage(String rawBody) {
        handle(rawBody);
    }

    @Override
    protected Class<PasswordChanged> eventClass() {
        return PasswordChanged.class;
    }

    @Override
    protected void process(PasswordChanged event) {
        if (event.getEmail() == null || event.getEmail().isBlank()) {
            log.warn("password-changed.email skipped: missing email userId={}", event.getUserId());
            return;
        }

        String frontend = frontEndProperties.geturl();
        if (frontend == null) frontend = "";

        String body = EmailTemplates.render("password-changed", Map.of(
                "name", safe(event.getFirstName(), "there"),
                "occurredAt", safe(event.getOccurredAtIso(), ""),
                "contactSupportUrl", frontend + "/support"));

        emailService.sendEmail(event.getEmail(), "Your SubbyBank password was changed", body);
        log.info("password-changed.email.sent userId={} to={}", event.getUserId(), event.getEmail());
    }

    private static String safe(String v, String fallback) {
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
