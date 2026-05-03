package backend.backend.Exception;

public class FraudServiceUnavailableException extends RuntimeException {
    public FraudServiceUnavailableException(String message) {
        super(message);
    }

    public FraudServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
