package com.crisinis.fileuploadservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import com.crisinis.fileuploadservice.model.FileRecord;
import com.crisinis.fileuploadservice.model.FileStatus;
import com.crisinis.fileuploadservice.model.FileType;
import com.crisinis.fileuploadservice.model.IdempotencyKey;
import com.crisinis.fileuploadservice.model.UploadMethod;
import com.crisinis.fileuploadservice.persistence.FileRepository;
import com.crisinis.fileuploadservice.persistence.FileRepository.Update;
import com.crisinis.fileuploadservice.service.UploadRejectedException.Reason;
import com.crisinis.fileuploadservice.storage.FileStorage;
import com.crisinis.fileuploadservice.storage.ObjectExistsException;
import com.crisinis.fileuploadservice.storage.PresignedUpload;
import com.crisinis.fileuploadservice.support.TestProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/** Retries with the same Idempotency-Key: the race between a lost answer and the caller's retry. */
class IdempotentUploadTests {

    static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
    static final byte[] PNG = UploadServiceTests.PNG;
    static final IdempotencyKey KEY = new IdempotencyKey("7c9e6679-7425-40de-944b-e07fc1f90ae7");
    static final String ID = KEY.fileId();

    private final FileRepository files = mock(FileRepository.class);
    private final FileStorage storage = mock(FileStorage.class);
    private final UploadService uploads = new UploadService(files, storage, TestProperties.defaults(),
            Clock.fixed(NOW, ZoneOffset.UTC), new SimpleMeterRegistry());

    static FileRecord file(UploadMethod method, FileStatus status, String name, long size) {
        return new FileRecord(ID, name, FileType.PNG, size, method, status, NOW.minusSeconds(60),
                status == FileStatus.AWAITING_UPLOAD ? null : NOW.minusSeconds(50), null, null, null, null, null);
    }

    // presigned style

    @Test
    void aRetriedPresignedRequestGetsTheSameFileAndAFreshUrl() {
        when(files.createIfAbsent(any())).thenReturn(new Update(false,
                file(UploadMethod.PRESIGNED_URL, FileStatus.AWAITING_UPLOAD, "a.png", 176)));
        when(storage.presignUpload(eq(ID), any(), anyLong(), any()))
                .thenReturn(new PresignedUpload(URI.create("https://s3.example/x"), java.util.Map.of(), NOW));

        UploadResult result = uploads.requestUpload("a.png", "image/png", 176, Optional.of(KEY));

        assertThat(result.file().id()).isEqualTo(ID);
        assertThat(result.replayed()).isTrue();
        assertThat(result.upload()).isNotNull();
    }

    @Test
    void aRetriedPresignedRequestAfterTheBytesLandedGetsNoUrl() {
        when(files.createIfAbsent(any())).thenReturn(new Update(false,
                file(UploadMethod.PRESIGNED_URL, FileStatus.SCANNING, "a.png", 176)));

        UploadResult result = uploads.requestUpload("a.png", "image/png", 176, Optional.of(KEY));

        assertThat(result.replayed()).isTrue();
        assertThat(result.upload()).isNull();
        verify(storage, never()).presignUpload(any(), any(), anyLong(), any());
    }

