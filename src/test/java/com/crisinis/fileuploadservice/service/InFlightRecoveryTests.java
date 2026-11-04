package com.crisinis.fileuploadservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.crisinis.fileuploadservice.model.FileRecord;
import com.crisinis.fileuploadservice.model.FileStatus;
import com.crisinis.fileuploadservice.model.FileType;
import com.crisinis.fileuploadservice.model.ScanResult;
import com.crisinis.fileuploadservice.model.UploadMethod;
import com.crisinis.fileuploadservice.persistence.FileRepository;
import com.crisinis.fileuploadservice.storage.FileStorage;
import com.crisinis.fileuploadservice.support.TestProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InFlightRecoveryTests {

    static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
    static final String ID = "0b8e2d4a-55a3-4d0e-9f53-3e1b5f0a7c11";

    private final FileRepository files = mock(FileRepository.class);
    private final FileStorage storage = mock(FileStorage.class);
    private final ScanOutcomes outcomes = mock(ScanOutcomes.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final InFlightRecovery recovery = new InFlightRecovery(files, storage, outcomes,
            TestProperties.of(Map.of("file-upload-service.scan.queue-url", "http://localhost:9324/q")),
            Clock.fixed(NOW, ZoneOffset.UTC), registry);

    static FileRecord promoting() {
        return new FileRecord(ID, "a.png", FileType.PNG, 8, UploadMethod.STREAM, FileStatus.PROMOTING, NOW.minusSeconds(600),
                NOW.minusSeconds(590), NOW.minusSeconds(580), null, ScanResult.NO_THREATS_FOUND, null, "etag-1", null);
    }

    @BeforeEach
    void nothingStuckByDefault() {
        when(files.inStageSince(any(), any(), anyInt())).thenReturn(List.of());
    }

    @Test
    void resumesAPromotionWhoseProcessDied() {
        when(files.inStageSince(FileRepository.Stage.PROMOTING, NOW.minus(Duration.ofMinutes(2)), InFlightRecovery.BATCH))
                .thenReturn(List.of(ID));
        when(files.find(ID)).thenReturn(Optional.of(promoting()));

        recovery.recover();

        verify(outcomes).resumePromotion(promoting());
        assertThat(registry.get("file-upload-service.recovery").tag("action", "promotion_resumed").counter().count()).isEqualTo(1);
    }

    @Test
    void leavesAPromotionThatFinishedMeanwhile() {
        when(files.inStageSince(FileRepository.Stage.PROMOTING, NOW.minus(Duration.ofMinutes(2)), InFlightRecovery.BATCH))
                .thenReturn(List.of(ID));
        when(files.find(ID)).thenReturn(Optional.empty());

        recovery.recover();

        verify(outcomes, never()).resumePromotion(any());
    }

    @Test
    void appliesAResultGuardDutyTaggedWhenItsEventNeverArrived() {
        when(files.inStageSince(FileRepository.Stage.SCANNING, NOW.minus(Duration.ofMinutes(5)), InFlightRecovery.BATCH))
                .thenReturn(List.of(ID, "still-scanning"));
        when(storage.scanStatusTag(ID)).thenReturn(Optional.of("THREATS_FOUND"));
        when(storage.scanStatusTag("still-scanning")).thenReturn(Optional.empty());

        recovery.recover();

        verify(outcomes).applyRecoveredResult(ID, ScanResult.THREATS_FOUND, NOW);
        verify(outcomes, never()).applyRecoveredResult(org.mockito.ArgumentMatchers.eq("still-scanning"), any(), any());
        assertThat(registry.get("file-upload-service.recovery").tag("action", "result_from_tag").counter().count()).isEqualTo(1);
    }

    @Test
    void oneFilesFailureDoesntStopTheOthers() {
        when(files.inStageSince(FileRepository.Stage.SCANNING, NOW.minus(Duration.ofMinutes(5)), InFlightRecovery.BATCH))
                .thenReturn(List.of("broken", ID));
        when(storage.scanStatusTag("broken"))
                .thenThrow(new DependencyUnavailableException("s3", "GetObjectTagging", new RuntimeException("timeout")));
        when(storage.scanStatusTag(ID)).thenReturn(Optional.of("NO_THREATS_FOUND"));

        recovery.recover();

        verify(outcomes).applyRecoveredResult(ID, ScanResult.NO_THREATS_FOUND, NOW);
    }

    @Test
    void aDynamoDbOutageSkipsTheRun() {
        when(files.inStageSince(any(), any(), anyInt()))
                .thenThrow(new DependencyUnavailableException("dynamodb", "inStageSince", new RuntimeException("timeout")));

        recovery.recover();

        verify(outcomes, never()).resumePromotion(any());
    }

    @Test
    void doesNothingWithoutScanning() {
        new InFlightRecovery(files, storage, outcomes, TestProperties.defaults(), Clock.fixed(NOW, ZoneOffset.UTC),
                new SimpleMeterRegistry()).recover();

        verify(files, never()).inStageSince(any(), any(), anyInt());
    }
}
