package com.crisinis.fileuploadservice.events;

import java.time.Instant;

import com.crisinis.fileuploadservice.model.ScanResult;

/** An EventBridge event from the scan queue, reduced to what the service acts on. */
public sealed interface ScanEvent {

    /**
     * S3's "Object Created": an upload landed in quarantine, which is when GuardDuty picks it up.
     *
     * @param time when S3 stored the object
     */
    record ObjectCreated(String bucket, String key, long size, String eTag, Instant time) implements ScanEvent {
    }

    /**
     * GuardDuty's "Malware Protection Object Scan Result".
     *
     * @param eTag   the version GuardDuty scanned
     * @param reason the threat's name, or GuardDuty's status reasons; null when there's none
     * @param time   when GuardDuty finished
     */
    record ScanCompleted(String bucket, String key, String eTag, ScanResult result, String reason, Instant time)
            implements ScanEvent {
    }

    /** Anything else: not an event this service handles. */
    record Unrecognised(String why) implements ScanEvent {
    }
}