    @Test
    void aKeyReusedForAnotherFileIsRefused() {
        when(files.createIfAbsent(any())).thenReturn(new Update(false,
                file(UploadMethod.PRESIGNED_URL, FileStatus.AWAITING_UPLOAD, "a.png", 176)));

        assertThatThrownBy(() -> uploads.requestUpload("a.png", "image/png", 999, Optional.of(KEY)))
                .isInstanceOfSatisfying(UploadRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.IDEMPOTENCY_KEY_REUSED));
        assertThatThrownBy(() -> uploads.requestUpload("b.png", "image/png", 176, Optional.of(KEY)))
                .isInstanceOf(UploadRejectedException.class);
        when(files.find(ID)).thenReturn(Optional.of(file(UploadMethod.PRESIGNED_URL, FileStatus.SCANNING, "a.png", PNG.length)));
        assertThatThrownBy(() -> uploads.stream("a.png", "image/png", PNG.length, new ByteArrayInputStream(PNG), Optional.of(KEY)))
                .as("same file, other style").isInstanceOf(UploadRejectedException.class);
    }

    // stream style

    @Test
    void aRetryOfAStreamThatLandedIsAnsweredWithoutReadingOrStoringTheBodyAgain() {
        when(files.find(ID)).thenReturn(Optional.of(file(UploadMethod.STREAM, FileStatus.AVAILABLE, "a.png", PNG.length)));
        InputStream body = mock(InputStream.class);

        UploadResult result = uploads.stream("a.png", "image/png", PNG.length, body, Optional.of(KEY));

        assertThat(result.replayed()).isTrue();
        assertThat(result.file().status()).isEqualTo(FileStatus.AVAILABLE);
        verify(storage, never()).store(any(), any(), anyLong(), any());
        org.mockito.Mockito.verifyNoInteractions(body);
    }

    @Test
    void aRetryAfterTheFirstAttemptDiedBeforeStoringStoresTheFileUnderTheSameId() {
        // the first attempt created the record, then died mid-stream: nothing in S3
        when(files.createIfAbsent(any())).thenReturn(new Update(false,
                file(UploadMethod.STREAM, FileStatus.AWAITING_UPLOAD, "a.png", PNG.length)));
        when(files.markSubmitted(eq(ID), any())).thenReturn(Optional.of(new Update(true,
                file(UploadMethod.STREAM, FileStatus.SCANNING, "a.png", PNG.length))));

        UploadResult result = uploads.stream("a.png", "image/png", PNG.length, new ByteArrayInputStream(PNG), Optional.of(KEY));

        verify(storage).store(eq(ID), eq(FileType.PNG), eq((long) PNG.length), any());
        assertThat(result.replayed()).as("this attempt stored it").isFalse();
        assertThat(result.file().status()).isEqualTo(FileStatus.SCANNING);
    }

    @Test
    void aRetryAfterTheFirstAttemptStoredTheFileButLostItsAnswerIsNotStoredTwice() {
        // S3 has the object (If-None-Match refuses a second one); the record still says AWAITING_UPLOAD
        when(files.createIfAbsent(any())).thenReturn(new Update(false,
                file(UploadMethod.STREAM, FileStatus.AWAITING_UPLOAD, "a.png", PNG.length)));
        doThrow(new ObjectExistsException(ID)).when(storage).store(any(), any(), anyLong(), any());
        when(files.markSubmitted(eq(ID), any())).thenReturn(Optional.of(new Update(true,
                file(UploadMethod.STREAM, FileStatus.SCANNING, "a.png", PNG.length))));

        UploadResult result = uploads.stream("a.png", "image/png", PNG.length, new ByteArrayInputStream(PNG), Optional.of(KEY));

        assertThat(result.replayed()).isTrue();
        assertThat(result.file().status()).isEqualTo(FileStatus.SCANNING);
    }

    @Test
    void twoConcurrentAttemptsWithOneKeyStoreOneFile() {
        // both see AWAITING_UPLOAD; S3 keeps the first PUT and refuses the second (412)
        when(files.createIfAbsent(any())).thenReturn(
                new Update(true, file(UploadMethod.STREAM, FileStatus.AWAITING_UPLOAD, "a.png", PNG.length)),
                new Update(false, file(UploadMethod.STREAM, FileStatus.AWAITING_UPLOAD, "a.png", PNG.length)));
        doAnswer(call -> null).doThrow(new ObjectExistsException(ID)).when(storage).store(any(), any(), anyLong(), any());
        when(files.markSubmitted(eq(ID), any())).thenReturn(Optional.empty());

        UploadResult first = uploads.stream("a.png", "image/png", PNG.length, new ByteArrayInputStream(PNG), Optional.of(KEY));
        UploadResult second = uploads.stream("a.png", "image/png", PNG.length, new ByteArrayInputStream(PNG), Optional.of(KEY));

        assertThat(first.file().id()).isEqualTo(second.file().id()).isEqualTo(ID);
        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
    }

    @Test
    void withoutAKeyAnExistingObjectIsAConflict() {
        when(files.createIfAbsent(any())).thenAnswer(call -> new Update(true, call.getArgument(0)));
        doThrow(new ObjectExistsException("x")).when(storage).store(any(), any(), anyLong(), any());

        assertThatThrownBy(() -> uploads.stream("a.png", "image/png", PNG.length, new ByteArrayInputStream(PNG), Optional.empty()))
                .isInstanceOf(ObjectExistsException.class);
    }
}
