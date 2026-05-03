package backend.backend.messaging;

/**
 * Thrown by a consumer when an event cannot succeed even on retry (e.g. JSON
 * parse failure, schema violation, missing referenced data that will never
 * appear). {@link BaseSqsHandler} catches this and fast-tracks the message to
 * the DLQ without burning through the normal retry budget.
 */
public class NonRetriableException extends RuntimeException {

    public NonRetriableException(String message) {
        super(message);
    }

    public NonRetriableException(String message, Throwable cause) {
        super(message, cause);
    }
}
