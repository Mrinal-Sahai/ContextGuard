package io.contextguard.exception;

/**
 * Thrown when AI service calls fail.
 */
public class AIServiceException extends RuntimeException {
    public AIServiceException(String message) {
        super(message);
    }

    public AIServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
