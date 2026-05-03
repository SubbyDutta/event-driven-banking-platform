package backend.backend.messaging.consumer;

import backend.backend.configuration.FrontEndProperties;
import backend.backend.events.TransactionPosted;
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
public class TransactionEmailConsumer extends BaseSqsHandler<TransactionPosted> {

    private static final DecimalFormat INR = new DecimalFormat("#,##,##0.00");

    private final EmailService emailService;
    private final FrontEndProperties frontEndProperties;
    private final UserRepository userRepository;

    public TransactionEmailConsumer(ObjectMapper objectMapper,
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

    @SqsListener("${subby.queues.transaction-email}")
    public void onMessage(String rawBody) {
        handle(rawBody);
    }

    @Override
    protected Class<TransactionPosted> eventClass() {
        return TransactionPosted.class;
    }

    @Override
    protected void process(TransactionPosted event) {
        if (event.getEmail() == null || event.getEmail().isBlank()) {
            log.warn("transaction.email skipped: missing email userId={} txnRef={}",
                    event.getUserId(), event.getTxnRef());
            return;
        }
        if (event.getDirection() == null) {
            log.warn("transaction.email skipped: missing direction userId={} txnRef={}",
                    event.getUserId(), event.getTxnRef());
            return;
        }

        String frontend = frontEndProperties.geturl();
        if (frontend == null) frontend = "";

        String name = lookupFirstName(event.getUserId());
        String amountStr = formatAmount(event.getAmount());
        String balanceStr = formatAmount(event.getBalanceAfter());

        boolean credit = event.getDirection() == TransactionPosted.Direction.CREDIT;
        String template = credit ? "transaction-credited" : "transaction-debited";
        String subject = credit
                ? "₹" + amountStr + " credited to your account"
                : "₹" + amountStr + " debited from your account";

        String body = EmailTemplates.render(template, Map.of(
                "name", safe(name, "there"),
                "amount", amountStr,
                "counterparty", safe(event.getCounterparty(), "—"),
                "txnRef", safe(event.getTxnRef(), ""),
                "balanceAfter", balanceStr,
                "occurredAt", safe(event.getOccurredAtIso(), ""),
                "disputeUrl", frontend + "/transactions/" + safe(event.getTxnRef(), "") + "/dispute"));

        emailService.sendEmail(event.getEmail(), subject, body);
        log.info("transaction.email.sent userId={} direction={} txnRef={} to={}",
                event.getUserId(), event.getDirection(), event.getTxnRef(), event.getEmail());
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
