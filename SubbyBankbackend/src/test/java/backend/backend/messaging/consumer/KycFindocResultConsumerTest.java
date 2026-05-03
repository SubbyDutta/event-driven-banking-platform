package backend.backend.messaging.consumer;

import backend.backend.configuration.SubbyProperties;
import backend.backend.events.AdminKycReviewNeeded;
import backend.backend.events.KycDecisionMade;
import backend.backend.events.KycFinalized;
import backend.backend.messaging.IdempotencyGuard;
import backend.backend.messaging.NonRetriableException;
import backend.backend.messaging.OutboxEventPublisher;
import backend.backend.messaging.SnsEnvelopeParser;
import backend.backend.model.KycStatus;
import backend.backend.model.User;
import backend.backend.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KycFindocResultConsumerTest {

    @Mock IdempotencyGuard idempotencyGuard;
    @Mock PlatformTransactionManager txManager;
    @Mock UserRepository userRepository;
    @Mock OutboxEventPublisher outbox;

    ObjectMapper objectMapper;
    SnsEnvelopeParser envelopeParser;
    SubbyProperties properties;
    KycFindocResultConsumer consumer;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        envelopeParser = new SnsEnvelopeParser(objectMapper);
        properties = new SubbyProperties(
                new SubbyProperties.S3("subby-documents", 600, 10_000_000),
                new SubbyProperties.Topics("subby-kyc-events", "subby-loan-events",
                        "subby-notifications", "subby-risk-requested"),
                new SubbyProperties.Queues("q","q","q","q","q","q","q","q","q","q","q","q","q","q","q","q","q"),
                new SubbyProperties.Outbox(100, 50, 5, 200_000),
                new SubbyProperties.Findoc("http://localhost:8081", "k", 60));
        consumer = new KycFindocResultConsumer(objectMapper, idempotencyGuard, envelopeParser,
                new SimpleMeterRegistry(), txManager, userRepository, outbox, properties);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    }

    @Test
    void process_happyPath_approvedRecommendation_setsApprovedAndPublishesDecisionAndFinalized() {
        when(idempotencyGuard.claim(any(UUID.class), eq("KycFindocResultConsumer"))).thenReturn(true);
        User u = new User();
        u.setId(42L);
        u.setUsername("alice");
        u.setEmail("alice@example.com");
        when(userRepository.findById(42L)).thenReturn(Optional.of(u));

        consumer.handle(findocEnvelopeJson("user-42", "kyc", "approve", "fa-1"));

        ArgumentCaptor<User> userArg = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userArg.capture());
        assertThat(userArg.getValue().getKycStatus()).isEqualTo(KycStatus.KYC_APPROVED);
        assertThat(userArg.getValue().isAccountActive()).isTrue();
        assertThat(userArg.getValue().getFindocKycApplicationId()).isEqualTo("fa-1");

        ArgumentCaptor<KycDecisionMade> decisionArg = ArgumentCaptor.forClass(KycDecisionMade.class);
        verify(outbox).publish(eq("subby-kyc-events"), decisionArg.capture());
        assertThat(decisionArg.getValue().getUserId()).isEqualTo("42");
        assertThat(decisionArg.getValue().getDecision()).isEqualTo("KYC_APPROVED");

        verify(outbox).publish(eq("subby-kyc-events"),
                org.mockito.ArgumentMatchers.isA(KycFinalized.class));
    }

    @Test
    void process_rejectedRecommendation_setsRejectedAndDoesNotPublishFinalized() {
        when(idempotencyGuard.claim(any(UUID.class), eq("KycFindocResultConsumer"))).thenReturn(true);
        User u = new User();
        u.setId(42L);
        u.setUsername("alice");
        u.setEmail("alice@example.com");
        when(userRepository.findById(42L)).thenReturn(Optional.of(u));

        consumer.handle(findocEnvelopeJson("user-42", "kyc", "reject", "fa-1"));

        ArgumentCaptor<User> userArg = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userArg.capture());
        assertThat(userArg.getValue().getKycStatus()).isEqualTo(KycStatus.KYC_REJECTED);
        assertThat(userArg.getValue().isAccountActive()).isFalse();

        verify(outbox, times(1)).publish(anyString(), any());
        verify(outbox, never()).publish(anyString(),
                org.mockito.ArgumentMatchers.isA(KycFinalized.class));
    }

    @Test
    void process_manualReview_publishesAdminKycReviewNeeded() {
        when(idempotencyGuard.claim(any(UUID.class), eq("KycFindocResultConsumer"))).thenReturn(true);
        User u = new User();
        u.setId(42L);
        u.setUsername("alice");
        u.setEmail("alice@example.com");
        when(userRepository.findById(42L)).thenReturn(Optional.of(u));

        consumer.handle(findocEnvelopeJson("user-42", "kyc", "manual_review", "fa-1"));

        verify(outbox).publish(eq("subby-notifications"),
                org.mockito.ArgumentMatchers.isA(AdminKycReviewNeeded.class));
    }

    @Test
    void process_idempotentReplay_secondCallIsNoOp() {
        when(idempotencyGuard.claim(any(UUID.class), eq("KycFindocResultConsumer")))
                .thenReturn(true)
                .thenReturn(false);
        User u = new User();
        u.setId(42L);
        u.setUsername("alice");
        u.setEmail("alice@example.com");
        when(userRepository.findById(42L)).thenReturn(Optional.of(u));

        String body = findocEnvelopeJson("user-42", "kyc", "approve", "fa-1");

        consumer.handle(body);
        consumer.handle(body);

        verify(userRepository, times(1)).save(any());
    }

    @Test
    void process_missingPayload_throwsNonRetriable() {
        when(idempotencyGuard.claim(any(UUID.class), eq("KycFindocResultConsumer"))).thenReturn(true);
        ObjectNode env = objectMapper.createObjectNode();
        env.put("eventId", UUID.randomUUID().toString());
        env.put("schemaVersion", 1);
        env.put("eventType", "FindocKycReportReady");
        env.put("occurredAt", Instant.now().toString());

        assertThatThrownBy(() -> consumer.handle(env.toString()))
                .isInstanceOf(NonRetriableException.class)
                .hasMessageContaining("missing payload");

        verifyNoInteractions(userRepository);
        verifyNoInteractions(outbox);
    }

    @Test
    void process_invalidCorrelationId_throwsNonRetriable() {
        when(idempotencyGuard.claim(any(UUID.class), eq("KycFindocResultConsumer"))).thenReturn(true);

        assertThatThrownBy(() -> consumer.handle(findocEnvelopeJson("not-a-user-corr", "kyc",
                "approve", "fa-1")))
                .isInstanceOf(NonRetriableException.class)
                .hasMessageContaining("correlationId is not 'user-{id}'");

        verifyNoInteractions(userRepository);
        verifyNoInteractions(outbox);
    }

    @Test
    void process_userNotFound_throwsNonRetriable() {
        when(idempotencyGuard.claim(any(UUID.class), eq("KycFindocResultConsumer"))).thenReturn(true);
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> consumer.handle(findocEnvelopeJson("user-99", "kyc",
                "approve", "fa-1")))
                .isInstanceOf(NonRetriableException.class)
                .hasMessageContaining("User not found");

        verifyNoInteractions(outbox);
    }

    @Test
    void process_nonKycUseCase_skipsSilently() {
        when(idempotencyGuard.claim(any(UUID.class), eq("KycFindocResultConsumer"))).thenReturn(true);

        consumer.handle(findocEnvelopeJson("user-42", "loan", "approve", "fa-1"));

        verifyNoInteractions(userRepository);
        verifyNoInteractions(outbox);
    }

    private String findocEnvelopeJson(String correlationId, String useCase,
                                      String recommendation, String applicationId) {
        ObjectNode env = objectMapper.createObjectNode();
        env.put("eventId", UUID.randomUUID().toString());
        env.put("schemaVersion", 1);
        env.put("eventType", "FindocKycReportReady");
        env.put("occurredAt", Instant.now().toString());

        ObjectNode payload = env.putObject("payload");
        payload.put("applicationId", applicationId);
        payload.put("correlationId", correlationId);
        payload.put("useCase", useCase);
        payload.put("status", "completed");
        payload.put("recommendation", recommendation);
        payload.put("overallScore", 0.85);
        ObjectNode report = payload.putObject("report");
        report.put("recommendation", recommendation);
        return env.toString();
    }
}
