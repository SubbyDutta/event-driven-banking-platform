package backend.backend.messaging.consumer;

import backend.backend.configuration.FrontEndProperties;
import backend.backend.events.PasswordChanged;
import backend.backend.messaging.IdempotencyGuard;
import backend.backend.messaging.SnsEnvelopeParser;
import backend.backend.service.EmailService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class PasswordChangedEmailConsumerTest {

    @Mock ObjectMapper objectMapper;
    @Mock IdempotencyGuard idempotencyGuard;
    @Mock SnsEnvelopeParser envelopeParser;
    @Mock PlatformTransactionManager txManager;
    @Mock EmailService emailService;

    FrontEndProperties frontEndProperties;
    PasswordChangedEmailConsumer consumer;

    @BeforeEach
    void setUp() {
        frontEndProperties = new FrontEndProperties();
        frontEndProperties.seturl("https://app.subby.example");
        consumer = new PasswordChangedEmailConsumer(objectMapper, idempotencyGuard, envelopeParser,
                new SimpleMeterRegistry(), txManager, emailService, frontEndProperties);
    }

    @Test
    void process_happyPath_sendsPasswordChangedEmailWithCorrectSubjectAndBody() {
        PasswordChanged event = PasswordChanged.forUser("42", "alice@example.com",
                "Alice", Instant.parse("2026-05-01T13:00:00Z"));

        consumer.process(event);

        ArgumentCaptor<String> subj = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendEmail(eq("alice@example.com"), subj.capture(), body.capture());
        assertThat(subj.getValue()).isEqualTo("Your SubbyBank password was changed");
        assertThat(body.getValue()).contains("Alice");
        assertThat(body.getValue()).contains("https://app.subby.example/support");
    }

    @Test
    void process_missingEmail_skipsAndDoesNotCallEmailService() {
        PasswordChanged event = PasswordChanged.forUser("42", null, "Alice", Instant.now());

        consumer.process(event);

        verifyNoInteractions(emailService);
    }
}
