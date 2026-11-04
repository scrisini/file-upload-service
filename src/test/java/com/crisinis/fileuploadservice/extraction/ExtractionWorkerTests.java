package com.crisinis.fileuploadservice.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

import com.crisinis.fileuploadservice.model.ExtractionKind;
import com.crisinis.fileuploadservice.model.FileRecord;
import com.crisinis.fileuploadservice.model.FileStatus;
import com.crisinis.fileuploadservice.model.FileType;
import com.crisinis.fileuploadservice.model.ScanResult;
import com.crisinis.fileuploadservice.model.UploadMethod;
import com.crisinis.fileuploadservice.persistence.FileRepository;
import com.crisinis.fileuploadservice.persistence.FileRepository.Claim;
import com.crisinis.fileuploadservice.persistence.FileRepository.Stage;
import com.crisinis.fileuploadservice.storage.FileStorage;
import com.crisinis.fileuploadservice.support.SamplePolicy;
import com.crisinis.fileuploadservice.support.TestProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

/** DynamoDB, S3 and Bedrock are all mocked. */
class ExtractionWorkerTests {

    static final Instant NOW = Instant.parse("2026-11-04T10:00:00Z");
    static final String ID = "0b8e2d4a-55a3-4d0e-9f53-3e1b5f0a7c11";
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G'};

    private final FileRepository files = mock(FileRepository.class);
    private final FileStorage storage = mock(FileStorage.class);
    private final BedrockPolicyModel model = mock(BedrockPolicyModel.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final JsonMapper json = JsonMapper.builder().build();
    private final ExtractionWorker worker = new ExtractionWorker(files, storage, model, new PolicyParser(json), json,
            TestProperties.of(Map.of("file-upload-service.extraction.model-id", "model-1")),
            Clock.fixed(NOW, ZoneOffset.UTC), registry);

    static Claim claim(int attempt) {
        return new Claim(new FileRecord(ID, "policy.png", FileType.PNG, 4, UploadMethod.STREAM, FileStatus.AVAILABLE,
                NOW.minusSeconds(60), NOW.minusSeconds(50), NOW.minusSeconds(40), NOW.minusSeconds(30),
                ScanResult.NO_THREATS_FOUND, null, "etag", ExtractionKind.INSURANCE_POLICY), attempt);
    }

    @Test
    void readsTheFileAsksTheModelAndStoresThePolicy() {
        when(storage.readClean(ID, BedrockPolicyModel.MAX_DOCUMENT_BYTES)).thenReturn(Optional.of(PNG));
        when(model.read(FileType.PNG, PNG)).thenReturn(new BedrockPolicyModel.Answer(SamplePolicy.JSON, 1500, 400));
        when(model.modelId()).thenReturn("model-1");
        when(files.completeExtraction(eq(ID), eq("model-1"), anyString(), eq(NOW))).thenReturn(true);

        worker.extract(claim(1));

        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
        verify(files).completeExtraction(eq(ID), eq("model-1"), stored.capture(), eq(NOW));
        assertThat(stored.getValue()).contains("\"policyNumber\"").contains("HPC-0042-7781").contains("\"amountMinor\":184260");
        assertThat(registry.get("file-upload-service.extraction.tokens").tag("direction", "input").counter().count()).isEqualTo(1500);
        assertThat(registry.get("file-upload-service.extraction.time-to-complete").timer().count()).isEqualTo(1);
        assertThat(registry.get("file-upload-service.extraction.model.calls").tag("outcome", "success").timer().count()).isEqualTo(1);
    }

    @Test
    void aTemporaryFailureWaitsLongerEachAttempt() {
        when(storage.readClean(any(), anyLong())).thenReturn(Optional.of(PNG));
        when(model.read(any(), any())).thenThrow(new ExtractionException(true, "MODEL_THROTTLED", "slow down", null));

        worker.extract(claim(2));

        verify(files).deferExtraction(ID, NOW.plus(Duration.ofSeconds(60)), "MODEL_THROTTLED");
        verify(files, never()).failExtraction(any(), any(), any());
    }

    @Test
    void theLastAttemptFailsTheExtraction() {
        when(storage.readClean(any(), anyLong())).thenReturn(Optional.of(PNG));
        when(model.read(any(), any())).thenThrow(new ExtractionException(true, "MODEL_TIMEOUT", "timeout", null));
        when(files.failExtraction(any(), any(), any())).thenReturn(true);

        worker.extract(claim(3));

        verify(files).failExtraction(ID, "MODEL_TIMEOUT", NOW);
        verify(files, never()).deferExtraction(any(), any(), any());
    }

    @Test
    void aPermanentFailureFailsAtOnce() {
        when(storage.readClean(any(), anyLong())).thenReturn(Optional.of(PNG));
        when(model.read(any(), any())).thenThrow(new ExtractionException(false, "MODEL_ACCESS_DENIED", "denied", null));

        worker.extract(claim(1));

        verify(files).failExtraction(ID, "MODEL_ACCESS_DENIED", NOW);
    }

    @Test
    void aGarbledAnswerIsRetried() {
        when(storage.readClean(any(), anyLong())).thenReturn(Optional.of(PNG));
        when(model.read(any(), any())).thenReturn(new BedrockPolicyModel.Answer("Sorry, I can't.", 10, 5));

        worker.extract(claim(1));

        verify(files).deferExtraction(ID, NOW.plus(Duration.ofSeconds(30)), "ANSWER_NOT_JSON");
    }

    @Test
    void aFileMissingFromTheCleanBucketFails() {
        when(storage.readClean(any(), anyLong())).thenReturn(Optional.empty());

        worker.extract(claim(1));

        verify(files).failExtraction(ID, "FILE_MISSING", NOW);
        verify(model, never()).read(any(), any());
    }

    @Test
    void stopsWhenDeadWorkersUsedUpTheAttempts() {
        // three workers claimed it and died without a word: the fourth claim doesn't call the model again
        worker.extract(claim(4));

        verify(files).failExtraction(ID, "ATTEMPTS_EXHAUSTED", NOW);
        verify(model, never()).read(any(), any());
        verify(storage, never()).readClean(any(), anyLong());
    }

    @Test
    void claimsThePendingExtractionNoOtherWorkerHolds() {
        when(files.inStageSince(eq(Stage.EXTRACTING), any(), anyInt())).thenReturn(List.of("held", ID));
        when(files.claimExtraction(eq("held"), any(), any())).thenReturn(Optional.empty());
        when(files.claimExtraction(eq(ID), eq(NOW), eq(Duration.ofMinutes(3)))).thenReturn(Optional.of(claim(1)));

        assertThat(worker.claimNext()).hasValueSatisfying(c -> assertThat(c.file().id()).isEqualTo(ID));
    }

    @Test
    void withoutAModelItDoesntStart() {
        ExtractionWorker off = new ExtractionWorker(files, storage, model, new PolicyParser(json), json,
                TestProperties.defaults(), Clock.fixed(NOW, ZoneOffset.UTC), new SimpleMeterRegistry());

        off.start();

        assertThat(off.isRunning()).isFalse();
    }
}
