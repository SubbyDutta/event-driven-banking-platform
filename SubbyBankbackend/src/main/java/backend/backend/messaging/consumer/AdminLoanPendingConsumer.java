package backend.backend.messaging.consumer;

import backend.backend.configuration.FrontEndProperties;
import backend.backend.events.LoanPendingAdminDecision;
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

import java.text.DecimalFormat;
import java.util.List;
import java.util.Map;

@Component
public class AdminLoanPendingConsumer extends BaseSqsHandler<LoanPendingAdminDecision> {

    private static final DecimalFormat INR = new DecimalFormat("#,##,##0.00");

    private final UserRepository userRepository;
    private final EmailService emailService;
    private final FrontEndProperties frontEndProperties;

    public AdminLoanPendingConsumer(ObjectMapper objectMapper,
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

    @SqsListener("${subby.queues.admin-loan-pending}")
    public void onMessage(String rawBody) {
        handle(rawBody);
    }

    @Override
    protected Class<LoanPendingAdminDecision> eventClass() {
        return LoanPendingAdminDecision.class;
    }

    @Override
    protected void process(LoanPendingAdminDecision event) {
        List<User> admins = userRepository.findByRole("ADMIN");
        if (admins.isEmpty()) {
            log.warn("admin-loan.email skipped: no admin users loanAppId={}", event.getLoanAppId());
            return;
        }

        String frontend = frontEndProperties.geturl();
        if (frontend == null) frontend = "";

        String applicantName = lookupApplicantName(event.getUserId());
        String body = EmailTemplates.render("admin-loan-pending", Map.ofEntries(
                Map.entry("loanAppId", safe(event.getLoanAppId())),
                Map.entry("applicantUserId", safe(event.getUserId())),
                Map.entry("applicantName", applicantName),
                Map.entry("amount", INR.format(event.getAmount())),
                Map.entry("tenureMonths", event.getTenureMonths() == null ? "" : event.getTenureMonths().toString()),
                Map.entry("interestRate", event.getInterestRate() == null ? "" : event.getInterestRate().toPlainString()),
                Map.entry("reason", safe(event.getReason())),
                Map.entry("adminQueueUrl", frontend + "/admin/loans?status=PENDING_ADMIN_DECISION")
        ));

        String subject = "Loan needs admin decision — " + safe(event.getLoanAppId());
        for (User admin : admins) {
            if (admin.getEmail() == null || admin.getEmail().isBlank()) {
                log.warn("admin-loan.email skipped: admin has no email username={}", admin.getUsername());
                continue;
            }
            emailService.sendEmail(admin.getEmail(), subject, body);
            log.info("admin-loan.email.sent loanAppId={} adminUsername={} to={}",
                    event.getLoanAppId(), admin.getUsername(), admin.getEmail());
        }
    }

    private String lookupApplicantName(String userId) {
        if (userId == null) return "";
        try {
            return userRepository.findById(Long.parseLong(userId))
                    .map(u -> firstNonBlank(u.getFirstname(), u.getUsername(), ""))
                    .orElse("");
        } catch (NumberFormatException e) {
            return "";
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return "";
    }

    private static String safe(Object v) { return v == null ? "" : v.toString(); }
}
