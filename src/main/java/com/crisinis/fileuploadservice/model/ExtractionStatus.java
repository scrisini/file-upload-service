package com.crisinis.fileuploadservice.model;

/** How far a file's extraction got. It starts when the file becomes AVAILABLE. */
public enum ExtractionStatus {

    /** The file isn't available yet: still being uploaded, scanned or promoted. */
    AWAITING_FILE,
    /** The file ended without becoming available (INFECTED, REJECTED, NOT_SCANNED): never extracted. */
    NOT_EXTRACTED,

    /** Waiting for, or being worked on by, an extraction worker (retried after temporary failures). */
    PENDING,
    /** The model's answer was read into the policy schema. */
    COMPLETED,
    /** Gave up: a permanent error, or temporary ones {@code extraction.max-attempts} times. */
    FAILED
}
