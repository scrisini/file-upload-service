package com.crisinis.fileuploadservice.service;

import org.springframework.http.HttpStatus;

/**
 * An upload refused before anything was stored, with the HTTP status that says why: 400 invalid,
 * 411 no length, 413 too large, 415 type not accepted or content not of the declared type.
 */
public class UploadRejectedException extends RuntimeException {

    /** Why, as a stable value for logs and metrics. */
    public enum Reason {
        INVALID(HttpStatus.BAD_REQUEST),
        INCOMPLETE(HttpStatus.BAD_REQUEST),
        LENGTH_REQUIRED(HttpStatus.LENGTH_REQUIRED),
        TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE),
        UNSUPPORTED_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE),
        CONTENT_MISMATCH(HttpStatus.UNSUPPORTED_MEDIA_TYPE);

        private final HttpStatus status;

        Reason(HttpStatus status) {
            this.status = status;
        }

        public HttpStatus status() {
            return status;
        }
    }

    private final Reason reason;

    public UploadRejectedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
