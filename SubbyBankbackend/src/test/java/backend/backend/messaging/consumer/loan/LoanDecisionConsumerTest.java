package backend.backend.messaging.consumer.loan;

import backend.backend.events.LoanDecisionMade;
import backend.backend.messaging.IdempotencyGuard;
import backend.backend.messaging.NonRetriableException;
import backend.backend.messaging.SnsEnvelopeParser;
import backend.backend.model.LoanApplication;
import backend.backend.model.LoanLifecycleStatus;
import backend.backend.repository.LoanApplicationRepository;
import backend.backend.service.loan.LoanFinalizationService;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoanDecisionConsumerTest {

    @Mock IdempotencyGuard idempotencyGuard;
    @Mock PlatformTransactionManager txManager;
    @Mock LoanApplicationRepository loanRepo;
    @Mock LoanFinalizationService finalizer;

    ObjectMapper objectMapper;
    SnsEnvelopeParser envelopeParser;
    LoanDecisionConsumer consumer;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        envelopeParser = new SnsEnvelopeParser(objectMapper);
        consumer = new LoanDecisionConsumer(objectMapper, idempotencyGuard, envelopeParser,
                new SimpleMeterRegistry(), txManager, loanRepo, finalizer);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    }

    @Test
    void process_happyPath_callsFinalizerAndStagesLoanFinalized() {
        when(idempotencyGuard.claim(any(UUID.class), eq("LoanDecisionConsumer"))).thenReturn(true);
        LoanApplication loan = newLoan(7L, "loan-app-123");
        when(loanRepo.findByExternalId("loan-app-123")).thenReturn(Optional.of(loan));

        LoanDecisionMade event = LoanDecisionMade.fromRisk("loan-app-123", "42",
                "APPROVED", "ML approved", "B", new BigDecimal("12.00"));

        consumer.handle(toJson(event));

        ArgumentCaptor<LoanApplication> loanArg = ArgumentCaptor.forClass(LoanApplication.class);
        ArgumentCaptor<String> decisionArg = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> reasonArg = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<BigDecimal> rateArg = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<String> sourceArg = ArgumentCaptor.forClass(String.class);
        verify(finalizer).finalize(loanArg.capture(), decisionArg.capture(), reasonArg.capture(),
                rateArg.capture(), sourceArg.capture());

        assertThat(loanArg.getValue().getExternalId()).isEqualTo("loan-app-123");
        assertThat(loanArg.getValue().getId()).isEqualTo(7L);
        assertThat(decisionArg.getValue()).isEqualTo("APPROVED");
        assertThat(reasonArg.getValue()).isEqualTo("ML approved");
        assertThat(rateArg.getValue()).isEqualByComparingTo("12.00");
        assertThat(sourceArg.getValue()).isEqualTo("risk");
    }

    @Test
    void process_idempotentReplay_secondCallIsNoOp() {
        when(idempotencyGuard.claim(any(UUID.class), eq("LoanDecisionConsumer")))
                .thenReturn(true)
                .thenReturn(false);
        LoanApplication loan = newLoan(7L, "loan-app-123");
        when(loanRepo.findByExternalId("loan-app-123")).thenReturn(Optional.of(loan));

        LoanDecisionMade event = LoanDecisionMade.fromRisk("loan-app-123", "42",
                "APPROVED", "ok", "B", new BigDecimal("12.00"));
        String body = toJson(event);

        consumer.handle(body);
        consumer.handle(body);

        verify(finalizer, times(1)).finalize(any(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void process_missingLoanAppId_skipsViaNonRetriableException() {
        when(idempotencyGuard.claim(any(UUID.class), eq("LoanDecisionConsumer"))).thenReturn(true);
        LoanDecisionMade event = LoanDecisionMade.fromRisk(null, "42",
                "APPROVED", "ok", "B", new BigDecimal("12.00"));

        assertThatThrownBy(() -> consumer.handle(toJson(event)))
                .isInstanceOf(NonRetriableException.class)
                .hasMessageContaining("missing loanAppId");

        verifyNoInteractions(finalizer);
    }

    @Test
    void process_adminSourceIsSkipped_alreadyFinalized() {
        when(idempotencyGuard.claim(any(UUID.class), eq("LoanDecisionConsumer"))).thenReturn(true);
        LoanDecisionMade event = LoanDecisionMade.fromAdminOverride("loan-app-123", "42",
                "APPROVED", "manual override", new BigDecimal("12.00"), "ops");

        consumer.handle(toJson(event));

        verifyNoInteractions(finalizer);
        verify(loanRepo, never()).findByExternalId(anyString());
    }

    @Test
    void process_loanNotFound_throwsNonRetriable() {
        when(idempotencyGuard.claim(any(UUID.class), eq("LoanDecisionConsumer"))).thenReturn(true);
        when(loanRepo.findByExternalId("loan-app-missing")).thenReturn(Optional.empty());

        LoanDecisionMade event = LoanDecisionMade.fromRisk("loan-app-missing", "42",
                "APPROVED", "ok", "B", new BigDecimal("12.00"));

        assertThatThrownBy(() -> consumer.handle(toJson(event)))
                .isInstanceOf(NonRetriableException.class)
                .hasMessageContaining("LoanApplication not found");

        verifyNoInteractions(finalizer);
    }

    @Test
    void process_unrecoverableError_finalizerThrowsNonRetriable_propagates() {
        when(idempotencyGuard.claim(any(UUID.class), eq("LoanDecisionConsumer"))).thenReturn(true);
        LoanApplication loan = newLoan(7L, "loan-app-123");
        when(loanRepo.findByExternalId("loan-app-123")).thenReturn(Optional.of(loan));
        doThrow(new NonRetriableException("invalid loan state for decision"))
                .when(finalizer).finalize(any(), anyString(), anyString(), any(), anyString());

        LoanDecisionMade event = LoanDecisionMade.fromRisk("loan-app-123", "42",
                "APPROVED", "ok", "B", new BigDecimal("12.00"));

        assertThatThrownBy(() -> consumer.handle(toJson(event)))
                .isInstanceOf(NonRetriableException.class)
                .hasMessageContaining("invalid loan state");
    }

    private static LoanApplication newLoan(long id, String externalId) {
        LoanApplication loan = new LoanApplication();
        loan.setId(id);
        loan.setExternalId(externalId);
        loan.setLifecycleStatus(LoanLifecycleStatus.PENDING_ADMIN_DECISION);
        loan.setAmount(500_000d);
        return loan;
    }

    private String toJson(LoanDecisionMade event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
