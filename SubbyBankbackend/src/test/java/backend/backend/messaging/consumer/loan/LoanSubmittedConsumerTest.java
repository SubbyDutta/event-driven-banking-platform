package backend.backend.messaging.consumer.loan;

import backend.backend.configuration.SubbyProperties;
import backend.backend.events.LoanApplicationSubmitted;
import backend.backend.events.LoanDecisionMade;
import backend.backend.messaging.IdempotencyGuard;
import backend.backend.messaging.NonRetriableException;
import backend.backend.messaging.OutboxEventPublisher;
import backend.backend.messaging.SnsEnvelopeParser;
import backend.backend.model.LoanApplication;
import backend.backend.model.LoanLifecycleStatus;
import backend.backend.repository.LoanApplicationRepository;
import backend.backend.service.findoc.FindocSubmitResponse;
import backend.backend.service.findoc.FindocVerifyClient;
import backend.backend.service.findoc.LoanSubmitRequest;
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

import java.util.LinkedHashMap;
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
class LoanSubmittedConsumerTest {

    @Mock IdempotencyGuard idempotencyGuard;
    @Mock PlatformTransactionManager txManager;
    @Mock LoanApplicationRepository loanRepo;
    @Mock S3DocumentStorage storage;
    @Mock FindocVerifyClient findoc;
    @Mock OutboxEventPublisher outbox;

