package com.walmal.gateway.exception;

/**
 * Thrown when a client exceeds the configured rate limit.
 */
public class RateLimitExceededException extends RuntimeException {

    public RateLimitExceededException() {
        super("Rate limit exceeded. Try again later.");
    }

    public RateLimitExceededException(String message) {
        super(message);
    }
}
