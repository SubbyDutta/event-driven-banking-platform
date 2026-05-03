package backend.backend.messaging.consumer;

import backend.backend.configuration.SubbyProperties;
import backend.backend.events.KycDocsVerified;
import backend.backend.events.KycSubmitted;
import backend.backend.messaging.IdempotencyGuard;
import backend.backend.messaging.NonRetriableException;
import backend.backend.messaging.OutboxEventPublisher;
import backend.backend.messaging.SnsEnvelopeParser;
import backend.backend.model.KycStatus;
import backend.backend.model.User;
import backend.backend.repository.UserRepository;
import backend.backend.service.findoc.FindocSubmitResponse;
import backend.backend.service.findoc.FindocVerifyClient;
import backend.backend.service.findoc.KycSubmitRequest;
import backend.backend.storage.S3DocumentStorage;
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

import java.time.LocalDate;
import java.util.Map;
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
class KycSubmittedConsumerTest {

    @Mock IdempotencyGuard idempotencyGuard;
    @Mock PlatformTransactionManager txManager;
    @Mock UserRepository userRepository;
    @Mock S3DocumentStorage storage;
    @Mock FindocVerifyClient findoc;
    @Mock OutboxEventPublisher outbox;

    ObjectMapper objectMapper;
    SnsEnvelopeParser envelopeParser;
    SubbyProperties properties;
    KycSubmittedConsumer consumer;

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
        consumer = new KycSubmittedConsumer(objectMapper, idempotencyGuard, envelopeParser,
                new SimpleMeterRegistry(), txManager, userRepository, storage, findoc, outbox, properties);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    }

    @Test
    void process_happyPath_forwardsToFindocAndPublishesKycDocsVerified() {
        when(idempotencyGuard.claim(any(UUID.class), eq("KycSubmittedConsumer"))).thenReturn(true);
        User u = new User();
        u.setId(42L);
        u.setKycStatus(KycStatus.KYC_SUBMITTED);
        when(userRepository.findById(42L)).thenReturn(Optional.of(u));
        when(storage.downloadBytes("kyc/42/aadhaar/a.pdf")).thenReturn(new byte[]{1, 2});
        when(storage.downloadBytes("kyc/42/pan/p.pdf")).thenReturn(new byte[]{3, 4});

        FindocSubmitResponse resp = new FindocSubmitResponse();
        resp.setApplicationId("findoc-app-99");
        resp.setStatus("queued");
        when(findoc.submitKyc(any(KycSubmitRequest.class))).thenReturn(resp);

        KycSubmitted event = KycSubmitted.forUser("42",
                Map.of("aadhaar", "kyc/42/aadhaar/a.pdf", "pan", "kyc/42/pan/p.pdf"),
                "Alice", "alice@example.com", "+919999900000",
                LocalDate.of(1998, 3, 15));

        consumer.handle(toJson(event));

        ArgumentCaptor<KycSubmitRequest> reqArg = ArgumentCaptor.forClass(KycSubmitRequest.class);
        verify(findoc).submitKyc(reqArg.capture());
        KycSubmitRequest req = reqArg.getValue();
        assertThat(req.getExternalId()).isEqualTo("user-42");
        assertThat(req.getApplicantName()).isEqualTo("Alice");
        assertThat(req.getEmail()).isEqualTo("alice@example.com");
        assertThat(req.getAadhaarBytes()).isEqualTo(new byte[]{1, 2});
        assertThat(req.getPanBytes()).isEqualTo(new byte[]{3, 4});

        ArgumentCaptor<User> userArg = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userArg.capture());
        assertThat(userArg.getValue().getFindocKycApplicationId()).isEqualTo("findoc-app-99");
        assertThat(userArg.getValue().getKycStatus()).isEqualTo(KycStatus.KYC_DOCS_UNDER_REVIEW);

        ArgumentCaptor<KycDocsVerified> outArg = ArgumentCaptor.forClass(KycDocsVerified.class);
        verify(outbox).publish(eq("subby-kyc-events"), outArg.capture());
        assertThat(outArg.getValue().getUserId()).isEqualTo("42");
        assertThat(outArg.getValue().getFindocAppId()).isEqualTo("findoc-app-99");
    }

    @Test
    void process_idempotentReplay_secondCallIsNoOp() {
        when(idempotencyGuard.claim(any(UUID.class), eq("KycSubmittedConsumer")))
                .thenReturn(true)
                .thenReturn(false);
        User u = new User();
        u.setId(42L);
        u.setKycStatus(KycStatus.KYC_SUBMITTED);
        when(userRepository.findById(42L)).thenReturn(Optional.of(u));
        when(storage.downloadBytes(anyString())).thenReturn(new byte[]{0});
        FindocSubmitResponse resp = new FindocSubmitResponse();
        resp.setApplicationId("findoc-app-99");
        when(findoc.submitKyc(any())).thenReturn(resp);

        KycSubmitted event = KycSubmitted.forUser("42",
                Map.of("aadhaar", "kyc/42/aadhaar/a.pdf", "pan", "kyc/42/pan/p.pdf"),
                "Alice", "alice@example.com", "+91", null);
        String body = toJson(event);

        consumer.handle(body);
        consumer.handle(body);

        verify(findoc, times(1)).submitKyc(any());
        verify(outbox, times(1)).publish(anyString(), any());
    }

    @Test
    void process_missingS3Keys_throwsNonRetriable() {
        when(idempotencyGuard.claim(any(UUID.class), eq("KycSubmittedConsumer"))).thenReturn(true);
        KycSubmitted event = KycSubmitted.forUser("42", null,
                "Alice", "alice@example.com", "+91", null);

        assertThatThrownBy(() -> consumer.handle(toJson(event)))
                .isInstanceOf(NonRetriableException.class)
                .hasMessageContaining("missing userId or s3Keys");

        verifyNoInteractions(findoc);
        verifyNoInteractions(outbox);
    }

    @Test
    void process_missingAadhaarKey_throwsNonRetriable() {
        when(idempotencyGuard.claim(any(UUID.class), eq("KycSubmittedConsumer"))).thenReturn(true);
        KycSubmitted event = KycSubmitted.forUser("42",
                Map.of("pan", "kyc/42/pan/p.pdf"),
                "Alice", "alice@example.com", "+91", null);

        assertThatThrownBy(() -> consumer.handle(toJson(event)))
                .isInstanceOf(NonRetriableException.class)
                .hasMessageContaining("missing required aadhaar/pan");

        verifyNoInteractions(findoc);
        verifyNoInteractions(outbox);
    }

    @Test
    void process_userAlreadyApproved_skipsForwarding() {
        when(idempotencyGuard.claim(any(UUID.class), eq("KycSubmittedConsumer"))).thenReturn(true);
        User u = new User();
        u.setId(42L);
        u.setKycStatus(KycStatus.KYC_APPROVED);
        when(userRepository.findById(42L)).thenReturn(Optional.of(u));

        KycSubmitted event = KycSubmitted.forUser("42",
                Map.of("aadhaar", "kyc/42/aadhaar/a.pdf", "pan", "kyc/42/pan/p.pdf"),
                "Alice", "alice@example.com", "+91", null);

        consumer.handle(toJson(event));

        verifyNoInteractions(findoc);
        verifyNoInteractions(outbox);
        verify(userRepository, never()).save(any());
    }

    @Test
    void process_unrecoverableError_findocReject_propagatesNonRetriable() {
        when(idempotencyGuard.claim(any(UUID.class), eq("KycSubmittedConsumer"))).thenReturn(true);
        User u = new User();
        u.setId(42L);
        u.setKycStatus(KycStatus.KYC_SUBMITTED);
        when(userRepository.findById(42L)).thenReturn(Optional.of(u));
        when(storage.downloadBytes(anyString())).thenReturn(new byte[]{0});
        when(findoc.submitKyc(any())).thenThrow(new NonRetriableException("findoc 400 — bad doc set"));

        KycSubmitted event = KycSubmitted.forUser("42",
                Map.of("aadhaar", "kyc/42/aadhaar/a.pdf", "pan", "kyc/42/pan/p.pdf"),
                "Alice", "alice@example.com", "+91", null);

        assertThatThrownBy(() -> consumer.handle(toJson(event)))
                .isInstanceOf(NonRetriableException.class)
                .hasMessageContaining("findoc 400");

        verifyNoInteractions(outbox);
    }

    private String toJson(KycSubmitted event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
