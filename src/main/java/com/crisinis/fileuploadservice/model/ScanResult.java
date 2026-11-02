package com.crisinis.fileuploadservice.model;

import java.util.Optional;

/** GuardDuty Malware Protection for S3's {@code scanResultStatus}, and the status each leads to. */
public enum ScanResult {

    NO_THREATS_FOUND(FileStatus.PROMOTING),
    THREATS_FOUND(FileStatus.INFECTED),
    /** Skipped: password-protected, too large, an unsupported storage class or archive. */
    UNSUPPORTED(FileStatus.NOT_SCANNED),
    /** Skipped: GuardDuty's role can't read the object. A configuration problem, not the file's. */
    ACCESS_DENIED(FileStatus.NOT_SCANNED),
    FAILED(FileStatus.NOT_SCANNED);

    private final FileStatus next;

    ScanResult(FileStatus next) {
        this.next = next;
    }

    /** The file's status once this result is recorded. */
    public FileStatus next() {
        return next;
    }

    /** The result for GuardDuty's value; empty for a value this version doesn't know. */
    public static Optional<ScanResult> of(String scanResultStatus) {
        for (ScanResult result : values()) {
            if (result.name().equals(scanResultStatus)) {
                return Optional.of(result);
            }
        }
        return Optional.empty();
    }
}
