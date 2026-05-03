package backend.backend.messaging.consumer.loan;

import backend.backend.events.LoanDecisionMade;
import backend.backend.messaging.BaseSqsHandler;
import backend.backend.messaging.IdempotencyGuard;
import backend.backend.messaging.NonRetriableException;
import backend.backend.messaging.SnsEnvelopeParser;
import backend.backend.model.LoanApplication;
import backend.backend.repository.LoanApplicationRepository;
import backend.backend.service.loan.LoanFinalizationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.awspring.cloud.sqs.annotation.SqsListener;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Drains {@code subby-loan-decision} (subscribed to {@code subby-loan-events}
 * with event-type filter {@code LoanDecisionMade}). Single responsibility:
 * hand the event to {@link LoanFinalizationService} — which is the one place
 * approval / rejection / manual-review side-effects are applied.
 *
 * <p>Keeping the consumer thin means the same finalization code path runs
 * whether the decision came from the auto-pipeline (findoc-reject short-circuit,
 * risk evaluator) or an admin override.
 */
@Component
public class LoanDecisionConsumer extends BaseSqsHandler<LoanDecisionMade> {

    private final LoanApplicationRepository loanRepo;
    private final LoanFinalizationService finalizer;

    public LoanDecisionConsumer(ObjectMapper objectMapper,
                                IdempotencyGuard idempotencyGuard,
                                SnsEnvelopeParser envelopeParser,
                                MeterRegistry meterRegistry,
                                PlatformTransactionManager txManager,
                                LoanApplicationRepository loanRepo,
                                LoanFinalizationService finalizer) {
        super(objectMapper, idempotencyGuard, envelopeParser, meterRegistry, txManager);
        this.loanRepo = loanRepo;
        this.finalizer = finalizer;
    }

    @SqsListener("${subby.queues.loan-decision}")
    public void onMessage(String rawBody) {
        handle(rawBody);
    }

    @Override
    protected Class<LoanDecisionMade> eventClass() {
        return LoanDecisionMade.class;
    }

    @Override
    protected void process(LoanDecisionMade event) {
        if (event.getLoanAppId() == null) {
            throw new NonRetriableException("LoanDecisionMade missing loanAppId");
        }

        String source = event.getSource();
        if (source != null && source.startsWith("admin:")) {
            log.info("loan.decision.skip admin-source already finalized loanAppId={} source={}",
                    event.getLoanAppId(), source);
            return;
        }

        LoanApplication loan = loanRepo.findByExternalId(event.getLoanAppId())
                .orElseThrow(() -> new NonRetriableException(
                        "LoanApplication not found for loanAppId=" + event.getLoanAppId()));

        finalizer.finalize(loan, event.getDecision(), event.getReason(),
                event.getInterestRate(), event.getSource());

        log.info("loan.decision.applied loanAppId={} decision={} source={}",
                event.getLoanAppId(), event.getDecision(), event.getSource());
    }
}
