package backend.backend.messaging.consumer;

import backend.backend.configuration.FrontEndProperties;
import backend.backend.events.LoanDisbursed;
import backend.backend.messaging.IdempotencyGuard;
import backend.backend.messaging.SnsEnvelopeParser;
import backend.backend.model.User;
import backend.backend.repository.UserRepository;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoanDisbursedEmailConsumerTest {

    @Mock ObjectMapper objectMapper;
    @Mock IdempotencyGuard idempotencyGuard;
    @Mock SnsEnvelopeParser envelopeParser;
    @Mock PlatformTransactionManager txManager;
    @Mock EmailService emailService;
    @Mock UserRepository userRepository;

    FrontEndProperties frontEndProperties;
    LoanDisbursedEmailConsumer consumer;

    @BeforeEach
    void setUp() {
        frontEndProperties = new FrontEndProperties();
        frontEndProperties.seturl("https://app.subby.example");
        consumer = new LoanDisbursedEmailConsumer(objectMapper, idempotencyGuard, envelopeParser,
                new SimpleMeterRegistry(), txManager, emailService, frontEndProperties, userRepository);
    }

    @Test
    void process_happyPath_sendsLoanDisbursedEmail() {
        User u = new User();
        u.setId(99L);
        u.setFirstname("Subham");
        when(userRepository.findById(99L)).thenReturn(Optional.of(u));

        LoanDisbursed event = LoanDisbursed.forLoan("99", "subham@example.com",
                "loan-app-123", new BigDecimal("50000.00"), "XXXX1234",
                Instant.parse("2026-05-01T12:00:00Z"));

        consumer.process(event);

        ArgumentCaptor<String> subj = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendEmail(eq("subham@example.com"), subj.capture(), body.capture());
        assertThat(subj.getValue().toLowerCase()).contains("disbursed");
        assertThat(body.getValue()).contains("Subham");
        assertThat(body.getValue()).contains("XXXX1234");
        assertThat(body.getValue()).contains("loan-app-123");
        assertThat(body.getValue()).contains("https://app.subby.example/loans/loan-app-123/schedule");
    }

    @Test
    void process_missingEmail_skipsAndDoesNotCallEmailService() {
        LoanDisbursed event = LoanDisbursed.forLoan("99", null,
                "loan-app-123", new BigDecimal("50000.00"), "XXXX1234", Instant.now());

        consumer.process(event);

        verifyNoInteractions(emailService);
        verifyNoInteractions(userRepository);
    }
}
