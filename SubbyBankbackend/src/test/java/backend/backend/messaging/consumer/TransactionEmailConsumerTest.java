package backend.backend.messaging.consumer;

import backend.backend.configuration.FrontEndProperties;
import backend.backend.events.TransactionPosted;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionEmailConsumerTest {

    @Mock ObjectMapper objectMapper;
    @Mock IdempotencyGuard idempotencyGuard;
    @Mock SnsEnvelopeParser envelopeParser;
    @Mock PlatformTransactionManager txManager;
    @Mock EmailService emailService;
    @Mock UserRepository userRepository;

    FrontEndProperties frontEndProperties;
    TransactionEmailConsumer consumer;

    @BeforeEach
    void setUp() {
        frontEndProperties = new FrontEndProperties();
        frontEndProperties.seturl("https://app.subby.example");
        consumer = new TransactionEmailConsumer(objectMapper, idempotencyGuard, envelopeParser,
                new SimpleMeterRegistry(), txManager, emailService, frontEndProperties, userRepository);
    }

    @Test
    void process_credit_rendersCreditedTemplateAndSubjectContainsCredited() {
        User u = new User();
        u.setId(7L);
        u.setFirstname("Rajat");
        when(userRepository.findById(7L)).thenReturn(Optional.of(u));

        TransactionPosted event = TransactionPosted.forUser("7", "rajat@example.com",
                TransactionPosted.Direction.CREDIT, new BigDecimal("500.00"), "INR",
                "txn-1001", new BigDecimal("12500.00"), "Subham",
                TransactionPosted.Category.TRANSFER, Instant.parse("2026-05-01T11:00:00Z"));

        consumer.process(event);

        ArgumentCaptor<String> subj = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendEmail(eq("rajat@example.com"), subj.capture(), body.capture());
        assertThat(subj.getValue().toLowerCase()).contains("credited");
        assertThat(body.getValue()).contains("Rajat");
        assertThat(body.getValue()).contains("Subham");
        assertThat(body.getValue()).contains("txn-1001");
        assertThat(body.getValue()).contains("https://app.subby.example/transactions/txn-1001/dispute");
    }

    @Test
    void process_debit_rendersDebitedTemplateAndSubjectContainsDebited() {
        User u = new User();
        u.setId(8L);
        u.setFirstname("Subham");
        when(userRepository.findById(8L)).thenReturn(Optional.of(u));

        TransactionPosted event = TransactionPosted.forUser("8", "subham@example.com",
                TransactionPosted.Direction.DEBIT, new BigDecimal("500.00"), "INR",
                "txn-1001", new BigDecimal("9500.00"), "Rajat",
                TransactionPosted.Category.TRANSFER, Instant.parse("2026-05-01T11:00:00Z"));

        consumer.process(event);

        ArgumentCaptor<String> subj = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendEmail(eq("subham@example.com"), subj.capture(), anyString());
        assertThat(subj.getValue().toLowerCase()).contains("debited");
    }

    @Test
    void process_missingEmail_skipsAndDoesNotCallEmailService() {
        TransactionPosted event = TransactionPosted.forUser("7", null,
                TransactionPosted.Direction.CREDIT, new BigDecimal("100.00"), "INR",
                "txn-1", new BigDecimal("100.00"), "Bob",
                TransactionPosted.Category.TRANSFER, Instant.now());

        consumer.process(event);

        verifyNoInteractions(emailService);
        verify(userRepository, never()).findById(anyLong());
    }

    @Test
    void process_nullDirection_skipsAndDoesNotCallEmailService() {
        TransactionPosted event = TransactionPosted.forUser("7", "rajat@example.com",
                null, new BigDecimal("100.00"), "INR",
                "txn-1", new BigDecimal("100.00"), "Bob",
                TransactionPosted.Category.TRANSFER, Instant.now());

        consumer.process(event);

        verifyNoInteractions(emailService);
    }
}
