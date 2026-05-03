package backend.backend.messaging;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Claims exclusive processing of an (eventId, consumerName) pair using an
 * {@code INSERT ... ON CONFLICT DO NOTHING} on {@code processed_events}.
 *
 * <p>Typical usage inside a consumer:
 * <pre>{@code
 * if (!idempotencyGuard.claim(eventId, "LoanSubmittedConsumer")) {
 *     log.info("skip duplicate eventId={}", eventId);
 *     return;
 * }
 * // ... process the event ...
 * }</pre>
 *
 * <p>Call this from within the same {@code @Transactional} scope that performs
 * business writes — the claim commits with the work, or both roll back together.
 */
@Component
public class IdempotencyGuard {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyGuard.class);

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Try to claim the given (eventId, consumerName) pair for processing.
     *
     * @return {@code true} if this consumer claimed the event (first time seen);
     *         {@code false} if another invocation already processed it.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean claim(UUID eventId, String consumerName) {
        if (eventId == null) throw new IllegalArgumentException("eventId is required");
        if (consumerName == null || consumerName.isBlank()) {
            throw new IllegalArgumentException("consumerName is required");
        }
        return claim(eventId.toString(), consumerName);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean claim(String eventId, String consumerName) {
        try {
            int inserted = entityManager.createNativeQuery("""
                    INSERT INTO processed_events (event_id, consumer_name)
                    VALUES (:eventId, :consumer)
                    ON CONFLICT (event_id, consumer_name) DO NOTHING
                    """)
                    .setParameter("eventId", eventId)
                    .setParameter("consumer", consumerName)
                    .executeUpdate();
            boolean claimed = inserted == 1;
            if (!claimed) {
                log.debug("idempotency.duplicate eventId={} consumer={}", eventId, consumerName);
            }
            return claimed;
        } catch (DataIntegrityViolationException e) {

            log.debug("idempotency.race eventId={} consumer={}", eventId, consumerName);
            return false;
        }
    }
}
