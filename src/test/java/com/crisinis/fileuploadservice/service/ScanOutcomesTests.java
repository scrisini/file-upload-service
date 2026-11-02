package com.crisinis.fileuploadservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import com.crisinis.fileuploadservice.events.ScanEvent;
import com.crisinis.fileuploadservice.model.FileRecord;
import com.crisinis.fileuploadservice.model.FileStatus;
import com.crisinis.fileuploadservice.model.FileType;
import com.crisinis.fileuploadservice.model.ScanResult;
import com.crisinis.fileuploadservice.model.UploadMethod;
import com.crisinis.fileuploadservice.persistence.FileRepository;
import com.crisinis.fileuploadservice.persistence.FileRepository.Update;
import com.crisinis.fileuploadservice.storage.FileStorage;
import com.crisinis.fileuploadservice.storage.FileStorage.Promotion;
import com.crisinis.fileuploadservice.support.TestProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

class ScanOutcomesTests {

    static final String ID = "0b8e2d4a-55a3-4d0e-9f53-3e1b5f0a7c11";
    static final String KEY = "uploads/" + ID;
    static final String BUCKET = "file-upload-quarantine";
    static final Instant LANDED = Instant.parse("2026-11-02T10:00:00Z");
    static final Instant SCANNED = LANDED.plusSeconds(20);
    static final Instant NOW = SCANNED.plusSeconds(1);
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    private final FileRepository files = mock(FileRepository.class);
    private final FileStorage storage = mock(FileStorage.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ScanOutcomes outcomes = new ScanOutcomes(files, storage, Clock.fixed(NOW, ZoneOffset.UTC), registry);

    @BeforeEach
    void ourBucket() {
        FileStorage real = new FileStorage(mock(S3Client.class), mock(S3Presigner.class), TestProperties.defaults());
        when(storage.fileIdOf(any(), any())).thenAnswer(call -> real.fileIdOf(call.getArgument(0), call.getArgument(1)));
    }

    static FileRecord file(FileStatus status, ScanResult result, Instant availableAt) {
        return new FileRecord(ID, "a.png", FileType.PNG, 8, UploadMethod.PRESIGNED_URL, status, LANDED.minusSeconds(5),
                LANDED, result == null ? null : SCANNED, availableAt, result, null,
                result == null ? null : "etag-1");
    }

    static ScanEvent.ScanCompleted result(ScanResult result, String reason) {
        return new ScanEvent.ScanCompleted(BUCKET, KEY, "etag-1", result, reason, SCANNED);
    }

    @Test
    void anUploadLandingStartsTheScan() {
        when(files.markSubmitted(ID, LANDED)).thenReturn(Optional.of(new Update(true, file(FileStatus.SCANNING, null, null))));

        assertThat(outcomes.handle(new ScanEvent.ObjectCreated(BUCKET, KEY, 8, "etag-1", LANDED)))
                .isEqualTo(ScanOutcomes.Outcome.HANDLED);
        assertThat(registry.get("file-upload-service.uploads.bytes").tag("method", "presigned_url").counter().count()).isEqualTo(8);
    }

    @Test
    void aCleanFileIsCheckedAndPromoted() {
        when(files.recordScan(ID, ScanResult.NO_THREATS_FOUND, SCANNED, null, "etag-1"))
                .thenReturn(Optional.of(new Update(true, file(FileStatus.PROMOTING, ScanResult.NO_THREATS_FOUND, null))));
        when(storage.readSignature(ID)).thenReturn(Optional.of(PNG));
        when(storage.promote(ID, FileType.PNG, "etag-1")).thenReturn(Promotion.COPIED);
        when(files.finishPromotion(ID, FileStatus.AVAILABLE, NOW, null))
                .thenReturn(Optional.of(new Update(true, file(FileStatus.AVAILABLE, ScanResult.NO_THREATS_FOUND, NOW))));

        outcomes.handle(result(ScanResult.NO_THREATS_FOUND, null));

        verify(files).finishPromotion(ID, FileStatus.AVAILABLE, NOW, null);
        assertThat(registry.get("file-upload-service.scan.duration").tag("result", "NO_THREATS_FOUND").timer()
                .totalTime(java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(20);
        assertThat(registry.get("file-upload-service.files.time-to-available").tag("method", "presigned_url").timer()
                .totalTime(java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(21);
    }

    @Test
    void anInfectedFileStaysInQuarantine() {
        when(files.recordScan(ID, ScanResult.THREATS_FOUND, SCANNED, "EICAR-Test-File", "etag-1"))
                .thenReturn(Optional.of(new Update(true, file(FileStatus.INFECTED, ScanResult.THREATS_FOUND, null))));

        outcomes.handle(result(ScanResult.THREATS_FOUND, "EICAR-Test-File"));

        verify(storage, never()).promote(any(), any(), any());
        verify(storage, never()).readSignature(any());
    }

    @Test
    void aCleanFileOfTheWrongTypeIsRejected() {
        when(files.recordScan(any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(new Update(true, file(FileStatus.PROMOTING, ScanResult.NO_THREATS_FOUND, null))));
        when(storage.readSignature(ID)).thenReturn(Optional.of("%PDF-1.7".getBytes()));
        when(files.finishPromotion(eq(ID), eq(FileStatus.REJECTED), any(), any())).thenReturn(Optional.empty());

        outcomes.handle(result(ScanResult.NO_THREATS_FOUND, null));

        verify(files).finishPromotion(ID, FileStatus.REJECTED, NOW, "CONTENT_MISMATCH: declared PNG, content is PDF");
        verify(storage, never()).promote(any(), any(), any());
    }

    @Test
    void aRedeliveryFinishesAPromotionThatFailedHalfway() {
        // the earlier delivery recorded the result and copied the file, then failed before finishing
        when(files.recordScan(any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(new Update(false, file(FileStatus.PROMOTING, ScanResult.NO_THREATS_FOUND, null))));
        when(storage.readSignature(ID)).thenReturn(Optional.empty());
        when(storage.isPromoted(ID)).thenReturn(true);
        when(files.finishPromotion(any(), any(), any(), any())).thenReturn(Optional.empty());

        outcomes.handle(result(ScanResult.NO_THREATS_FOUND, null));

        verify(files).finishPromotion(ID, FileStatus.AVAILABLE, NOW, null);
        assertThat(registry.get("file-upload-service.scan.duration").tag("result", "NO_THREATS_FOUND").timer().count())
                .as("measured once, by the delivery that recorded it").isZero();
    }

    @Test
    void aRedeliveryAfterTheFileIsFinishedChangesNothing() {
        when(files.recordScan(any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(new Update(false, file(FileStatus.AVAILABLE, ScanResult.NO_THREATS_FOUND, NOW))));

        outcomes.handle(result(ScanResult.NO_THREATS_FOUND, null));

        verify(storage, never()).readSignature(any());
        verify(files, never()).finishPromotion(any(), any(), any(), any());
    }

    @Test
    void aFileReplacedAfterItsScanIsRejected() {
        when(files.recordScan(any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(new Update(true, file(FileStatus.PROMOTING, ScanResult.NO_THREATS_FOUND, null))));
        when(storage.readSignature(ID)).thenReturn(Optional.of(PNG));
        when(storage.promote(ID, FileType.PNG, "etag-1")).thenReturn(Promotion.SOURCE_CHANGED);
        when(files.finishPromotion(any(), any(), any(), any())).thenReturn(Optional.empty());

        outcomes.handle(result(ScanResult.NO_THREATS_FOUND, null));

        verify(files).finishPromotion(ID, FileStatus.REJECTED, NOW, "CHANGED_AFTER_SCAN");
    }

    @Test
    void eventsForOtherObjectsOrForgottenFilesAreUnknown() {
        assertThat(outcomes.handle(new ScanEvent.ObjectCreated("another-bucket", KEY, 1, "e", LANDED)))
                .isEqualTo(ScanOutcomes.Outcome.UNKNOWN_FILE);
        assertThat(outcomes.handle(new ScanEvent.ObjectCreated(BUCKET, "uploads/readme.txt", 1, "e", LANDED)))
                .isEqualTo(ScanOutcomes.Outcome.UNKNOWN_FILE);
        when(files.recordScan(any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        assertThat(outcomes.handle(result(ScanResult.FAILED, null))).isEqualTo(ScanOutcomes.Outcome.UNKNOWN_FILE);
        verify(files, never()).markSubmitted(any(), any());
    }

    @Test
    void aPromotionCanBeFinishedFromTheRecordAloneWithTheVersionScanned() {
        when(storage.readSignature(ID)).thenReturn(Optional.of(PNG));
        when(storage.promote(ID, FileType.PNG, "etag-1")).thenReturn(Promotion.COPIED);
        when(files.finishPromotion(any(), any(), any(), any())).thenReturn(Optional.empty());

        outcomes.resumePromotion(file(FileStatus.PROMOTING, ScanResult.NO_THREATS_FOUND, null));

        verify(storage).promote(ID, FileType.PNG, "etag-1");
        verify(files).finishPromotion(ID, FileStatus.AVAILABLE, NOW, null);
    }

    @Test
    void resumingAFinishedFileDoesNothing() {
        outcomes.resumePromotion(file(FileStatus.AVAILABLE, ScanResult.NO_THREATS_FOUND, NOW));

        verify(storage, never()).readSignature(any());
    }

    @Test
    void aResultRecoveredFromTheTagCarriesOnLikeTheEvent() {
        when(files.recordScan(ID, ScanResult.NO_THREATS_FOUND, NOW, ScanOutcomes.RECOVERED_FROM_TAG, null))
                .thenReturn(Optional.of(new Update(true, new FileRecord(ID, "a.png", FileType.PNG, 8, UploadMethod.STREAM,
                        FileStatus.PROMOTING, LANDED, LANDED, NOW, null, ScanResult.NO_THREATS_FOUND,
                        ScanOutcomes.RECOVERED_FROM_TAG, null))));
        when(storage.readSignature(ID)).thenReturn(Optional.of(PNG));
        when(storage.promote(ID, FileType.PNG, null)).thenReturn(Promotion.COPIED);
        when(files.finishPromotion(any(), any(), any(), any())).thenReturn(Optional.empty());

        outcomes.applyRecoveredResult(ID, ScanResult.NO_THREATS_FOUND, NOW);

        // quarantine objects can't be replaced, so without the scanned ETag the copy is unconditional
        verify(storage).promote(ID, FileType.PNG, null);
        verify(files).finishPromotion(ID, FileStatus.AVAILABLE, NOW, null);
    }

    @Test
    void anUnscannableFileIsNeverPromoted() {
        when(files.recordScan(ID, ScanResult.UNSUPPORTED, SCANNED, "PASSWORD_PROTECTED", "etag-1"))
                .thenReturn(Optional.of(new Update(true, file(FileStatus.NOT_SCANNED, ScanResult.UNSUPPORTED, null))));

        outcomes.handle(result(ScanResult.UNSUPPORTED, "PASSWORD_PROTECTED"));

        verify(storage, never()).promote(any(), any(), isNull());
        verify(storage, never()).readSignature(any());
        assertThat(Duration.between(LANDED, SCANNED)).isEqualTo(Duration.ofSeconds(20));
    }
}
