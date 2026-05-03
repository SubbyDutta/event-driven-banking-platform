package backend.backend.messaging.consumer;

import backend.backend.configuration.FrontEndProperties;
import backend.backend.events.UserSignedUp;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class WelcomeEmailConsumerTest {

    @Mock ObjectMapper objectMapper;
    @Mock IdempotencyGuard idempotencyGuard;
    @Mock SnsEnvelopeParser envelopeParser;
    @Mock PlatformTransactionManager txManager;
    @Mock EmailService emailService;

    FrontEndProperties frontEndProperties;
    WelcomeEmailConsumer consumer;

    @BeforeEach
    void setUp() {
        frontEndProperties = new FrontEndProperties();
        frontEndProperties.seturl("https://app.subby.example");
        consumer = new WelcomeEmailConsumer(objectMapper, idempotencyGuard, envelopeParser,
                new SimpleMeterRegistry(), txManager, emailService, frontEndProperties);
    }

    @Test
    void process_happyPath_sendsWelcomeEmailWithCorrectSubjectAndBody() {
        UserSignedUp event = UserSignedUp.forUser("42", "alice@example.com",
                "Alice", "alice", Instant.parse("2026-05-01T10:00:00Z"));

        consumer.process(event);

        ArgumentCaptor<String> subjectCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendEmail(eq("alice@example.com"), subjectCaptor.capture(), bodyCaptor.capture());

        assertThat(subjectCaptor.getValue()).contains("Welcome");
        String body = bodyCaptor.getValue();
        assertThat(body).contains("Alice");
        assertThat(body).contains("alice");
        assertThat(body).contains("https://app.subby.example/login");
    }

    @Test
    void process_missingEmail_skipsAndDoesNotCallEmailService() {
        UserSignedUp event = UserSignedUp.forUser("42", null, "Alice", "alice", Instant.now());

        consumer.process(event);

        verifyNoInteractions(emailService);
    }

    @Test
    void process_blankEmail_skipsAndDoesNotCallEmailService() {
        UserSignedUp event = UserSignedUp.forUser("42", "   ", "Alice", "alice", Instant.now());

        consumer.process(event);

        verify(emailService, never()).sendEmail(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }
}
