package io.contextguard.exception;


/**
 * Thrown when requested PR does not exist.
 */
public class PRNotFoundException extends RuntimeException {
    public PRNotFoundException(String message) {
        super(message);
    }
}