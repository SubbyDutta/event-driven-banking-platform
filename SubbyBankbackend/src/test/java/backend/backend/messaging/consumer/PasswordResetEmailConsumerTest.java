package backend.backend.messaging.consumer;

import backend.backend.configuration.FrontEndProperties;
import backend.backend.events.PasswordResetRequested;
import backend.backend.messaging.IdempotencyGuard;
import backend.backend.messaging.SnsEnvelopeParser;
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

import java.time.Instant;
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
class PasswordResetEmailConsumerTest {

    @Mock IdempotencyGuard idempotencyGuard;
    @Mock PlatformTransactionManager txManager;
    @Mock EmailService emailService;

    ObjectMapper objectMapper;
    SnsEnvelopeParser envelopeParser;
    FrontEndProperties frontEndProperties;
    PasswordResetEmailConsumer consumer;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        envelopeParser = new SnsEnvelopeParser(objectMapper);
        frontEndProperties = new FrontEndProperties();
        frontEndProperties.seturl("https://app.subby.example");
        consumer = new PasswordResetEmailConsumer(objectMapper, idempotencyGuard, envelopeParser,
                new SimpleMeterRegistry(), txManager, emailService, frontEndProperties);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    }

    @Test
    void process_happyPath_sendsResetEmailWithOtpAndResetUrl() {
        when(idempotencyGuard.claim(any(UUID.class), eq("PasswordResetEmailConsumer"))).thenReturn(true);
        PasswordResetRequested event = PasswordResetRequested.forUser("42",
                "alice@example.com", "Alice", "654321",
                Instant.parse("2026-05-01T11:30:00Z"));

        consumer.handle(toJson(event));

        ArgumentCaptor<String> subj = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendEmail(eq("alice@example.com"), subj.capture(), body.capture());
        assertThat(subj.getValue()).contains("Reset your SubbyBank password");
        assertThat(body.getValue()).contains("Alice");
        assertThat(body.getValue()).contains("654321");
        assertThat(body.getValue()).contains("https://app.subby.example/reset-password");
    }

    @Test
    void process_idempotentReplay_secondCallIsNoOp() {
        when(idempotencyGuard.claim(any(UUID.class), eq("PasswordResetEmailConsumer")))
                .thenReturn(true)
                .thenReturn(false);
        PasswordResetRequested event = PasswordResetRequested.forUser("42",
                "alice@example.com", "Alice", "654321",
                Instant.parse("2026-05-01T11:30:00Z"));
        String body = toJson(event);

        consumer.handle(body);
        consumer.handle(body);

        verify(emailService, times(1)).sendEmail(anyString(), anyString(), anyString());
    }

    @Test
    void process_missingEmail_skipsAndDoesNotCallEmailService() {
        when(idempotencyGuard.claim(any(UUID.class), eq("PasswordResetEmailConsumer"))).thenReturn(true);
        PasswordResetRequested event = PasswordResetRequested.forUser("42",
                null, "Alice", "654321", Instant.now());

        consumer.handle(toJson(event));

        verifyNoInteractions(emailService);
    }

    @Test
    void process_missingOtp_skipsAndDoesNotCallEmailService() {
        when(idempotencyGuard.claim(any(UUID.class), eq("PasswordResetEmailConsumer"))).thenReturn(true);
        PasswordResetRequested event = PasswordResetRequested.forUser("42",
                "alice@example.com", "Alice", null, Instant.now());

        consumer.handle(toJson(event));

        verify(emailService, never()).sendEmail(anyString(), anyString(), anyString());
    }

    private String toJson(PasswordResetRequested event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
