package com.crisinis.fileuploadservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.crisinis.fileuploadservice.model.FileRecord;
import com.crisinis.fileuploadservice.model.FileStatus;
import com.crisinis.fileuploadservice.model.FileType;
import com.crisinis.fileuploadservice.model.IdempotencyKey;
import com.crisinis.fileuploadservice.model.UploadMethod;
import com.crisinis.fileuploadservice.persistence.FileRepository;
import com.crisinis.fileuploadservice.service.UploadRejectedException.Reason;
import com.crisinis.fileuploadservice.storage.FileStorage;
import com.crisinis.fileuploadservice.storage.PresignedUpload;
import com.crisinis.fileuploadservice.support.TestProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class UploadServiceTests {

    static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13, 'I', 'H', 'D', 'R', 1, 2, 3};

    private final FileRepository files = mock(FileRepository.class);
    private final FileStorage storage = mock(FileStorage.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final UploadService uploads = service(Map.of());
    static final Optional<IdempotencyKey> NO_KEY = Optional.empty();

    @org.junit.jupiter.api.BeforeEach
    void recordsAreNew() {
        when(files.createIfAbsent(any())).thenAnswer(call -> new FileRepository.Update(true, call.getArgument(0)));
    }

    private UploadService service(Map<String, String> settings) {
        return new UploadService(files, storage, TestProperties.of(settings), Clock.fixed(NOW, ZoneOffset.UTC), registry);
    }

    @Test
    void issuesAPresignedUploadForAnAcceptedFile() {
        PresignedUpload presigned = new PresignedUpload(URI.create("https://s3.example/uploads/x"),
                Map.of("Content-Type", "image/png"), NOW.plus(Duration.ofMinutes(15)));
        when(storage.presignUpload(any(), eq(FileType.PNG), eq(1234L), eq(Duration.ofMinutes(15)))).thenReturn(presigned);

        UploadResult ticket = uploads.requestUpload(DeclaredFile.of("../holiday/beach.png", "image/png", 1234), NO_KEY);

        ArgumentCaptor<FileRecord> created = ArgumentCaptor.forClass(FileRecord.class);
        verify(files).createIfAbsent(created.capture());
        assertThat(created.getValue()).satisfies(file -> {
            assertThat(file.fileName()).isEqualTo("beach.png");
            assertThat(file.status()).isEqualTo(FileStatus.AWAITING_UPLOAD);
            assertThat(file.method()).isEqualTo(UploadMethod.PRESIGNED_URL);
            assertThat(file.createdAt()).isEqualTo(NOW);
        });
        assertThat(ticket.upload()).isSameAs(presigned);
        assertThat(registry.get("file-upload-service.uploads").tags("method", "presigned_url", "outcome", "accepted")
                .counter().count()).isEqualTo(1);
    }

    @Test
    void refusesTypesSizesAndNamesBeforeIssuingAnything() {
        assertRejected(() -> uploads.requestUpload(DeclaredFile.of("a.svg", "image/svg+xml", 10), NO_KEY), Reason.UNSUPPORTED_TYPE);
        assertRejected(() -> uploads.requestUpload(DeclaredFile.of("a.png", "image/png", 0), NO_KEY), Reason.INVALID);
        assertRejected(() -> uploads.requestUpload(DeclaredFile.of("a.png", "image/png", 25L * 1024 * 1024 + 1), NO_KEY), Reason.TOO_LARGE);
        assertRejected(() -> service(Map.of("file-upload-service.uploads.allowed-types", "PDF"))
                .requestUpload(DeclaredFile.of("a.png", "image/png", 10), NO_KEY), Reason.UNSUPPORTED_TYPE);

        verify(files, never()).createIfAbsent(any());
        verify(storage, never()).presignUpload(any(), any(), anyLong(), any());
    }

    @Test
    void anOptedInFileIsRecordedForExtraction() {
        UploadService withModel = service(Map.of("file-upload-service.extraction.model-id", "model-1"));
        when(files.markSubmitted(any(), any())).thenReturn(Optional.empty());

        withModel.stream(new DeclaredFile("policy.png", "image/png", PNG.length,
                com.crisinis.fileuploadservice.model.ExtractionKind.INSURANCE_POLICY), new ByteArrayInputStream(PNG), NO_KEY);

        ArgumentCaptor<FileRecord> created = ArgumentCaptor.forClass(FileRecord.class);
        verify(files).createIfAbsent(created.capture());
        assertThat(created.getValue().extraction()).isEqualTo(com.crisinis.fileuploadservice.model.ExtractionKind.INSURANCE_POLICY);
    }

    @Test
    void optingInIsRefusedWhenTheFileCantBeExtracted() {
        var policy = com.crisinis.fileuploadservice.model.ExtractionKind.INSURANCE_POLICY;
        UploadService withModel = service(Map.of("file-upload-service.extraction.model-id", "model-1"));

        assertRejected(() -> withModel.requestUpload(new DeclaredFile("p.heic", "image/heic", 1000, policy), NO_KEY),
                Reason.EXTRACTION_NOT_SUPPORTED);
        assertRejected(() -> withModel.requestUpload(new DeclaredFile("p.png", "image/png", 4_000_000, policy), NO_KEY),
                Reason.EXTRACTION_NOT_SUPPORTED);
        assertRejected(() -> withModel.requestUpload(new DeclaredFile("p.pdf", "application/pdf", 5_000_000, policy), NO_KEY),
                Reason.EXTRACTION_NOT_SUPPORTED);
        // no model configured: extraction is off
        assertRejected(() -> uploads.requestUpload(new DeclaredFile("p.png", "image/png", 1000, policy), NO_KEY),
                Reason.EXTRACTION_NOT_SUPPORTED);
        verify(files, never()).createIfAbsent(any());
    }

    @Test
    void streamsAMatchingFileAndMarksItSubmitted() throws IOException {
        when(files.markSubmitted(any(), eq(NOW))).thenAnswer(call -> Optional.of(new FileRepository.Update(true,
                new FileRecord(call.getArgument(0), "a.png", FileType.PNG, PNG.length, UploadMethod.STREAM,
                        FileStatus.SCANNING, NOW, NOW, null, null, null, null, null, null))));
        byte[][] stored = new byte[1][];
        doAnswer(call -> {
            stored[0] = call.<InputStream>getArgument(3).readAllBytes();
            return null;
        }).when(storage).store(any(), eq(FileType.PNG), eq((long) PNG.length), any());

        FileRecord file = uploads.stream(DeclaredFile.of("a.png", "image/png", PNG.length), new ByteArrayInputStream(PNG), NO_KEY).file();

        assertThat(file.status()).isEqualTo(FileStatus.SCANNING);
        assertThat(stored[0]).as("the signature read for checking is sent on too").isEqualTo(PNG);
        assertThat(registry.get("file-upload-service.uploads.bytes").tag("method", "stream").counter().count())
                .isEqualTo(PNG.length);
    }

    @Test
    void refusesAStreamWhoseContentIsNotTheDeclaredTypeBeforeStoringIt() {
        byte[] executable = "MZ\u0090\u0000 this is not a png at all".getBytes();

        assertThatThrownBy(() -> uploads.stream(DeclaredFile.of("a.png", "image/png", executable.length), new ByteArrayInputStream(executable), NO_KEY))
                .isInstanceOfSatisfying(UploadRejectedException.class, e -> {
                    assertThat(e.reason()).isEqualTo(Reason.CONTENT_MISMATCH);
                    assertThat(e.getMessage()).isEqualTo("the content is not image/png");
                });
        verify(files, never()).createIfAbsent(any());
        verify(storage, never()).store(any(), any(), anyLong(), any());
    }

    @Test
    void needsALengthAndKeepsToTheLimit() {
        assertRejected(() -> uploads.stream(DeclaredFile.of("a.png", "image/png", -1), InputStream.nullInputStream(), NO_KEY), Reason.LENGTH_REQUIRED);
        assertRejected(() -> uploads.stream(DeclaredFile.of("a.png", "image/png", 26L * 1024 * 1024), InputStream.nullInputStream(), NO_KEY),
                Reason.TOO_LARGE);
    }

    @Test
    void aBodyShorterThanItsLengthIsTheCallersFault() {
        doAnswer(call -> {
            call.<InputStream>getArgument(3).readAllBytes();
            throw new DependencyUnavailableException("s3", "PutObject", new IOException("stream ended"));
        }).when(storage).store(any(), any(), anyLong(), any());

        assertRejected(() -> uploads.stream(DeclaredFile.of("a.png", "image/png", 1000), new ByteArrayInputStream(PNG), NO_KEY), Reason.INCOMPLETE);
        // kept: a retry with the same key may be using it; DynamoDB's TTL removes it
    }

    @Test
    void anS3FailureKeepsTheRecordInCaseS3StoredTheFileAndOnlyItsAnswerWasLost() {
        doAnswer(call -> {
            call.<InputStream>getArgument(3).readAllBytes();
            throw new DependencyUnavailableException("s3", "PutObject", new IOException("read timed out"));
        }).when(storage).store(any(), any(), anyLong(), any());

        assertThatThrownBy(() -> uploads.stream(DeclaredFile.of("a.png", "image/png", PNG.length), new ByteArrayInputStream(PNG), NO_KEY))
                .isInstanceOf(DependencyUnavailableException.class);
        // nothing deletes it: its Object Created event (if it landed) finds it; DynamoDB's TTL removes it otherwise
    }

    @Test
    void aStoredFileIsAcceptedEvenIfItsStatusCantBeUpdated() {
        when(files.markSubmitted(any(), any()))
                .thenThrow(new DependencyUnavailableException("dynamodb", "markSubmitted", new IOException("timeout")));

        FileRecord file = uploads.stream(DeclaredFile.of("a.png", "image/png", PNG.length), new ByteArrayInputStream(PNG), NO_KEY).file();

        // the caller gets its id (202) rather than a 503 that makes it upload a second copy;
        // S3's Object Created event marks the file SCANNING
        assertThat(file.status()).isEqualTo(FileStatus.AWAITING_UPLOAD);
        verify(storage).store(eq(file.id()), eq(FileType.PNG), eq((long) PNG.length), any());
    }

    @Test
    void limitsConcurrentStreamsAndReleasesThem() throws Exception {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        UploadService one = new UploadService(files, storage,
                TestProperties.of(Map.of("file-upload-service.uploads.max-concurrent-streams", "1")),
                Clock.fixed(NOW, ZoneOffset.UTC), meters);
        CountDownLatch storing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> {
            storing.countDown();
            release.await(5, TimeUnit.SECONDS);
            return null;
        }).when(storage).store(any(), any(), anyLong(), any());
        when(files.markSubmitted(any(), any())).thenReturn(Optional.empty());

        Thread first = Thread.ofVirtual().start(() -> one.stream(DeclaredFile.of("a.png", "image/png", PNG.length), new ByteArrayInputStream(PNG), NO_KEY));
        assertThat(storing.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(meters.get("file-upload-service.uploads.streaming").gauge().value()).isEqualTo(1);

        assertThatThrownBy(() -> one.stream(DeclaredFile.of("b.png", "image/png", PNG.length), new ByteArrayInputStream(PNG), NO_KEY))
                .isInstanceOf(UploadCapacityExceededException.class);

        release.countDown();
        first.join(5000);
        assertThat(meters.get("file-upload-service.uploads.streaming").gauge().value()).isZero();
    }

    @Test
    void downloadsOnlyAvailableFiles() {
        String id = "0b8e2d4a-55a3-4d0e-9f53-3e1b5f0a7c11";
        when(files.find(id)).thenReturn(Optional.of(new FileRecord(id, "a.png", FileType.PNG, 1, UploadMethod.STREAM,
                FileStatus.INFECTED, NOW, NOW, NOW, null, null, null, null, null)));

        assertThatThrownBy(() -> uploads.downloadUrl(id)).isInstanceOf(FileNotAvailableException.class);
        assertThatThrownBy(() -> uploads.downloadUrl("6a4b8c5e-0000-4000-8000-000000000000"))
                .isInstanceOf(UnknownFileException.class);
    }

    @Test
    void keepsOnlyTheFileNamePart() {
        assertThat(UploadService.cleanName("C:\\Users\\me\\scan.pdf")).isEqualTo("scan.pdf");
        assertThat(UploadService.cleanName("a/b/c.png")).isEqualTo("c.png");
        assertThat(UploadService.cleanName("my scan  (1).png")).isEqualTo("my scan  (1).png");
        assertThat(UploadService.cleanName("  padded name.png ")).isEqualTo("padded name.png");
        assertThat(UploadService.cleanName("bad\u0000name\n.png")).isEqualTo("badname.png");
        assertThat(UploadService.cleanName("..")).isEqualTo("upload");
        assertThat(UploadService.cleanName(null)).isEqualTo("upload");
        assertThat(UploadService.cleanName("x".repeat(300))).hasSize(255);
    }

    private static void assertRejected(Runnable upload, Reason reason) {
        assertThatThrownBy(upload::run).isInstanceOfSatisfying(UploadRejectedException.class,
                e -> assertThat(e.reason()).isEqualTo(reason));
    }
}
