package backend.backend.messaging.consumer.loan;

import backend.backend.configuration.FrontEndProperties;
import backend.backend.events.LoanFinalized;
import backend.backend.messaging.BaseSqsHandler;
import backend.backend.messaging.IdempotencyGuard;
import backend.backend.messaging.SnsEnvelopeParser;
import backend.backend.model.User;
import backend.backend.repository.UserRepository;
import backend.backend.service.EmailService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.awspring.cloud.sqs.annotation.SqsListener;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.util.Map;

/**
 * Drains {@code subby-email-notify} — subscribed to {@code subby-notifications}
 * with a filter matching {@code eventType=LoanFinalized}. Renders the
 * appropriate plain-text template and hands the body to {@link EmailService}.
 *
 * <p>Keeps the same non-retry policy as the KYC consumer — SMTP failures
 * bubble up so SQS redelivers, and persistent failures DLQ after the
 * redrive-policy budget.
 */
@Component
public class LoanEmailNotificationConsumer extends BaseSqsHandler<LoanFinalized> {

    private static final DecimalFormat INR = new DecimalFormat("#,##,##0.00");

    private final UserRepository userRepository;
    private final EmailService emailService;
    private final FrontEndProperties frontEndProperties;

    public LoanEmailNotificationConsumer(ObjectMapper objectMapper,
                                         IdempotencyGuard idempotencyGuard,
                                         SnsEnvelopeParser envelopeParser,
                                         MeterRegistry meterRegistry,
                                         PlatformTransactionManager txManager,
                                         UserRepository userRepository,
                                         EmailService emailService,
                                         FrontEndProperties frontEndProperties) {
        super(objectMapper, idempotencyGuard, envelopeParser, meterRegistry, txManager);
        this.userRepository = userRepository;
        this.emailService = emailService;
        this.frontEndProperties = frontEndProperties;
    }

    @SqsListener("${subby.queues.email-notify}")
    public void onMessage(String rawBody) {
        handle(rawBody);
    }

    @Override
    protected Class<LoanFinalized> eventClass() {
        return LoanFinalized.class;
    }

    @Override
    protected void process(LoanFinalized event) {
        if (event.getUserId() == null) return;

        Long userId;
        try { userId = Long.parseLong(event.getUserId()); }
        catch (NumberFormatException e) { return; }

        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            log.warn("loan.email user not found userId={}", userId);
            return;
        }
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            log.warn("loan.email skipped: user has no email userId={}", userId);
            return;
        }

        String decision = event.getDecision() == null ? "" : event.getDecision().toUpperCase();
        String template;
        String subject;
        switch (decision) {
            case "APPROVED" -> {
                template = "loan-approved";
                subject = "Your loan has been approved";
            }
            case "REJECTED" -> {
                template = "loan-rejected";
                subject = "Loan application decision";
            }
            case "MANUAL_REVIEW" -> {
                template = "loan-manual-review";
                subject = "Your loan application is under review";
            }
            default -> {
                log.info("loan.email skipped unknown decision={} loanAppId={}",
                        decision, event.getLoanAppId());
                return;
            }
        }

        String frontend = frontEndProperties.geturl();
        if (frontend == null) frontend = "";

        String rendered = render(template, Map.ofEntries(
                Map.entry("name", firstNonBlank(user.getFirstname(), user.getUsername(), "there")),
                Map.entry("loanAppId", safe(event.getLoanAppId())),
                Map.entry("amount", INR.format(event.getAmount())),
                Map.entry("tenureMonths", event.getTenureMonths() == null ? "6" : event.getTenureMonths().toString()),
                Map.entry("interestRate", event.getInterestRate() == null ? "" : event.getInterestRate().toPlainString()),
                Map.entry("monthlyEmi", event.getMonthlyEmi() == null ? "" : INR.format(event.getMonthlyEmi())),
                Map.entry("firstDueDate", safe(event.getFirstDueDate())),
                Map.entry("reason", safe(event.getReason())),
                Map.entry("loanScheduleUrl", frontend + "/loans/" + safe(event.getLoanAppId()) + "/status")
        ));

        emailService.sendEmail(user.getEmail(), subject, rendered);
        log.info("loan.email.sent userId={} loanAppId={} decision={} to={}",
                userId, event.getLoanAppId(), decision, user.getEmail());
    }

    private static String render(String templateName, Map<String, String> vars) {
        String raw = load("templates/" + templateName + ".txt");
        String out = raw;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            out = out.replace("{{" + e.getKey() + "}}",
                    e.getValue() == null ? "" : e.getValue());
        }
        return out;
    }

    private static String load(String classpath) {
        URL url = LoanEmailNotificationConsumer.class.getClassLoader().getResource(classpath);
        if (url == null) return "Loan decision update. Reference: {{loanAppId}}";
        try (InputStream in = url.openStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read email template " + classpath, e);
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return "";
    }

    private static String safe(Object v) { return v == null ? "" : v.toString(); }
}
