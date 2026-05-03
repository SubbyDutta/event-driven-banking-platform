package backend.backend.messaging;

import backend.backend.configuration.SubbyProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import software.amazon.awssdk.services.sns.SnsAsyncClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background relay that drains the outbox into SNS. Runs on a scheduled loop,
 * claims a batch of unpublished rows with {@code SELECT ... FOR UPDATE SKIP LOCKED}
 * (so multiple JVM instances can run the relay concurrently), publishes each
 * row to SNS, and marks success/failure in place.
 *
 * <p>Failure handling:
 * <ul>
 *   <li>Per-row try/catch — one bad row does not block the batch.</li>
 *   <li>Attempt count increments on every failure; {@code last_error} captures the
 *       reason for triage.</li>
 *   <li>After {@code subby.outbox.max-attempts} failures, the row stops being
 *       claimed (poison-pill fence) and a {@code outbox.events.dead_letter}
 *       counter fires for alerting.</li>
 * </ul>
 *
 * <p>Shutdown: the {@link #running} flag flips on SIGTERM so any in-flight poll
 * completes its current batch before the app exits.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxEventRepository repository;
    private final SnsAsyncClient snsClient;
    private final SubbyProperties properties;

    private final TransactionTemplate claimTxTemplate;
    private final TransactionTemplate updateTxTemplate;

    private final Counter publishedCounter;
    private final Counter failedCounter;
    private final Counter deadLetterCounter;
    private final Timer publishTimer;

    private final Map<String, String> topicArnCache = new ConcurrentHashMap<>();

    private final AtomicBoolean running = new AtomicBoolean(true);

    private final UUID relayId = UUID.randomUUID();
    private static final Duration LEASE_DURATION = Duration.ofSeconds(30);

    public OutboxRelay(OutboxEventRepository repository,
                       SnsAsyncClient snsClient,
                       SubbyProperties properties,
                       MeterRegistry meterRegistry,
                       PlatformTransactionManager txManager) {
        this.repository = repository;
        this.snsClient = snsClient;
        this.properties = properties;

        this.claimTxTemplate = new TransactionTemplate(txManager);
        this.claimTxTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.updateTxTemplate = new TransactionTemplate(txManager);
        this.updateTxTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.publishedCounter = Counter.builder("outbox.events.published")
                .description("Events successfully published to SNS")
                .register(meterRegistry);
        this.failedCounter = Counter.builder("outbox.events.failed")
                .description("Publish attempts that failed (retry pending)")
                .register(meterRegistry);
        this.deadLetterCounter = Counter.builder("outbox.events.dead_letter")
                .description("Events that exhausted max attempts and need manual intervention")
                .register(meterRegistry);
        this.publishTimer = Timer.builder("outbox.publish.duration")
                .description("Time spent publishing a single outbox row to SNS")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${subby.outbox.poll-interval-ms:1000}")
    public void pollAndPublish() {
        if (!running.get()) return;

        int batchSize = properties.outbox().batchSize();
        int maxAttempts = properties.outbox().maxAttempts();

        Instant leaseExpiresAt = Instant.now().plus(LEASE_DURATION);
        List<OutboxEvent> batch = claimTxTemplate.execute(status -> {
            List<OutboxEvent> rows = repository.claimBatch(maxAttempts, batchSize);
            for (OutboxEvent row : rows) {
                row.setLeaseId(relayId);
                row.setLeaseExpiresAt(leaseExpiresAt);
            }
            return rows;
        });
        if (batch == null || batch.isEmpty()) return;

        log.debug("outbox.relay claimed batch size={}", batch.size());
        for (OutboxEvent row : batch) {
            MDC.put("eventId", row.getEventId());
            MDC.put("topic", row.getTopicName());
            if (row.getCorrelationId() != null) MDC.put("correlationId", row.getCorrelationId());
            try {
                publishOne(row);
            } catch (Exception ex) {
                log.warn("outbox.relay unexpected error on eventId={}: {}",
                        row.getEventId(), ex.toString());
            } finally {
                MDC.remove("eventId");
                MDC.remove("topic");
                MDC.remove("correlationId");
            }
        }
    }

    protected void publishOne(OutboxEvent row) {
        long start = System.nanoTime();
        try {
            String topicArn = resolveTopicArn(row.getTopicName());
            PublishRequest request = PublishRequest.builder()
                    .topicArn(topicArn)
                    .message(row.getPayload())
                    .messageAttributes(buildAttributes(row))
                    .build();

            CompletableFuture<PublishResponse> future = snsClient.publish(request);
            PublishResponse response = future.get(30, TimeUnit.SECONDS);

            markPublished(row.getId());
            publishedCounter.increment();
            publishTimer.record(Duration.ofNanos(System.nanoTime() - start));
            log.info("outbox.published eventId={} eventType={} topic={} snsMessageId={} latencyMs={}",
                    row.getEventId(), row.getEventType(), row.getTopicName(),
                    response.messageId(), (System.nanoTime() - start) / 1_000_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            recordFailure(row, "interrupted: " + e.getMessage());
        } catch (ExecutionException | RuntimeException | java.util.concurrent.TimeoutException e) {
            recordFailure(row, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private void markPublished(Long rowId) {
        updateTxTemplate.executeWithoutResult(status ->
                repository.findById(rowId).ifPresent(row -> {
                    row.setPublishedAt(Instant.now());
                    row.setLastError(null);
                    row.setLeaseId(null);
                    row.setLeaseExpiresAt(null);
                    repository.save(row);
                }));
    }

    private void recordFailure(OutboxEvent row, String error) {
        updateTxTemplate.executeWithoutResult(status ->
                repository.findById(row.getId()).ifPresent(r -> {
                    int newCount = r.getAttemptCount() + 1;
                    r.setAttemptCount(newCount);
                    r.setLastAttemptAt(Instant.now());
                    r.setLastError(truncate(error, 2000));
                    r.setLeaseId(null);
                    r.setLeaseExpiresAt(null);
                    repository.save(r);

                    if (newCount >= properties.outbox().maxAttempts()) {
                        deadLetterCounter.increment();
                        log.error("outbox.dead_letter eventId={} eventType={} topic={} attempts={} lastError={}",
                                r.getEventId(), r.getEventType(), r.getTopicName(), newCount, error);
                    } else {
                        failedCounter.increment();
                        log.warn("outbox.publish_failed eventId={} topic={} attempt={} error={}",
                                r.getEventId(), r.getTopicName(), newCount, error);
                    }
                }));
    }

    private String resolveTopicArn(String topicName) {
        return topicArnCache.computeIfAbsent(topicName, name -> {
            try {
                return snsClient.createTopic(b -> b.name(name))
                        .get(10, TimeUnit.SECONDS)
                        .topicArn();
            } catch (Exception e) {
                throw new IllegalStateException("Failed to resolve SNS topic ARN for " + name, e);
            }
        });
    }

    private static Map<String, MessageAttributeValue> buildAttributes(OutboxEvent row) {
        Map<String, MessageAttributeValue> attrs = new java.util.HashMap<>();
        attrs.put("eventType", MessageAttributeValue.builder()
                .dataType("String").stringValue(row.getEventType()).build());
        attrs.put("eventId", MessageAttributeValue.builder()
                .dataType("String").stringValue(row.getEventId()).build());
        attrs.put("schemaVersion", MessageAttributeValue.builder()
                .dataType("Number").stringValue(String.valueOf(row.getSchemaVersion())).build());
        if (row.getCorrelationId() != null) {
            attrs.put("correlationId", MessageAttributeValue.builder()
                    .dataType("String").stringValue(row.getCorrelationId()).build());
        }
        if (row.getAggregateType() != null) {
            attrs.put("aggregateType", MessageAttributeValue.builder()
                    .dataType("String").stringValue(row.getAggregateType()).build());
        }
        return attrs;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    @PreDestroy
    public void shutdown() {
        running.set(false);
        log.info("outbox.relay shutdown signal received; in-flight batch will complete");
    }
}
