package com.crisinis.fileuploadservice.service;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.crisinis.fileuploadservice.events.ScanEvent;
import com.crisinis.fileuploadservice.logging.LogEvent;
import com.crisinis.fileuploadservice.model.FileRecord;
import com.crisinis.fileuploadservice.model.FileStatus;
import com.crisinis.fileuploadservice.model.FileType;
import com.crisinis.fileuploadservice.model.ScanResult;
import com.crisinis.fileuploadservice.model.UploadMethod;
import com.crisinis.fileuploadservice.persistence.FileRepository;
import com.crisinis.fileuploadservice.persistence.FileRepository.Update;
import com.crisinis.fileuploadservice.storage.FileStorage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Acts on what S3 and GuardDuty report: an upload landing in quarantine (GuardDuty picks it up),
 * and GuardDuty's result. A clean file is checked (its content must be the declared type) and
 * promoted to the clean bucket. Everything here is safe to repeat: SQS delivers at least once, in
 * any order, to any instance.
 */
@Slf4j
@Service
public class ScanOutcomes {

    /** What handling an event amounted to. */
    public enum Outcome {
        /** Acted on, or already done by an earlier delivery. */
        HANDLED,
        /** The object isn't one of this service's uploads, or its record no longer exists. */
        UNKNOWN_FILE
    }

    /** The {@code reason} of a result read from the object's tag instead of GuardDuty's event. */
    public static final String RECOVERED_FROM_TAG = "RECOVERED_FROM_OBJECT_TAG";

    private final FileRepository files;
    private final FileStorage storage;
    private final Clock clock;
    private final Map<ScanResult, Timer> scanDuration = new EnumMap<>(ScanResult.class);
    private final Map<UploadMethod, Timer> timeToAvailable = new EnumMap<>(UploadMethod.class);
    private final Map<UploadMethod, Counter> bytes = new EnumMap<>(UploadMethod.class);

    public ScanOutcomes(FileRepository files, FileStorage storage, Clock clock, MeterRegistry registry) {
        this.files = files;
        this.storage = storage;
        this.clock = clock;
        for (ScanResult result : ScanResult.values()) {
            scanDuration.put(result, Timer.builder("file-upload-service.scan.duration")
                    .description("From the upload landing in quarantine to GuardDuty's result")
                    .tag("result", result.name()).register(registry));
        }
        for (UploadMethod method : UploadMethod.values()) {
            String tag = method.name().toLowerCase(Locale.ROOT);
            timeToAvailable.put(method, Timer.builder("file-upload-service.files.time-to-available")
                    .description("From the upload landing in quarantine to the file being available: what callers wait")
                    .tag("method", tag).register(registry));
            bytes.put(method, Counter.builder("file-upload-service.uploads.bytes").baseUnit("bytes")
                    .description("Bytes landed in quarantine").tag("method", tag).register(registry));
        }
    }

    public Outcome handle(ScanEvent event) {
        return switch (event) {
            case ScanEvent.ObjectCreated created -> submitted(created);
            case ScanEvent.ScanCompleted completed -> scanned(completed);
            case ScanEvent.Unrecognised unrecognised ->
                    throw new IllegalArgumentException("not an event to handle: " + unrecognised.why());
        };
    }

    private Outcome submitted(ScanEvent.ObjectCreated event) {
        Optional<Update> update = storage.fileIdOf(event.bucket(), event.key())
                .flatMap(id -> files.markSubmitted(id, event.time()));
        if (update.isEmpty()) {
            return Outcome.UNKNOWN_FILE;
        }
        FileRecord file = update.get().file();
        if (update.get().changed()) {
            bytes.get(file.method()).increment(file.size());
            LogEvent.FILE_SUBMITTED_FOR_SCAN.at(log).with("file_id", file.id()).with("method", file.method())
                    .with("submitted_at", file.submittedAt()).log();
        }
        return Outcome.HANDLED;
    }

    private Outcome scanned(ScanEvent.ScanCompleted event) {
        Optional<Update> update = storage.fileIdOf(event.bucket(), event.key())
                .flatMap(id -> files.recordScan(id, event.result(), event.time(), event.reason(), event.eTag()));
        if (update.isEmpty()) {
            return Outcome.UNKNOWN_FILE;
        }
        carryOn(update.get(), event.result());
        return Outcome.HANDLED;
    }

