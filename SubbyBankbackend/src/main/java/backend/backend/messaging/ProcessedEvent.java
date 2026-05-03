package backend.backend.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * Composite-keyed row that records which consumer has already processed which
 * event. Used by {@link IdempotencyGuard} to block duplicate deliveries.
 */
@Entity
@Table(name = "processed_events")
@IdClass(ProcessedEvent.Key.class)
public class ProcessedEvent {

    @Id
    @Column(name = "event_id", length = 64)
    private String eventId;

    @Id
    @Column(name = "consumer_name", length = 128)
    private String consumerName;

    @Column(name = "processed_at", insertable = false, updatable = false)
    private Instant processedAt;

    public ProcessedEvent() {}

    public ProcessedEvent(String eventId, String consumerName) {
        this.eventId = eventId;
        this.consumerName = consumerName;
    }

    public String getEventId() { return eventId; }
    public String getConsumerName() { return consumerName; }
    public Instant getProcessedAt() { return processedAt; }

    public static class Key implements Serializable {
        private String eventId;
        private String consumerName;

        public Key() {}

        public Key(String eventId, String consumerName) {
            this.eventId = eventId;
            this.consumerName = consumerName;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key other)) return false;
            return Objects.equals(eventId, other.eventId)
                    && Objects.equals(consumerName, other.consumerName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(eventId, consumerName);
        }
    }
}
