package backend.backend.messaging.consumer;

import backend.backend.configuration.FrontEndProperties;
import backend.backend.events.LoanPendingAdminDecision;
import backend.backend.messaging.IdempotencyGuard;
import backend.backend.messaging.SnsEnvelopeParser;
import backend.backend.model.User;
import backend.backend.repository.UserRepository;
import backend.backend.service.EmailService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminLoanPendingConsumerTest {

    @Mock IdempotencyGuard idempotencyGuard;
    @Mock PlatformTransactionManager txManager;
    @Mock EmailService emailService;
    @Mock UserRepository userRepository;

    ObjectMapper objectMapper;
    SnsEnvelopeParser envelopeParser;
    FrontEndProperties frontEndProperties;
    AdminLoanPendingConsumer consumer;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        envelopeParser = new SnsEnvelopeParser(objectMapper);
        frontEndProperties = new FrontEndProperties();
        frontEndProperties.seturl("https://app.subby.example");
        consumer = new AdminLoanPendingConsumer(objectMapper, idempotencyGuard, envelopeParser,
                new SimpleMeterRegistry(), txManager, userRepository, emailService, frontEndProperties);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    }

    @Test
    void process_happyPath_fansOutEmailToEachAdmin() {
        when(idempotencyGuard.claim(any(UUID.class), eq("AdminLoanPendingConsumer"))).thenReturn(true);
        User a1 = admin("admin1", "admin1@subby.example");
        User a2 = admin("admin2", "admin2@subby.example");
        User a3 = admin("admin3", "admin3@subby.example");
        when(userRepository.findByRole("ADMIN")).thenReturn(List.of(a1, a2, a3));

        User applicant = new User();
        applicant.setId(42L);
        applicant.setFirstname("Alice");
        applicant.setUsername("alice");
        when(userRepository.findById(42L)).thenReturn(Optional.of(applicant));

        LoanPendingAdminDecision event = pendingEvent("loan-app-123", "42", 500_000d, 6,
                new BigDecimal("12.00"), "ML approved at band B");

        consumer.handle(toJson(event));

        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> subj = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailService, times(3)).sendEmail(to.capture(), subj.capture(), body.capture());
        assertThat(to.getAllValues())
                .containsExactly("admin1@subby.example", "admin2@subby.example", "admin3@subby.example");
        assertThat(subj.getValue()).contains("Loan needs admin decision");
        assertThat(subj.getValue()).contains("loan-app-123");
        assertThat(body.getValue()).contains("loan-app-123");
        assertThat(body.getValue()).contains("Alice");
        assertThat(body.getValue()).contains("12");
        assertThat(body.getValue()).contains("https://app.subby.example/admin/loans?status=PENDING_ADMIN_DECISION");
    }

    @Test
    void process_idempotentReplay_secondCallIsNoOp() {
        when(idempotencyGuard.claim(any(UUID.class), eq("AdminLoanPendingConsumer")))
                .thenReturn(true)
                .thenReturn(false);
        when(userRepository.findByRole("ADMIN"))
                .thenReturn(List.of(admin("admin1", "admin1@subby.example")));
        when(userRepository.findById(anyLong())).thenReturn(Optional.empty());

        LoanPendingAdminDecision event = pendingEvent("loan-app-123", "42", 100_000d, 6,
                new BigDecimal("12.00"), "x");
        String body = toJson(event);

        consumer.handle(body);
        consumer.handle(body);

        verify(emailService, times(1)).sendEmail(anyString(), anyString(), anyString());
    }

    @Test
    void process_noAdminUsers_skipsAndDoesNotSend() {
        when(idempotencyGuard.claim(any(UUID.class), eq("AdminLoanPendingConsumer"))).thenReturn(true);
        when(userRepository.findByRole("ADMIN")).thenReturn(List.of());

        LoanPendingAdminDecision event = pendingEvent("loan-app-123", "42", 100_000d, 6,
                new BigDecimal("12.00"), "x");

        consumer.handle(toJson(event));

        verifyNoInteractions(emailService);
    }

    @Test
    void process_adminWithoutEmail_skippedButOthersStillEmailed() {
        when(idempotencyGuard.claim(any(UUID.class), eq("AdminLoanPendingConsumer"))).thenReturn(true);
        User a1 = admin("admin1", null);
        User a2 = admin("admin2", "admin2@subby.example");
        when(userRepository.findByRole("ADMIN")).thenReturn(List.of(a1, a2));
        when(userRepository.findById(anyLong())).thenReturn(Optional.empty());

        LoanPendingAdminDecision event = pendingEvent("loan-app-123", "42", 100_000d, 6,
                new BigDecimal("12.00"), "x");

        consumer.handle(toJson(event));

        verify(emailService, times(1)).sendEmail(eq("admin2@subby.example"), anyString(), anyString());
        verify(emailService, never()).sendEmail(eq(null), anyString(), anyString());
    }

    @Test
    void process_unrecoverableError_propagatesAsRuntimeException() {
        when(idempotencyGuard.claim(any(UUID.class), eq("AdminLoanPendingConsumer"))).thenReturn(true);
        when(userRepository.findByRole("ADMIN"))
                .thenThrow(new RuntimeException("DB unavailable"));

        LoanPendingAdminDecision event = pendingEvent("loan-app-123", "42", 100_000d, 6,
                new BigDecimal("12.00"), "x");

        try {
            consumer.handle(toJson(event));
            org.junit.jupiter.api.Assertions.fail("expected RuntimeException to propagate");
        } catch (RuntimeException expected) {
            assertThat(expected.getMessage()).contains("DB unavailable");
        }
    }

    private static User admin(String username, String email) {
        User u = new User();
        u.setUsername(username);
        u.setEmail(email);
        u.setRole("ADMIN");
        return u;
    }

    private static LoanPendingAdminDecision pendingEvent(String loanAppId, String userId, double amount,
                                                         Integer tenureMonths, BigDecimal interestRate,
                                                         String reason) {
        return new LoanPendingAdminDecision(
                UUID.randomUUID(), Instant.now(), 1, loanAppId,
                loanAppId, userId, reason, 1L, amount, tenureMonths, interestRate);
    }

    private String toJson(LoanPendingAdminDecision event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
