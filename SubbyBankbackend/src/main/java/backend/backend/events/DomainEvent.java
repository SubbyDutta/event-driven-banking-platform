package backend.backend.events;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.UUID;

/**
 * Base class for every domain event published to SNS through the outbox.
 *
 * <p>Subclasses must be annotated with {@link EventType} to declare their wire-level
 * type string. The envelope fields ({@code eventId}, {@code occurredAt},
 * {@code schemaVersion}, {@code correlationId}) are carried alongside the payload
 * so consumers can reason about causality, idempotency, and schema evolution.
 */
public abstract class DomainEvent {

    private final UUID eventId;
    private final Instant occurredAt;
    private final int schemaVersion;
    private final String correlationId;

    protected DomainEvent(UUID eventId, Instant occurredAt, int schemaVersion, String correlationId) {
        this.eventId = eventId != null ? eventId : UUID.randomUUID();
        this.occurredAt = occurredAt != null ? occurredAt : Instant.now();
        this.schemaVersion = schemaVersion <= 0 ? 1 : schemaVersion;
        this.correlationId = correlationId;
    }

    protected DomainEvent() {
        this(null, null, 1, null);
    }

    protected DomainEvent(String correlationId) {
        this(null, null, 1, correlationId);
    }

    public UUID getEventId() {
        return eventId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    /**
     * Wire-level event type string — resolved from the {@link EventType} annotation
     * on the concrete subclass. Used for SNS message attributes and filter policies.
     */
    @JsonIgnore
    public String eventType() {
        EventType annotation = this.getClass().getAnnotation(EventType.class);
        if (annotation == null) {
            throw new IllegalStateException(
                    "DomainEvent subclass " + this.getClass().getSimpleName()
                            + " is missing the @EventType annotation");
        }
        return annotation.value();
    }

    /**
     * Aggregate type this event relates to (e.g. "loan_application", "kyc_application").
     * Used by the outbox for aggregate-scoped indexing and troubleshooting.
     */
    @JsonIgnore
    public abstract String aggregateType();

    /**
     * Business-level identifier of the aggregate this event describes.
     */
    @JsonIgnore
    public abstract String aggregateId();
}
