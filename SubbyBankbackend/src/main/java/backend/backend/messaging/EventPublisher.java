package backend.backend.messaging;

import backend.backend.events.DomainEvent;

/**
 * Staging API for outbound events. Implementations write to the transactional
 * outbox — they MUST be called from inside a {@code @Transactional} method so the
 * event commits atomically with the business writes that produced it. A separate
 * relay publishes rows to SNS asynchronously.
 *
 * <p>Callers never talk to SNS directly; direct publish would break exactly-once
 * guarantees if the outer transaction rolled back after the send succeeded.
 */
public interface EventPublisher {

    /**
     * Stage a domain event for publication to the given SNS topic. Call from
     * within a {@code @Transactional} business method.
     *
     * @param topicName fully-qualified SNS topic name (not ARN — the relay resolves it)
     * @param event     the event to publish; {@link DomainEvent#eventType()} and
     *                  aggregate metadata are used to populate the outbox row
     */
    void publish(String topicName, DomainEvent event);
}