    ObjectMapper objectMapper;
    SnsEnvelopeParser envelopeParser;
    SubbyProperties properties;
    LoanSubmittedConsumer consumer;

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
        consumer = new LoanSubmittedConsumer(objectMapper, idempotencyGuard, envelopeParser,
                new SimpleMeterRegistry(), txManager, loanRepo, storage, findoc, outbox, properties);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    }

    @Test
    void process_happyPath_forwardsToFindocAndTransitionsToDocsUnderReview() {
        when(idempotencyGuard.claim(any(UUID.class), eq("LoanSubmittedConsumer"))).thenReturn(true);
        LoanApplication loan = newLoan(7L, "loan-app-123", LoanLifecycleStatus.DRAFT);
        when(loanRepo.findByExternalId("loan-app-123")).thenReturn(Optional.of(loan));
        when(storage.downloadBytes(anyString())).thenReturn(new byte[]{0});
        when(storage.findLatestKycKey(anyString(), any())).thenReturn(Optional.empty());

        FindocSubmitResponse resp = new FindocSubmitResponse();
        resp.setApplicationId("findoc-loan-99");
        resp.setStatus("queued");
        when(findoc.submitLoan(any(LoanSubmitRequest.class))).thenReturn(resp);

        Map<String, String> s3Keys = new LinkedHashMap<>();
        s3Keys.put("bank_statement_1", "loans/loan-app-123/bank/b1.pdf");
        s3Keys.put("payslip_1", "loans/loan-app-123/payslip/p1.pdf");

        LoanApplicationSubmitted event = LoanApplicationSubmitted.of("loan-app-123", "42",
                "alice", 500_000d, "MEDICAL", 6, s3Keys, "Alice", "alice@example.com",
                "+91", "1998-03-15");

        consumer.handle(toJson(event));

        ArgumentCaptor<LoanSubmitRequest> reqArg = ArgumentCaptor.forClass(LoanSubmitRequest.class);
        verify(findoc).submitLoan(reqArg.capture());
        assertThat(reqArg.getValue().getExternalId()).isEqualTo("loan-app-123");
        assertThat(reqArg.getValue().getApplicantName()).isEqualTo("Alice");
        assertThat(reqArg.getValue().getDocuments()).containsKeys("bank_statement_1", "payslip_1");

        ArgumentCaptor<LoanApplication> loanArg = ArgumentCaptor.forClass(LoanApplication.class);
        verify(loanRepo).save(loanArg.capture());
        assertThat(loanArg.getValue().getFindocLoanApplicationId()).isEqualTo("findoc-loan-99");
        assertThat(loanArg.getValue().getLifecycleStatus()).isEqualTo(LoanLifecycleStatus.DOCS_UNDER_REVIEW);
    }

    @Test
    void process_idempotentReplay_secondCallIsNoOp() {
        when(idempotencyGuard.claim(any(UUID.class), eq("LoanSubmittedConsumer")))
                .thenReturn(true)
                .thenReturn(false);
        LoanApplication loan = newLoan(7L, "loan-app-123", LoanLifecycleStatus.DRAFT);
        when(loanRepo.findByExternalId("loan-app-123")).thenReturn(Optional.of(loan));
        when(storage.downloadBytes(anyString())).thenReturn(new byte[]{0});
        when(storage.findLatestKycKey(anyString(), any())).thenReturn(Optional.empty());
        FindocSubmitResponse resp = new FindocSubmitResponse();
        resp.setApplicationId("findoc-loan-99");
        when(findoc.submitLoan(any())).thenReturn(resp);

        LoanApplicationSubmitted event = LoanApplicationSubmitted.of("loan-app-123", "42",
                "alice", 500_000d, "MEDICAL", 6,
                Map.of("bank_statement_1", "loans/loan-app-123/b1.pdf"),
                "Alice", "alice@example.com", "+91", "1998-03-15");
        String body = toJson(event);

        consumer.handle(body);
        consumer.handle(body);

        verify(findoc, times(1)).submitLoan(any());
        verify(loanRepo, times(1)).save(any());
    }

    @Test
    void process_missingLoanAppId_throwsNonRetriable() {
        when(idempotencyGuard.claim(any(UUID.class), eq("LoanSubmittedConsumer"))).thenReturn(true);
        LoanApplicationSubmitted event = LoanApplicationSubmitted.of(null, "42",
                "alice", 500_000d, "MEDICAL", 6,
                Map.of("bank_statement_1", "x"),
                "Alice", "alice@example.com", "+91", "1998-03-15");

        assertThatThrownBy(() -> consumer.handle(toJson(event)))
                .isInstanceOf(NonRetriableException.class)
                .hasMessageContaining("missing loanAppId or s3Keys");

        verifyNoInteractions(findoc);
    }

    @Test
    void process_missingS3Keys_throwsNonRetriable() {
        when(idempotencyGuard.claim(any(UUID.class), eq("LoanSubmittedConsumer"))).thenReturn(true);
        LoanApplicationSubmitted event = LoanApplicationSubmitted.of("loan-app-123", "42",
                "alice", 500_000d, "MEDICAL", 6,
                Map.of(),
                "Alice", "alice@example.com", "+91", "1998-03-15");

        assertThatThrownBy(() -> consumer.handle(toJson(event)))
                .isInstanceOf(NonRetriableException.class)
                .hasMessageContaining("missing loanAppId or s3Keys");

        verifyNoInteractions(findoc);
    }

    @Test
    void process_alreadyAdvanced_skipsForwarding() {
        when(idempotencyGuard.claim(any(UUID.class), eq("LoanSubmittedConsumer"))).thenReturn(true);
        LoanApplication loan = newLoan(7L, "loan-app-123", LoanLifecycleStatus.DOCS_UNDER_REVIEW);
        when(loanRepo.findByExternalId("loan-app-123")).thenReturn(Optional.of(loan));

        LoanApplicationSubmitted event = LoanApplicationSubmitted.of("loan-app-123", "42",
                "alice", 500_000d, "MEDICAL", 6,
                Map.of("bank_statement_1", "x"),
                "Alice", "alice@example.com", "+91", "1998-03-15");

        consumer.handle(toJson(event));

        verifyNoInteractions(findoc);
        verify(loanRepo, never()).save(any());
    }

    @Test
    void process_unrecoverableError_findocReject_marksLoanFailedAndPublishesRejection() {
        when(idempotencyGuard.claim(any(UUID.class), eq("LoanSubmittedConsumer"))).thenReturn(true);
        LoanApplication loan = newLoan(7L, "loan-app-123", LoanLifecycleStatus.DRAFT);
        when(loanRepo.findByExternalId("loan-app-123")).thenReturn(Optional.of(loan));
        when(storage.downloadBytes(anyString())).thenReturn(new byte[]{0});
        when(storage.findLatestKycKey(anyString(), any())).thenReturn(Optional.empty());
        when(findoc.submitLoan(any())).thenThrow(new NonRetriableException("findoc 400 — bad doc set"));

        LoanApplicationSubmitted event = LoanApplicationSubmitted.of("loan-app-123", "42",
                "alice", 500_000d, "MEDICAL", 6,
                Map.of("bank_statement_1", "loans/loan-app-123/b1.pdf"),
                "Alice", "alice@example.com", "+91", "1998-03-15");

        assertThatThrownBy(() -> consumer.handle(toJson(event)))
                .isInstanceOf(NonRetriableException.class)
                .hasMessageContaining("findoc 400");

        ArgumentCaptor<LoanApplication> loanArg = ArgumentCaptor.forClass(LoanApplication.class);
        verify(loanRepo).save(loanArg.capture());
        assertThat(loanArg.getValue().getLifecycleStatus()).isEqualTo(LoanLifecycleStatus.FAILED);

        ArgumentCaptor<LoanDecisionMade> outArg = ArgumentCaptor.forClass(LoanDecisionMade.class);
        verify(outbox).publish(eq("subby-loan-events"), outArg.capture());
        assertThat(outArg.getValue().getDecision()).isEqualTo("REJECTED");
        assertThat(outArg.getValue().getLoanAppId()).isEqualTo("loan-app-123");
    }

    private static LoanApplication newLoan(long id, String externalId, LoanLifecycleStatus status) {
        LoanApplication loan = new LoanApplication();
        loan.setId(id);
        loan.setExternalId(externalId);
        loan.setLifecycleStatus(status);
        loan.setUserId(42L);
        return loan;
    }

    private String toJson(LoanApplicationSubmitted event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
