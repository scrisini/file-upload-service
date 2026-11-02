package com.crisinis.fileuploadservice.model;

/**
 * Where a file is in its life. Both upload styles share it:
 *
 * <pre>
 * AWAITING_UPLOAD ──(object lands in quarantine)──▶ SCANNING ──NO_THREATS_FOUND──▶ PROMOTING ──▶ AVAILABLE
 *                                                       │                               └──────▶ REJECTED
 *                                                       ├──THREATS_FOUND──▶ INFECTED
 *                                                       └──UNSUPPORTED, ACCESS_DENIED, FAILED──▶ NOT_SCANNED
 * </pre>
 *
 * A scan result can arrive before the event saying the object landed (the queue doesn't keep
 * order), so a result is accepted from {@code AWAITING_UPLOAD} too.
 */
public enum FileStatus {

    /** A presigned URL was issued (or a stream started); nothing is in quarantine yet. */
    AWAITING_UPLOAD,
    /** In the quarantine bucket; GuardDuty scans it on arrival and hasn't reported yet. */
    SCANNING,
    /** Scanned clean; its content is being checked and it's being copied to the clean bucket. */
    PROMOTING,
    /** In the clean bucket and ready to serve. */
    AVAILABLE,
    /** GuardDuty found a threat. Stays in quarantine, never served. */
    INFECTED,
    /** Scanned clean but refused: content not of the declared type, or changed after the scan. */
    REJECTED,
    /** GuardDuty couldn't scan it (UNSUPPORTED, ACCESS_DENIED, FAILED). Stays in quarantine, never served. */
    NOT_SCANNED;

    /** Whether the file can't change status any more. */
    public boolean isFinal() {
        return this == AVAILABLE || this == INFECTED || this == REJECTED || this == NOT_SCANNED;
    }
}
