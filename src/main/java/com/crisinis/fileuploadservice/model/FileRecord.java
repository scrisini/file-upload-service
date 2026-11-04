package com.crisinis.fileuploadservice.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * One uploaded file and how far it got. Times are when this service learned of each step:
 *
 * @param submittedAt when the object landed in quarantine, which is when GuardDuty picks it up
 *                    (GuardDuty publishes no event of its own when a scan starts); null before
 * @param scannedAt   when GuardDuty reported the result; null before
 * @param availableAt when it reached the clean bucket; null unless {@link FileStatus#AVAILABLE}
 * @param scanResult  GuardDuty's result; null before
 * @param reason      why it's not available, for support: the threat name, GuardDuty's status
 *                    reasons, or the rejection; null otherwise. Not shown to callers.
 * @param scannedETag the ETag of the version GuardDuty scanned; null before, or when the result came
 *                    from the object's tag. Promotion copies only this version, so a promotion
 *                    interrupted by a crash can be finished from the record alone.
 * @param extraction  what the file is to be extracted as once AVAILABLE; null when its uploader didn't opt in
 */
public record FileRecord(
        String id,
        String fileName,
        FileType type,
        long size,
        UploadMethod method,
        FileStatus status,
        Instant createdAt,
        Instant submittedAt,
        Instant scannedAt,
        Instant availableAt,
        ScanResult scanResult,
        String reason,
        String scannedETag,
        ExtractionKind extraction) {

    /** A new file whose bytes haven't arrived yet. */
    public static FileRecord awaitingUpload(String id, String fileName, FileType type, long size,
                                            UploadMethod method, Instant createdAt) {
        return awaitingUpload(id, fileName, type, size, method, createdAt, null);
    }

    /** A new file whose bytes haven't arrived yet, to be extracted as {@code extraction} once available. */
    public static FileRecord awaitingUpload(String id, String fileName, FileType type, long size,
                                            UploadMethod method, Instant createdAt, ExtractionKind extraction) {
        return new FileRecord(id, fileName, type, size, method, FileStatus.AWAITING_UPLOAD, createdAt,
                null, null, null, null, null, null, extraction);
    }

    /** How long GuardDuty took, from the object landing to the result; empty until both are known. */
    public Optional<Duration> scanDuration() {
        return submittedAt == null || scannedAt == null
                ? Optional.empty() : Optional.of(Duration.between(submittedAt, scannedAt));
    }

    /** How long callers waited, from the object landing to the file being available; empty until then. */
    public Optional<Duration> timeToAvailable() {
        return submittedAt == null || availableAt == null
                ? Optional.empty() : Optional.of(Duration.between(submittedAt, availableAt));
    }
}
