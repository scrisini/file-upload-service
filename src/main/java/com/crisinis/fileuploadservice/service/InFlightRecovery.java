package com.crisinis.fileuploadservice.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import com.crisinis.fileuploadservice.config.FileUploadServiceProperties;
import com.crisinis.fileuploadservice.logging.LogEvent;
import com.crisinis.fileuploadservice.model.FileRecord;
import com.crisinis.fileuploadservice.model.FileStatus;
import com.crisinis.fileuploadservice.model.ScanResult;
import com.crisinis.fileuploadservice.persistence.FileRepository;
import com.crisinis.fileuploadservice.storage.FileStorage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Finishes files that a crash, a kill or a lost event left in flight, from what DynamoDB and S3
 * hold, without waiting for their events (README "Crash safety"). Every
 * {@code scan.recovery-interval}, on every instance:
 *
 * <ul>
 *   <li><b>promotions</b> PROMOTING longer than {@code scan.resume-promotion-after}: the process
 *       promoting them died and their event may be dead-lettered. Resumed from the record, which
 *       holds the ETag GuardDuty scanned;</li>
 *   <li><b>scans</b> SCANNING longer than {@code scan.overdue-after}: if GuardDuty's result is on
 *       the object's tag, its event never arrived. The result is applied from the tag.</li>
 * </ul>
 *
 * Instances may recover the same file at once: every step is a conditional write or an idempotent
 * S3 call, so the second one changes nothing.
 */
@Slf4j
@Service
public class InFlightRecovery {

    /** Files looked at per stage per run, at most; the rest wait for the next run. */
    static final int BATCH = 50;

    private final FileRepository files;
    private final FileStorage storage;
    private final ScanOutcomes outcomes;
    private final FileUploadServiceProperties.Scan scan;
    private final Clock clock;
    private final Counter resumed;
    private final Counter recovered;

    public InFlightRecovery(FileRepository files, FileStorage storage, ScanOutcomes outcomes,
                            FileUploadServiceProperties properties, Clock clock, MeterRegistry registry) {
        this.files = files;
        this.storage = storage;
        this.outcomes = outcomes;
        this.scan = properties.scan();
        this.clock = clock;
        this.resumed = recoveryCounter(registry, "promotion_resumed");
        this.recovered = recoveryCounter(registry, "result_from_tag");
    }

    @Scheduled(fixedDelayString = "${file-upload-service.scan.recovery-interval:1m}",
            initialDelayString = "${file-upload-service.scan.recovery-interval:1m}")
    void recover() {
        if (!scan.enabled()) {
            return;
        }
        Instant now = clock.instant();
        resumePromotions(now);
        recoverScanResults(now);
    }

    /** Finishes promotions whose process died. */
    void resumePromotions(Instant now) {
        for (String id : stuck(FileStatus.PROMOTING, now.minus(scan.resumePromotionAfter()))) {
            try {
                Optional<FileRecord> file = files.find(id).filter(f -> f.status() == FileStatus.PROMOTING);
                if (file.isPresent()) {
                    resumed.increment();
                    LogEvent.PROMOTION_RESUMED.at(log).with("file_id", id).with("scanned_at", file.get().scannedAt()).log();
                    outcomes.resumePromotion(file.get());
                }
            } catch (RuntimeException e) {
                LogEvent.RECOVERY_FAILED.at(log).with("file_id", id).with("stage", FileStatus.PROMOTING).cause(e).log();
            }
        }
    }

    /** Applies results GuardDuty tagged on objects whose result event never arrived. */
    void recoverScanResults(Instant now) {
        for (String id : stuck(FileStatus.SCANNING, now.minus(scan.overdueAfter()))) {
            try {
                Optional<ScanResult> result = storage.scanStatusTag(id).flatMap(ScanResult::of);
                if (result.isPresent()) {
                    recovered.increment();
                    LogEvent.SCAN_RESULT_RECOVERED.at(log).with("file_id", id).with("scan_result", result.get()).log();
                    outcomes.applyRecoveredResult(id, result.get(), now);
                }
            } catch (RuntimeException e) {
                LogEvent.RECOVERY_FAILED.at(log).with("file_id", id).with("stage", FileStatus.SCANNING).cause(e).log();
            }
        }
    }

    /** Files in {@code stage} since before {@code cutoff}; none if DynamoDB can't say (the next run retries). */
    private Iterable<String> stuck(FileStatus stage, Instant cutoff) {
        try {
            return files.inStageSince(stage, cutoff, BATCH);
        } catch (DependencyUnavailableException e) {
            LogEvent.RECOVERY_FAILED.at(log).with("stage", stage).cause(e).log();
            return java.util.List.of();
        }
    }

    private static Counter recoveryCounter(MeterRegistry registry, String action) {
        return Counter.builder("file-upload-service.recovery")
                .description("Files finished from their stored state after a crash or a lost event")
                .tag("action", action).register(registry);
    }
}
