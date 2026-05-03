package backend.backend.messaging.consumer;

import backend.backend.configuration.FrontEndProperties;
import backend.backend.events.LoanDisbursed;
import backend.backend.messaging.BaseSqsHandler;
import backend.backend.messaging.IdempotencyGuard;
import backend.backend.messaging.SnsEnvelopeParser;
import backend.backend.repository.UserRepository;
import backend.backend.service.EmailService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.awspring.cloud.sqs.annotation.SqsListener;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.Map;

@Component
public class LoanDisbursedEmailConsumer extends BaseSqsHandler<LoanDisbursed> {

    private static final DecimalFormat INR = new DecimalFormat("#,##,##0.00");

    private final EmailService emailService;
    private final FrontEndProperties frontEndProperties;
    private final UserRepository userRepository;

    public LoanDisbursedEmailConsumer(ObjectMapper objectMapper,
                                      IdempotencyGuard idempotencyGuard,
                                      SnsEnvelopeParser envelopeParser,
                                      MeterRegistry meterRegistry,
                                      PlatformTransactionManager txManager,
                                      EmailService emailService,
                                      FrontEndProperties frontEndProperties,
                                      UserRepository userRepository) {
        super(objectMapper, idempotencyGuard, envelopeParser, meterRegistry, txManager);
        this.emailService = emailService;
        this.frontEndProperties = frontEndProperties;
        this.userRepository = userRepository;
    }

    @SqsListener("${subby.queues.loan-disbursed-email}")
    public void onMessage(String rawBody) {
        handle(rawBody);
    }

    @Override
    protected Class<LoanDisbursed> eventClass() {
        return LoanDisbursed.class;
    }

    @Override
    protected void process(LoanDisbursed event) {
        if (event.getEmail() == null || event.getEmail().isBlank()) {
            log.warn("loan-disbursed.email skipped: missing email userId={} loanAppId={}",
                    event.getUserId(), event.getLoanAppId());
            return;
        }

        String frontend = frontEndProperties.geturl();
        if (frontend == null) frontend = "";

        String name = lookupFirstName(event.getUserId());

        String body = EmailTemplates.render("loan-disbursed", Map.of(
                "name", safe(name, "there"),
                "loanAppId", safe(event.getLoanAppId(), ""),
                "amount", formatAmount(event.getAmount()),
                "accountNumberMasked", safe(event.getAccountNumberMasked(), ""),
                "repaymentScheduleUrl", frontend + "/loans/" + safe(event.getLoanAppId(), "") + "/schedule"));

        String subject = "Your SubbyBank loan has been disbursed";
        emailService.sendEmail(event.getEmail(), subject, body);
        log.info("loan-disbursed.email.sent userId={} loanAppId={} to={}",
                event.getUserId(), event.getLoanAppId(), event.getEmail());
    }

    private String lookupFirstName(String userId) {
        if (userId == null) return "";
        try {
            return userRepository.findById(Long.parseLong(userId))
                    .map(u -> firstNonBlank(u.getFirstname(), u.getUsername(), ""))
                    .orElse("");
        } catch (NumberFormatException e) {
            return "";
        }
    }

    private static String formatAmount(BigDecimal v) {
        return v == null ? "0.00" : INR.format(v);
    }

    private static String safe(String v, String fallback) {
        return (v == null || v.isBlank()) ? fallback : v;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return "";
    }
}
