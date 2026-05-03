package backend.backend.messaging.consumer;

import backend.backend.events.KycDecisionMade;
import backend.backend.messaging.BaseSqsHandler;
import backend.backend.messaging.IdempotencyGuard;
import backend.backend.messaging.SnsEnvelopeParser;
import backend.backend.model.KycStatus;
import backend.backend.model.User;
import backend.backend.repository.UserRepository;
import backend.backend.service.EmailService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.awspring.cloud.sqs.annotation.SqsListener;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Map;

/**
 * Drains {@code subby-kyc-decision} — subscribed to {@code subby-kyc-events}
 * with filter {@code eventType=KycDecisionMade}. Emits an email per decision
 * using a tiny {@link KycEmailTemplates} helper (there's no Thymeleaf in this
 * project; prompt 5 introduces the generic notification consumer that will
 * replace this one).
 *
 * <p>Intentionally scoped narrowly: no retry of SMTP failures beyond what SQS
 * provides — if email send throws, SQS redelivers and we try again. A SMTP 5xx
 * that never succeeds will DLQ after three tries, which is the desired end state.
 */
@Component
public class KycEmailNotificationConsumer extends BaseSqsHandler<KycDecisionMade> {

    private final UserRepository userRepository;
    private final EmailService emailService;

    public KycEmailNotificationConsumer(ObjectMapper objectMapper,
                                        IdempotencyGuard idempotencyGuard,
                                        SnsEnvelopeParser envelopeParser,
                                        MeterRegistry meterRegistry,
                                        PlatformTransactionManager txManager,
                                        UserRepository userRepository,
                                        EmailService emailService) {
        super(objectMapper, idempotencyGuard, envelopeParser, meterRegistry, txManager);
        this.userRepository = userRepository;
        this.emailService = emailService;
    }

    @SqsListener("${subby.queues.kyc-decision}")
    public void onMessage(String rawBody) {
        handle(rawBody);
    }

    @Override
    protected Class<KycDecisionMade> eventClass() {
        return KycDecisionMade.class;
    }

    @Override
    protected void process(KycDecisionMade event) {
        if (event.getUserId() == null) return;
        Long userId;
        try {
            userId = Long.parseLong(event.getUserId());
        } catch (NumberFormatException e) {
            return;
        }
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            log.warn("kyc.email user not found userId={}", userId);
            return;
        }
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            log.warn("kyc.email skipped: user has no email. userId={}", userId);
            return;
        }

        KycStatus decision;
        try {
            decision = KycStatus.valueOf(event.getDecision());
        } catch (IllegalArgumentException e) {
            log.warn("kyc.email unknown decision={} userId={}", event.getDecision(), userId);
            return;
        }

        String name = firstNonBlank(user.getFirstname(), user.getUsername(), "there");
        KycEmailTemplates.Rendered rendered = KycEmailTemplates.render(
                decision,
                Map.of(
                        "name", name,
                        "reason", event.getReason() == null ? "" : event.getReason()));
        if (rendered == null) {
            return;
        }

        emailService.sendEmail(user.getEmail(), rendered.subject(), rendered.body());
        log.info("kyc.email.sent userId={} decision={} to={}", userId, decision, user.getEmail());
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return "";
    }
}
