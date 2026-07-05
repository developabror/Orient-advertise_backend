package uz.orientadvertise.services.common.exception;

/**
 * Per-user rate or concurrency limit exceeded. Mapped to HTTP 429 by the global handler.
 * Distinct from {@link IllegalStateException} (409) — 429 conveys a transient, retryable
 * limit, not a permanent state conflict.
 */
public class RateLimitExceededException extends RuntimeException {

    public RateLimitExceededException(String message) {
        super(message);
    }
}
