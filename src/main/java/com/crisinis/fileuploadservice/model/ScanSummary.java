package com.crisinis.fileuploadservice.model;

import java.time.Duration;
import java.time.Instant;

/**
 * Files GuardDuty is scanning now, across all instances.
 *
 * @param inProgress   files in quarantine with no result yet
 * @param oldestSubmittedAt when the longest-waiting of them landed; null when none
 * @param overdueAfter how long a scan may take before it's reported as overdue
 */
public record ScanSummary(long inProgress, Instant oldestSubmittedAt, Duration overdueAfter) {
}
