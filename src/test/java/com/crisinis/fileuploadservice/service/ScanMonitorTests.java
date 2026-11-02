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

import com.crisinis.fileuploadservice.model.ScanSummary;
import com.crisinis.fileuploadservice.persistence.FileRepository;
import com.crisinis.fileuploadservice.support.TestProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class ScanMonitorTests {

    static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");

    private final FileRepository files = mock(FileRepository.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private ScanMonitor monitor(Map<String, String> settings) {
        return new ScanMonitor(files, TestProperties.of(settings), Clock.fixed(NOW, ZoneOffset.UTC), registry);
    }

    private final ScanMonitor monitor = monitor(Map.of("file-upload-service.scan.queue-url", "http://localhost:9324/q"));

    @Test
    void countsScansInProgressAndReportsEachOverdueScanOnce() {
        when(files.countScanning()).thenReturn(7L);
        when(files.scanningSince(NOW.minus(Duration.ofMinutes(5)), 100)).thenReturn(List.of("a", "b"));
        when(files.claimOverdueReport("a", NOW)).thenReturn(true);
        when(files.claimOverdueReport("b", NOW)).thenReturn(false);  // another instance reported it

        monitor.refresh();

        assertThat(registry.get("file-upload-service.scan.in-progress").gauge().value()).isEqualTo(7);
        assertThat(registry.get("file-upload-service.scan.overdue").counter().count()).isEqualTo(1);
    }

    @Test
    void keepsTheLastCountWhenDynamoDbFails() {
        when(files.countScanning()).thenReturn(3L)
                .thenThrow(new DependencyUnavailableException("dynamodb", "countScanning", new RuntimeException("timeout")));

        monitor.refresh();
        monitor.refresh();

        assertThat(registry.get("file-upload-service.scan.in-progress").gauge().value()).isEqualTo(3);
    }

    @Test
    void doesNothingWithoutScanning() {
        monitor(Map.of()).refresh();

        verify(files, never()).countScanning();
        verify(files, never()).scanningSince(any(), anyInt());
    }

    @Test
    void summarisesFromTheTableNow() {
        when(files.countScanning()).thenReturn(2L);
        when(files.oldestScanning()).thenReturn(Optional.of(NOW.minusSeconds(40)));

        assertThat(monitor.summary()).isEqualTo(new ScanSummary(2, NOW.minusSeconds(40), Duration.ofMinutes(5)));
    }
}
