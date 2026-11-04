package com.crisinis.fileuploadservice.extraction;

/**
 * An extraction attempt that didn't produce a policy.
 *
 * @see #retryable()
 */
public class ExtractionException extends RuntimeException {

    private final boolean retryable;
    private final String reason;

    public ExtractionException(boolean retryable, String reason, String message, Throwable cause) {
        super(reason + ": " + message, cause);
        this.retryable = retryable;
        this.reason = reason;
    }

    /** Whether trying again may succeed (throttling, a timeout, a garbled answer), or never will. */
    public boolean retryable() {
        return retryable;
    }

    /** A stable code for logs, metrics and the stored failure, e.g. {@code MODEL_THROTTLED}. */
    public String reason() {
        return reason;
    }
}
