package com.crisinis.fileuploadservice.model;

import java.time.Instant;

/**
 * A file's extraction, for internal callers.
 *
 * @param requestedAt when the file became AVAILABLE and the extraction was queued
 * @param attempts    calls to the model so far
 * @param modelId     the model that answered; null before
 * @param policy      the result; null unless COMPLETED
 * @param failure     why it FAILED; null otherwise
 */
public record ExtractionRecord(
        String fileId,
        ExtractionKind kind,
        ExtractionStatus status,
        Instant requestedAt,
        Instant completedAt,
        int attempts,
        String modelId,
        PolicyExtraction policy,
        String failure) {
}
