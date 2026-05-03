package backend.backend.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.UUID;

/**
 * Template-method base for SQS consumers. Subclasses implement
 * {@link #process(Object)} with the business work; this class handles the
 * cross-cutting concerns that every consumer would otherwise copy-paste:
 *
 * <ul>
 *   <li><b>Envelope unwrap</b> — tolerate both raw and SNS-wrapped delivery.</li>
 *   <li><b>JSON parse</b> — fail loudly with {@link NonRetriableException} so bad
 *       messages go straight to DLQ rather than burning the 3-retry budget.</li>
 *   <li><b>MDC</b> — set {@code eventId}, {@code eventType}, {@code correlationId}
 *       for structured logging; always cleared on exit.</li>
 *   <li><b>Idempotency</b> — gate every event through {@link IdempotencyGuard};
 *       duplicates return silently.</li>
 *   <li><b>Metrics</b> — per-consumer received / processed / skipped / failed
 *       counters plus a processing timer.</li>
 *   <li><b>Retry classification</b> — {@link NonRetriableException} rethrows as-is
 *       (Spring Cloud AWS SQS listener will honor it and avoid retries when the
 *       listener is configured accordingly); other exceptions rethrow for normal
 *       retry + eventual DLQ via the queue's redrive policy.</li>
 * </ul>
 *
 * @param <E> deserialized event type
 */
public abstract class BaseSqsHandler<E> {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    protected final ObjectMapper objectMapper;
    protected final IdempotencyGuard idempotencyGuard;
    protected final SnsEnvelopeParser envelopeParser;
    protected final TransactionTemplate txTemplate;

    private final Counter receivedCounter;
    private final Counter processedCounter;
    private final Counter skippedCounter;
    private final Counter failedCounter;
    private final Counter poisonCounter;
    private final Timer processingTimer;

    protected BaseSqsHandler(ObjectMapper objectMapper,
                             IdempotencyGuard idempotencyGuard,
                             SnsEnvelopeParser envelopeParser,
                             MeterRegistry meterRegistry,
                             PlatformTransactionManager txManager) {
        this.objectMapper = objectMapper;
        this.idempotencyGuard = idempotencyGuard;
        this.envelopeParser = envelopeParser;
        this.txTemplate = new TransactionTemplate(txManager);
        Tags tags = Tags.of("consumer", consumerName());
        this.receivedCounter = Counter.builder("sqs.messages.received").tags(tags).register(meterRegistry);
        this.processedCounter = Counter.builder("sqs.messages.acked").tags(tags).register(meterRegistry);
        this.skippedCounter = Counter.builder("sqs.messages.duplicate").tags(tags).register(meterRegistry);
        this.failedCounter = Counter.builder("sqs.messages.failed").tags(tags).register(meterRegistry);
        this.poisonCounter = Counter.builder("sqs.messages.poison").tags(tags).register(meterRegistry);
        this.processingTimer = Timer.builder("sqs.messages.duration").tags(tags).register(meterRegistry);
    }

    /**
     * Subclasses must return a stable name (used as idempotency scope + metric tag).
     * Convention: simple class name (e.g. {@code "LoanSubmittedConsumer"}).
     */
    protected String consumerName() {
        return getClass().getSimpleName();
    }

    /** Concrete class of the event payload — used by Jackson to deserialize. */
    protected abstract Class<E> eventClass();

    /** Business logic. Called only once per {@code (eventId, consumerName)}. */
    protected abstract void process(E event);

    /**
     * Entry point invoked by {@code @SqsListener} methods. Subclasses typically do:
     * <pre>{@code
     * @SqsListener("${subby.queues.loan-submitted}")
     * public void onMessage(String rawBody) { handle(rawBody); }
     * }</pre>
     *
     * <p>Opens a transaction via {@link TransactionTemplate} so the idempotency
     * claim, business writes done by {@link #process(Object)}, and any outbox
     * events staged by {@code OutboxEventPublisher} commit as one unit — or roll
     * back together on failure. A rollback undoes the idempotency claim so SQS
     * redrive can retry the event cleanly.
     *
     * <p>Programmatic tx (not {@code @Transactional}) avoids the common AOP pitfall
     * where an {@code @SqsListener} method calls {@code this.handle(...)} and
     * bypasses the proxy, silently losing the transaction.
     */
    public final void handle(String rawBody) {
        receivedCounter.increment();
        long start = System.nanoTime();
        try {
            String body = envelopeParser.unwrap(rawBody);
            JsonNode envelope = parseTree(body);
            String eventId = textOrNull(envelope, "eventId");
            String eventType = textOrNull(envelope, "eventType");
            String correlationId = textOrNull(envelope, "correlationId");

            MDC.put("consumer", consumerName());
            if (eventId != null) MDC.put("eventId", eventId);
            if (eventType != null) MDC.put("eventType", eventType);
            if (correlationId != null) MDC.put("correlationId", correlationId);

            if (eventId == null) {
                poisonCounter.increment();
                throw new NonRetriableException("Event is missing required 'eventId' field");
            }

            E event = deserialize(body);
            UUID uuid = parseUuid(eventId);

            boolean processed = Boolean.TRUE.equals(txTemplate.execute(status -> {
                if (!idempotencyGuard.claim(uuid, consumerName())) {
                    skippedCounter.increment();
                    log.info("sqs.duplicate eventId={} consumer={}", eventId, consumerName());
                    return false;
                }
                process(event);
                return true;
            }));

            if (processed) {
                processedCounter.increment();
                log.info("sqs.acked eventId={} eventType={} latencyMs={}",
                        eventId, eventType, (System.nanoTime() - start) / 1_000_000);
            }

        } catch (NonRetriableException nre) {
            poisonCounter.increment();
            log.error("sqs.poison consumer={} error={}", consumerName(), nre.getMessage(), nre);
            throw nre;
        } catch (Exception e) {
            failedCounter.increment();
            log.warn("sqs.failed consumer={} error={} — will retry", consumerName(), e.toString());
            throw e instanceof RuntimeException re ? re : new RuntimeException(e);
        } finally {
            processingTimer.record(Duration.ofNanos(System.nanoTime() - start));
            MDC.remove("consumer");
            MDC.remove("eventId");
            MDC.remove("eventType");
            MDC.remove("correlationId");
        }
    }

    private JsonNode parseTree(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new NonRetriableException("Event body is not valid JSON", e);
        }
    }

    private E deserialize(String body) {
        try {
            return objectMapper.readValue(body, eventClass());
        } catch (Exception e) {
            throw new NonRetriableException(
                    "Event body failed to deserialize as " + eventClass().getSimpleName(), e);
        }
    }

    private static UUID parseUuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            throw new NonRetriableException("eventId is not a valid UUID: " + s, e);
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.get(field);
        return (v != null && !v.isNull()) ? v.asText() : null;
    }
}