    /**
     * Finishes a promotion from the record alone, with the ETag it stored when the scan was
     * recorded: for a file left PROMOTING when the process handling it died and its event can't be
     * delivered again. Safe to repeat, and to run while the event is also being handled.
     */
    public void resumePromotion(FileRecord file) {
        if (file.status() == FileStatus.PROMOTING) {
            promote(file, file.scannedETag());
        }
    }

    /**
     * Applies a scan result read from the object's GuardDuty tag, for a file whose result event
     * never reached the service. The ETag scanned isn't known, but quarantine objects can't be
     * replaced ({@code If-None-Match: *}), so the object there is the one scanned.
     *
     * @return the file as it is now; empty if it has no record
     */
    public Optional<FileRecord> applyRecoveredResult(String id, ScanResult result, Instant at) {
        Optional<Update> update = files.recordScan(id, result, at, RECOVERED_FROM_TAG, null);
        update.ifPresent(u -> carryOn(u, result));
        return update.map(Update::file);
    }

    /** After a result is recorded: report it if this call recorded it, and promote a clean file. */
    private void carryOn(Update update, ScanResult result) {
        FileRecord file = update.file();
        if (update.changed()) {
            report(file, result);
        }
        if (file.status() == FileStatus.PROMOTING) {
            // a redelivery after a failed promotion lands here too, and carries on
            promote(file, file.scannedETag());
        }
    }

    private void report(FileRecord file, ScanResult result) {
        file.scanDuration().ifPresent(scanDuration.get(result)::record);
        long scanMs = file.scanDuration().map(java.time.Duration::toMillis).orElse(-1L);
        switch (result) {
            case NO_THREATS_FOUND -> { }
            case THREATS_FOUND -> LogEvent.FILE_INFECTED.at(log).with("file_id", file.id())
                    .with("threat", file.reason()).with("method", file.method()).with("scan_ms", scanMs).log();
            case UNSUPPORTED, ACCESS_DENIED, FAILED -> LogEvent.FILE_NOT_SCANNED.at(log).with("file_id", file.id())
                    .with("scan_result", result).with("status_reasons", file.reason()).log();
        }
    }

    /** Checks a clean file's content and moves it to the clean bucket. */
    private void promote(FileRecord file, String eTag) {
        Optional<byte[]> signature = storage.readSignature(file.id());
        if (signature.isEmpty()) {
            // promoted by an earlier delivery that failed before recording it, or removed
            finish(file, storage.isPromoted(file.id()) ? null : "MISSING_FROM_QUARANTINE");
            return;
        }
        if (!file.type().matches(signature.get())) {
            finish(file, "CONTENT_MISMATCH: declared " + file.type() + ", content is "
                    + FileType.detect(signature.get()).map(Enum::name).orElse("unrecognised"));
            return;
        }
        switch (storage.promote(file.id(), file.type(), eTag)) {
            case COPIED -> finish(file, null);
            case SOURCE_CHANGED -> finish(file, "CHANGED_AFTER_SCAN");
            case SOURCE_MISSING -> finish(file, storage.isPromoted(file.id()) ? null : "MISSING_FROM_QUARANTINE");
        }
    }

    /** Ends the promotion: AVAILABLE when {@code rejection} is null, otherwise REJECTED for that reason. */
    private void finish(FileRecord file, String rejection) {
        FileStatus outcome = rejection == null ? FileStatus.AVAILABLE : FileStatus.REJECTED;
        Optional<Update> update = files.finishPromotion(file.id(), outcome, clock.instant(), rejection);
        if (update.isEmpty() || !update.get().changed()) {
            return;
        }
        FileRecord finished = update.get().file();
        if (outcome == FileStatus.AVAILABLE) {
            finished.timeToAvailable().ifPresent(timeToAvailable.get(finished.method())::record);
            LogEvent.FILE_AVAILABLE.at(log).with("file_id", finished.id()).with("method", finished.method())
                    .with("scan_ms", finished.scanDuration().map(java.time.Duration::toMillis).orElse(-1L))
                    .with("total_ms", finished.timeToAvailable().map(java.time.Duration::toMillis).orElse(-1L))
                    .log();
        } else {
            LogEvent.FILE_REJECTED_AFTER_SCAN.at(log).with("file_id", finished.id()).with("reason", rejection).log();
        }
    }
}
