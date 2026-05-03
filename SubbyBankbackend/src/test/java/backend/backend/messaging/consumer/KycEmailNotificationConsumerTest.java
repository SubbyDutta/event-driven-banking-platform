package backend.backend.messaging.consumer;

import backend.backend.events.KycDecisionMade;
import backend.backend.messaging.IdempotencyGuard;
import backend.backend.messaging.SnsEnvelopeParser;
import backend.backend.model.KycStatus;
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

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KycEmailNotificationConsumerTest {

    @Mock IdempotencyGuard idempotencyGuard;
    @Mock PlatformTransactionManager txManager;
    @Mock EmailService emailService;
    @Mock UserRepository userRepository;

    ObjectMapper objectMapper;
    SnsEnvelopeParser envelopeParser;
    KycEmailNotificationConsumer consumer;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        envelopeParser = new SnsEnvelopeParser(objectMapper);
        consumer = new KycEmailNotificationConsumer(objectMapper, idempotencyGuard, envelopeParser,
                new SimpleMeterRegistry(), txManager, userRepository, emailService);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    }

    @Test
    void process_happyPath_sendsApprovedEmail() {
        when(idempotencyGuard.claim(any(UUID.class), eq("KycEmailNotificationConsumer"))).thenReturn(true);
        User u = new User();
        u.setId(42L);
        u.setFirstname("Alice");
        u.setUsername("alice");
        u.setEmail("alice@example.com");
        when(userRepository.findById(42L)).thenReturn(Optional.of(u));

        KycDecisionMade event = KycDecisionMade.fromPipeline("42", KycStatus.KYC_APPROVED.name(),
                "All checks passed");

        consumer.handle(toJson(event));

        ArgumentCaptor<String> subj = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendEmail(eq("alice@example.com"), subj.capture(), body.capture());
        assertThat(subj.getValue()).contains("account is now active");
        assertThat(body.getValue()).contains("Alice");
    }

    @Test
    void process_rejectedDecision_sendsRejectedEmailWithReason() {
        when(idempotencyGuard.claim(any(UUID.class), eq("KycEmailNotificationConsumer"))).thenReturn(true);
        User u = new User();
        u.setId(42L);
        u.setFirstname("Alice");
        u.setEmail("alice@example.com");
        when(userRepository.findById(42L)).thenReturn(Optional.of(u));

        KycDecisionMade event = KycDecisionMade.fromPipeline("42", KycStatus.KYC_REJECTED.name(),
                "Aadhaar number is already linked to another account");

        consumer.handle(toJson(event));

        ArgumentCaptor<String> subj = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendEmail(eq("alice@example.com"), subj.capture(), body.capture());
        assertThat(subj.getValue()).contains("couldn't verify");
        assertThat(body.getValue()).contains("already linked to another account");
    }

    @Test
    void process_idempotentReplay_secondCallIsNoOp() {
        when(idempotencyGuard.claim(any(UUID.class), eq("KycEmailNotificationConsumer")))
                .thenReturn(true)
                .thenReturn(false);
        User u = new User();
        u.setId(42L);
        u.setFirstname("Alice");
        u.setEmail("alice@example.com");
        when(userRepository.findById(42L)).thenReturn(Optional.of(u));

        KycDecisionMade event = KycDecisionMade.fromPipeline("42", KycStatus.KYC_APPROVED.name(),
                "ok");
        String body = toJson(event);

        consumer.handle(body);
        consumer.handle(body);

        verify(emailService, times(1)).sendEmail(anyString(), anyString(), anyString());
    }

    @Test
    void process_missingUserId_skipsAndDoesNotCallEmailService() {
        when(idempotencyGuard.claim(any(UUID.class), eq("KycEmailNotificationConsumer"))).thenReturn(true);
        KycDecisionMade event = KycDecisionMade.fromPipeline(null,
                KycStatus.KYC_APPROVED.name(), "ok");

        consumer.handle(toJson(event));

        verifyNoInteractions(emailService);
        verify(userRepository, never()).findById(any());
    }

    @Test
    void process_userNotFound_skipsAndDoesNotCallEmailService() {
        when(idempotencyGuard.claim(any(UUID.class), eq("KycEmailNotificationConsumer"))).thenReturn(true);
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        KycDecisionMade event = KycDecisionMade.fromPipeline("99",
                KycStatus.KYC_APPROVED.name(), "ok");

        consumer.handle(toJson(event));

        verifyNoInteractions(emailService);
    }

    @Test
    void process_unknownDecision_skipsAndDoesNotCallEmailService() {
        when(idempotencyGuard.claim(any(UUID.class), eq("KycEmailNotificationConsumer"))).thenReturn(true);
        User u = new User();
        u.setId(42L);
        u.setFirstname("Alice");
        u.setEmail("alice@example.com");
        when(userRepository.findById(42L)).thenReturn(Optional.of(u));

        KycDecisionMade event = KycDecisionMade.fromPipeline("42", "BOGUS_DECISION", "ok");

        consumer.handle(toJson(event));

        verifyNoInteractions(emailService);
    }

    private String toJson(KycDecisionMade event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
