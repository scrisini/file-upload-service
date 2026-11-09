package com.crisinis.fileuploadservice.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;

import com.crisinis.fileuploadservice.config.FileUploadServiceProperties;
import com.crisinis.fileuploadservice.extraction.BedrockPolicyModel;
import com.crisinis.fileuploadservice.logging.LogEvent;
import com.crisinis.fileuploadservice.model.FileRecord;
import com.crisinis.fileuploadservice.model.FileStatus;
import com.crisinis.fileuploadservice.model.FileType;
import com.crisinis.fileuploadservice.model.IdempotencyKey;
import com.crisinis.fileuploadservice.model.UploadMethod;
import com.crisinis.fileuploadservice.persistence.FileRepository;
import com.crisinis.fileuploadservice.service.UploadRejectedException.Reason;
import com.crisinis.fileuploadservice.storage.FileStorage;
import com.crisinis.fileuploadservice.storage.ObjectExistsException;
import com.crisinis.fileuploadservice.storage.PresignedUpload;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.annotation.Observed;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Accepts files in two styles, which end the same way (an object in quarantine that GuardDuty
 * scans) and are compared in README "Upload styles":
 *
 * <ul>
 *   <li>{@link #requestUpload}: the caller gets a presigned URL and PUTs the bytes straight to S3;</li>
 *   <li>{@link #stream}: the caller sends the bytes here, and they're streamed on to S3 as they
 *       arrive. Nothing is written to disk, and each upload holds only the SDK's small buffer.</li>
 * </ul>
 */
@Slf4j
@Service
public class UploadService {

    /** How long a download link works. */
    static final Duration DOWNLOAD_TTL = Duration.ofMinutes(5);
    private static final int MAX_FILE_NAME = 255;

    private final FileRepository files;
    private final FileStorage storage;
    private final FileUploadServiceProperties.Uploads uploads;
    private final Clock clock;
    private final Semaphore streams;
    private final Map<UploadMethod, Counter> accepted = new EnumMap<>(UploadMethod.class);
    private final Map<UploadMethod, Counter> rejected = new EnumMap<>(UploadMethod.class);
    private final Counter streamedBytes;
    private final boolean extractionEnabled;

    public UploadService(FileRepository files, FileStorage storage, FileUploadServiceProperties properties,
                         Clock clock, MeterRegistry registry) {
        this.files = files;
        this.storage = storage;
        this.uploads = properties.uploads();
        this.clock = clock;
        this.streams = new Semaphore(uploads.maxConcurrentStreams());
        this.extractionEnabled = properties.extraction().enabled();
        for (UploadMethod method : UploadMethod.values()) {
            accepted.put(method, uploadCounter(registry, method, "accepted"));
            rejected.put(method, uploadCounter(registry, method, "rejected"));
        }
        // the same meter ScanOutcomes counts presigned uploads in: a streamed upload is submitted here
        this.streamedBytes = Counter.builder("file-upload-service.uploads.bytes").baseUnit("bytes")
                .description("Bytes landed in quarantine").tag("method", "stream").register(registry);
        Gauge.builder("file-upload-service.uploads.streaming", streams,
                        s -> uploads.maxConcurrentStreams() - s.availablePermits())
                .description("Streamed uploads in progress on this instance").register(registry);
    }

    /**
     * Presigned style: records the file and returns a URL that accepts exactly this file. With an
     * idempotency key a retry gets the same file: a fresh URL while its bytes haven't landed, and
     * no URL once they have.
     */
    @Observed(name = "file-upload-service.upload.presigned", contextualName = "upload-service#presigned",
            lowCardinalityKeyValues = {"method", "presigned"})
    public UploadResult requestUpload(DeclaredFile declared, Optional<IdempotencyKey> key) {
        FileType type = accept(UploadMethod.PRESIGNED_URL, declared);
        long size = declared.size();
        FileRecord requested = FileRecord.awaitingUpload(idFor(key), cleanName(declared.fileName()), type, size,
                UploadMethod.PRESIGNED_URL, clock.instant(), declared.extraction());
        FileRepository.Update created = files.createIfAbsent(requested);
        FileRecord file = sameRequest(created, requested);
        if (file.status() != FileStatus.AWAITING_UPLOAD) {
            return replayed(file, null);
        }
        var upload = storage.presignUpload(file.id(), type, size, uploads.urlTtl());
        if (!created.changed()) {
            return replayed(file, upload);
        }
        accepted.get(UploadMethod.PRESIGNED_URL).increment();
        LogEvent.UPLOAD_URL_ISSUED.at(log).with("file_id", file.id()).with("file_type", type)
                .with("size", size).with("extraction", file.extraction()).with("expires_at", upload.expiresAt()).log();
        return new UploadResult(file, upload, false);
    }

    /**
     * Stream style: checks the declared type, the length and the content's signature, then streams
     * the body to quarantine. Nothing is stored if a check fails. With an idempotency key a retry of
     * a file that already landed is answered with that file, without reading the body again.
     *
     * @param declared the file; its size is the request's Content-Length, -1 when it has none (chunked)
     */
    @Observed(name = "file-upload-service.upload.stream", contextualName = "upload-service#stream",
            lowCardinalityKeyValues = {"method", "stream"})
    public UploadResult stream(DeclaredFile declared, InputStream body, Optional<IdempotencyKey> key) {
        if (declared.size() < 0) {
            throw reject(UploadMethod.STREAM, Reason.LENGTH_REQUIRED, "send the file with a Content-Length header");
        }
        FileType type = accept(UploadMethod.STREAM, declared);
        FileRecord requested = FileRecord.awaitingUpload(idFor(key), cleanName(declared.fileName()), type,
                declared.size(), UploadMethod.STREAM, clock.instant(), declared.extraction());
        Optional<FileRecord> landed = key.flatMap(k -> files.find(requested.id()))
                .filter(existing -> existing.status() != FileStatus.AWAITING_UPLOAD);
        if (landed.isPresent()) {
            return replayed(sameRequest(new FileRepository.Update(false, landed.get()), requested), null);
        }
        if (!streams.tryAcquire()) {
            throw new UploadCapacityExceededException(uploads.maxConcurrentStreams());
        }
        try {
            return streamAccepted(requested, body, key.isPresent());
        } finally {
            streams.release();
        }
    }

    /** The file's record. */
    public FileRecord find(String id) {
        return files.find(id).orElseThrow(() -> new UnknownFileException(id));
    }

    /** A short-lived link to download the file, once it's AVAILABLE. */
    public URI downloadUrl(String id) {
        FileRecord file = find(id);
        if (file.status() != FileStatus.AVAILABLE) {
            throw new FileNotAvailableException(id, file.status());
        }
        return storage.presignDownload(id, file.fileName(), DOWNLOAD_TTL);
    }

    private UploadResult streamAccepted(FileRecord requested, InputStream body, boolean keyed) {
        FileType type = requested.type();
        long size = requested.size();
        byte[] signature = readSignature(body, size);
        if (!type.matches(signature)) {
            throw reject(UploadMethod.STREAM, Reason.CONTENT_MISMATCH,
                    "the content is not " + type.mediaType() + FileType.detect(signature)
                            .map(actual -> " (it looks like " + actual.mediaType() + ")").orElse(""));
        }
        FileRecord file = sameRequest(files.createIfAbsent(requested), requested);
        if (file.status() != FileStatus.AWAITING_UPLOAD) {
            // a concurrent attempt with the same key landed it meanwhile
            return replayed(file, null);
        }
        ExactLengthInputStream rest = new ExactLengthInputStream(
                new SequenceInputStream(new ByteArrayInputStream(signature), body), size);
        try {
            storage.store(file.id(), type, size, rest);
        } catch (ObjectExistsException e) {
            if (!keyed) {
                throw e;
            }
            // an earlier attempt with this key stored it, and died or lost its answer before saying so
            return replayed(submitted(file), null);
        } catch (DependencyUnavailableException e) {
            // The record is kept either way, never deleted: S3 may have stored the file and only the
            // answer been lost (its event will find the record), and a retry with the same key may be
            // using it. If nothing landed, DynamoDB's TTL removes it.
            if (rest.endedEarly()) {
                throw reject(UploadMethod.STREAM, Reason.INCOMPLETE,
                        "the body ended before the " + size + " bytes in Content-Length");
            }
            throw e;
        }
        accepted.get(UploadMethod.STREAM).increment();
        LogEvent.UPLOAD_STREAMED.at(log).with("file_id", file.id()).with("file_type", type).with("size", size)
                .with("extraction", file.extraction()).log();
        return new UploadResult(submitted(file), null, false);
    }

    /**
     * Marks a stored file SCANNING. The file is safe in quarantine whatever happens here: if
     * DynamoDB fails, S3's "Object Created" event marks it instead, so the caller still gets 202
     * and its id rather than a 503 that would make it upload a second copy.
     */
    private FileRecord submitted(FileRecord file) {
        try {
            var update = files.markSubmitted(file.id(), clock.instant());
            if (update.map(FileRepository.Update::changed).orElse(false)) {
                streamedBytes.increment(file.size());
            }
            return update.map(FileRepository.Update::file).orElse(file);
        } catch (DependencyUnavailableException e) {
            LogEvent.UPLOAD_STORED_UNRECORDED.at(log).with("file_id", file.id()).cause(e).log();
            return file;
        }
    }

    /** A new random id, or the id an idempotency key stands for. */
    private static String idFor(Optional<IdempotencyKey> key) {
        return key.map(IdempotencyKey::fileId).orElseGet(() -> UUID.randomUUID().toString());
    }

    /**
     * The file a key already stands for must be the file now described: a key reused for another
     * file is refused (422) rather than answered with the wrong one.
     */
    private FileRecord sameRequest(FileRepository.Update created, FileRecord requested) {
        FileRecord file = created.file();
        if (!created.changed() && !(file.fileName().equals(requested.fileName()) && file.type() == requested.type()
                && file.size() == requested.size() && file.method() == requested.method()
                && file.extraction() == requested.extraction())) {
            throw reject(requested.method(), Reason.IDEMPOTENCY_KEY_REUSED,
                    "this Idempotency-Key was used for another file (" + file.fileName() + ", " + file.type().mediaType()
                            + ", " + file.size() + " bytes, " + file.method()
                            + (file.extraction() == null ? "" : ", extract " + file.extraction())
                            + "); use a new key for a new file");
        }
        return file;
    }

    /** The answer to a retry: the file the key already stands for. */
    private UploadResult replayed(FileRecord file, PresignedUpload upload) {
        LogEvent.UPLOAD_REPLAYED.at(log).with("file_id", file.id()).with("method", file.method())
                .with("status", file.status()).log();
        return new UploadResult(file, upload, true);
    }

    /** The first bytes of the body, to check its type before anything is stored. */
    private byte[] readSignature(InputStream body, long size) {
        try {
            return body.readNBytes((int) Math.min(size, FileType.SIGNATURE_BYTES));
        } catch (IOException e) {
            throw reject(UploadMethod.STREAM, Reason.INCOMPLETE, "the connection failed while reading the body");
        }
    }

    /** The type for this declared file, or a rejection: its type, its size, and whether it can be extracted. */
    private FileType accept(UploadMethod method, DeclaredFile declared) {
        String mediaType = declared.mediaType();
        long size = declared.size();
        FileType type = FileType.fromMediaType(mediaType)
                .filter(uploads.allowedTypes()::contains)
                .orElseThrow(() -> reject(method, Reason.UNSUPPORTED_TYPE,
                        "content type '" + mediaType + "' is not accepted; send one of " + acceptedTypes()));
        if (size < 1) {
            throw reject(method, Reason.INVALID, "size must be at least 1 byte, was " + size);
        }
        if (size > uploads.maxSize().toBytes()) {
            throw reject(method, Reason.TOO_LARGE,
                    "the file is " + size + " bytes; the limit is " + uploads.maxSize().toBytes());
        }
        if (declared.extraction() != null) {
            acceptExtraction(method, type, size);
        }
        return type;
    }

    /** Opting in is refused now, rather than failing later, when extraction can't happen for this file. */
    private void acceptExtraction(UploadMethod method, FileType type, long size) {
        if (!extractionEnabled) {
            throw reject(method, Reason.EXTRACTION_NOT_SUPPORTED, "extraction isn't available: no model is configured");
        }
        var refusal = BedrockPolicyModel.refusal(type, size);
        if (refusal.isPresent()) {
            throw reject(method, Reason.EXTRACTION_NOT_SUPPORTED, refusal.get());
        }
    }

    private UploadRejectedException reject(UploadMethod method, Reason reason, String message) {
        rejected.get(method).increment();
        LogEvent.UPLOAD_REJECTED.at(log).with("method", method).with("reason", reason).with("detail", message).log();
        return new UploadRejectedException(reason, message);
    }

    private String acceptedTypes() {
        return String.join(", ", uploads.allowedTypes().stream().map(FileType::mediaType).toList());
    }

    /** The name to keep: without any path, control characters or surrounding space, at most 255 characters. */
    static String cleanName(String fileName) {
        if (fileName == null) {
            return "upload";
        }
        String name = fileName.substring(Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\')) + 1)
                .replaceAll("\\p{Cntrl}", "").strip();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            return "upload";
        }
        return name.length() > MAX_FILE_NAME ? name.substring(0, MAX_FILE_NAME) : name;
    }

    private static Counter uploadCounter(MeterRegistry registry, UploadMethod method, String outcome) {
        return Counter.builder("file-upload-service.uploads")
                .description("Uploads accepted or refused, by style")
                .tag("method", method.name().toLowerCase(java.util.Locale.ROOT))
                .tag("outcome", outcome)
                .register(registry);
    }
}
