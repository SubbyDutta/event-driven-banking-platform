package backend.backend.service.findoc;

/**
 * Transient failure from findoc-verify — 5xx, timeouts, connect-refused. The
 * caller should bubble this up so SQS redelivers (and the circuit breaker gets
 * a recorded failure). Never extend with "retriable at best-effort" semantics;
 * this type is specifically what distinguishes redrive from DLQ routing.
 */
public class RetriableException extends RuntimeException {
    public RetriableException(String message) {
        super(message);
    }

    public RetriableException(String message, Throwable cause) {
        super(message, cause);
    }
}
