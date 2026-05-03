package backend.backend.messaging;

import backend.backend.configuration.SubbyProperties;
import backend.backend.events.DomainEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

/**
 * Writes events to the {@code outbox_events} table for asynchronous publication
 * by {@link OutboxRelay}. Enforces the SNS payload-size ceiling at staging time
 * so oversized events never enter the pipeline.
 */
@Component
public class OutboxEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxEventPublisher.class);

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;
    private final SubbyProperties properties;
    private final Counter stagedCounter;
    private final Counter rejectedCounter;

    public OutboxEventPublisher(OutboxEventRepository repository,
                                ObjectMapper objectMapper,
                                SubbyProperties properties,
                                MeterRegistry meterRegistry) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.stagedCounter = Counter.builder("outbox.events.staged")
                .description("Events written to the outbox")
                .register(meterRegistry);
        this.rejectedCounter = Counter.builder("outbox.events.rejected")
                .description("Events rejected at staging (oversize, serialization failure)")
                .register(meterRegistry);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(String topicName, DomainEvent event) {
        if (topicName == null || topicName.isBlank()) {
            throw new IllegalArgumentException("topicName is required");
        }
        if (event == null) {
            throw new IllegalArgumentException("event is required");
        }

        final String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            rejectedCounter.increment();
            throw new IllegalStateException("Failed to serialize event " + event.eventType(), e);
        }

        int payloadBytes = payload.getBytes(StandardCharsets.UTF_8).length;
        int limit = properties.outbox().maxEventPayloadBytes();
        if (payloadBytes > limit) {
            rejectedCounter.increment();
            throw new IllegalArgumentException(
                    "Event payload " + payloadBytes + " bytes exceeds limit " + limit
                            + " (SNS hard limit is 256 KB — store large bodies in S3 and pass the key)");
        }

        OutboxEvent row = new OutboxEvent();
        row.setEventId(event.getEventId().toString());
        row.setAggregateType(event.aggregateType());
        row.setAggregateId(event.aggregateId());
        row.setEventType(event.eventType());
        row.setTopicName(topicName);
        row.setCorrelationId(resolveCorrelationId(event));
        row.setSchemaVersion(event.getSchemaVersion());
        row.setPayload(payload);
        row.setAttemptCount(0);

        repository.save(row);
        stagedCounter.increment();

        log.debug("outbox.staged eventId={} eventType={} topic={} bytes={}",
                row.getEventId(), row.getEventType(), topicName, payloadBytes);
    }

    private static String resolveCorrelationId(DomainEvent event) {
        if (event.getCorrelationId() != null && !event.getCorrelationId().isBlank()) {
            return event.getCorrelationId();
        }
        String mdc = MDC.get("correlationId");
        return (mdc != null && !mdc.isBlank()) ? mdc : null;
    }
}
