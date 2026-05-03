package backend.backend.messaging.consumer;

import backend.backend.configuration.FrontEndProperties;
import backend.backend.events.UserSignedUp;
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
public class WelcomeEmailConsumer extends BaseSqsHandler<UserSignedUp> {

    private final EmailService emailService;
    private final FrontEndProperties frontEndProperties;

    public WelcomeEmailConsumer(ObjectMapper objectMapper,
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

    @SqsListener("${subby.queues.welcome-email}")
    public void onMessage(String rawBody) {
        handle(rawBody);
    }

    @Override
    protected Class<UserSignedUp> eventClass() {
        return UserSignedUp.class;
    }

    @Override
    protected void process(UserSignedUp event) {
        if (event.getEmail() == null || event.getEmail().isBlank()) {
            log.warn("welcome.email skipped: missing email userId={}", event.getUserId());
            return;
        }

        String frontend = frontEndProperties.geturl();
        if (frontend == null) frontend = "";

        String body = EmailTemplates.render("welcome", Map.of(
                "name", safe(event.getFirstName(), "there"),
                "username", safe(event.getUsername(), ""),
                "loginUrl", frontend + "/login"));

        emailService.sendEmail(event.getEmail(), "Welcome to SubbyBank", body);
        log.info("welcome.email.sent userId={} to={}", event.getUserId(), event.getEmail());
    }

    private static String safe(String v, String fallback) {
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
