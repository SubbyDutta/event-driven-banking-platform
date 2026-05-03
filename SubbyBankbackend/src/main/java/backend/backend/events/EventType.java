package backend.backend.events;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the wire-level {@code eventType} string for a {@link DomainEvent} subclass.
 * Used both for SNS message attributes (filter-policy matching) and for JSON
 * serialization of the event envelope.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface EventType {
    String value();
}
