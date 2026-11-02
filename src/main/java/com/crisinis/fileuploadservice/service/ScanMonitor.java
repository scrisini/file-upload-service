package com.crisinis.fileuploadservice.service;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import com.crisinis.fileuploadservice.config.FileUploadServiceProperties;
import com.crisinis.fileuploadservice.logging.LogEvent;
import com.crisinis.fileuploadservice.model.ScanSummary;
import com.crisinis.fileuploadservice.persistence.FileRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * How many files GuardDuty is scanning, across all instances. GuardDuty reports results but no
 * count of scans in progress, so the count is of this service's records that landed in quarantine
 * and have no result yet. Every {@code scan.monitor-interval} it refreshes the
 * {@code file-upload-service.scan.in-progress} gauge and reports scans that are overdue, each once
 * across all instances.
 */
@Slf4j
@Service
public class ScanMonitor {

    /** Overdue scans reported per run, at most; the rest wait for the next run. */
    private static final int OVERDUE_BATCH = 100;

    private final FileRepository files;
    private final FileUploadServiceProperties.Scan scan;
    private final Clock clock;
    private final AtomicLong inProgress = new AtomicLong();
    private final Counter overdue;

    public ScanMonitor(FileRepository files, FileUploadServiceProperties properties, Clock clock, MeterRegistry registry) {
        this.files = files;
        this.scan = properties.scan();
        this.clock = clock;
        Gauge.builder("file-upload-service.scan.in-progress", inProgress, AtomicLong::get)
                .description("Files in quarantine waiting for GuardDuty's result, across all instances")
                .register(registry);
        this.overdue = Counter.builder("file-upload-service.scan.overdue")
                .description("Scans with no result after scan.overdue-after").register(registry);
    }

    /** The count and the oldest scan, read now. */
    public ScanSummary summary() {
        return new ScanSummary(files.countScanning(), files.oldestScanning().orElse(null), scan.overdueAfter());
    }

    @Scheduled(fixedDelayString = "${file-upload-service.scan.monitor-interval:15s}",
            initialDelayString = "${file-upload-service.scan.monitor-interval:15s}")
    void refresh() {
        if (!scan.enabled()) {
            return;
        }
        try {
            inProgress.set(files.countScanning());
            reportOverdue(clock.instant());
        } catch (DependencyUnavailableException e) {
            LogEvent.SCAN_MONITOR_FAILED.at(log).with("operation", e.operation()).cause(e).log();
        }
    }

    private void reportOverdue(Instant now) {
        for (String id : files.scanningSince(now.minus(scan.overdueAfter()), OVERDUE_BATCH)) {
            if (files.claimOverdueReport(id, now)) {
                overdue.increment();
                LogEvent.SCAN_OVERDUE.at(log).with("file_id", id)
                        .with("overdue_after", scan.overdueAfter()).log();
            }
        }
    }
}
