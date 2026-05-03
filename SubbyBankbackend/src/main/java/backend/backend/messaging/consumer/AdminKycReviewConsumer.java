package backend.backend.messaging.consumer;

import backend.backend.configuration.FrontEndProperties;
import backend.backend.events.AdminKycReviewNeeded;
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

import java.util.List;
import java.util.Map;

@Component
public class AdminKycReviewConsumer extends BaseSqsHandler<AdminKycReviewNeeded> {

    private final UserRepository userRepository;
    private final EmailService emailService;
    private final FrontEndProperties frontEndProperties;

    public AdminKycReviewConsumer(ObjectMapper objectMapper,
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

    @SqsListener("${subby.queues.admin-kyc-review}")
    public void onMessage(String rawBody) {
        handle(rawBody);
    }

    @Override
    protected Class<AdminKycReviewNeeded> eventClass() {
        return AdminKycReviewNeeded.class;
    }

    @Override
    protected void process(AdminKycReviewNeeded event) {
        List<User> admins = userRepository.findByRole("ADMIN");
        if (admins.isEmpty()) {
            log.warn("admin-kyc.email skipped: no admin users userId={}", event.getUserId());
            return;
        }

        String frontend = frontEndProperties.geturl();
        if (frontend == null) frontend = "";

        String body = EmailTemplates.render("admin-kyc-review", Map.of(
                "applicantUserId", safe(event.getUserId()),
                "applicantUsername", safe(event.getUsername()),
                "applicantEmail", safe(event.getApplicantEmail()),
                "findocApplicationId", safe(event.getFindocApplicationId()),
                "reason", safe(event.getReason()),
                "adminQueueUrl", frontend + "/admin/kyc?status=KYC_MANUAL_REVIEW"));

        String subject = "KYC needs manual review — user " + safe(event.getUsername());
        for (User admin : admins) {
            if (admin.getEmail() == null || admin.getEmail().isBlank()) {
                log.warn("admin-kyc.email skipped: admin has no email username={}", admin.getUsername());
                continue;
            }
            emailService.sendEmail(admin.getEmail(), subject, body);
            log.info("admin-kyc.email.sent userId={} adminUsername={} to={}",
                    event.getUserId(), admin.getUsername(), admin.getEmail());
        }
    }

    private static String safe(Object v) { return v == null ? "" : v.toString(); }
}
