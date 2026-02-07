package io.contextguard.exception;

/**
 * Thrown when GitHub API calls fail.
 */
public class GitHubApiException extends RuntimeException {
    public GitHubApiException(String message) {
        super(message);
    }

    public GitHubApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
