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
import java.util.UUID;
import java.util.concurrent.Semaphore;

import com.crisinis.fileuploadservice.config.FileUploadServiceProperties;
import com.crisinis.fileuploadservice.logging.LogEvent;
import com.crisinis.fileuploadservice.model.FileRecord;
import com.crisinis.fileuploadservice.model.FileStatus;
import com.crisinis.fileuploadservice.model.FileType;
import com.crisinis.fileuploadservice.model.UploadMethod;
import com.crisinis.fileuploadservice.persistence.FileRepository;
import com.crisinis.fileuploadservice.service.UploadRejectedException.Reason;
import com.crisinis.fileuploadservice.storage.FileStorage;
import com.crisinis.fileuploadservice.storage.ObjectExistsException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
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

    public UploadService(FileRepository files, FileStorage storage, FileUploadServiceProperties properties,
                         Clock clock, MeterRegistry registry) {
        this.files = files;
        this.storage = storage;
        this.uploads = properties.uploads();
        this.clock = clock;
        this.streams = new Semaphore(uploads.maxConcurrentStreams());
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

    /** Presigned style: records the file and returns a URL that accepts exactly this file. */
    public UploadTicket requestUpload(String fileName, String mediaType, long size) {
        FileType type = accept(UploadMethod.PRESIGNED_URL, mediaType, size);
        FileRecord file = FileRecord.awaitingUpload(UUID.randomUUID().toString(), cleanName(fileName), type, size,
                UploadMethod.PRESIGNED_URL, clock.instant());
        files.create(file);
        var upload = storage.presignUpload(file.id(), type, size, uploads.urlTtl());
        accepted.get(UploadMethod.PRESIGNED_URL).increment();
        LogEvent.UPLOAD_URL_ISSUED.at(log).with("file_id", file.id()).with("file_type", type)
                .with("size", size).with("expires_at", upload.expiresAt()).log();
        return new UploadTicket(file, upload);
    }

    /**
     * Stream style: checks the declared type, the length and the content's signature, then streams
     * the body to quarantine. Nothing is stored if a check fails.
     *
     * @param contentLength the request's Content-Length; -1 when it has none (chunked)
     */
    public FileRecord stream(String fileName, String mediaType, long contentLength, InputStream body) {
        if (contentLength < 0) {
            throw reject(UploadMethod.STREAM, Reason.LENGTH_REQUIRED, "send the file with a Content-Length header");
        }
        FileType type = accept(UploadMethod.STREAM, mediaType, contentLength);
        if (!streams.tryAcquire()) {
            throw new UploadCapacityExceededException(uploads.maxConcurrentStreams());
        }
        try {
            return streamAccepted(cleanName(fileName), type, contentLength, body);
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

    private FileRecord streamAccepted(String fileName, FileType type, long size, InputStream body) {
        byte[] signature = readSignature(body, size);
        if (!type.matches(signature)) {
            throw reject(UploadMethod.STREAM, Reason.CONTENT_MISMATCH,
                    "the content is not " + type.mediaType() + FileType.detect(signature)
                            .map(actual -> " (it looks like " + actual.mediaType() + ")").orElse(""));
        }
        FileRecord file = FileRecord.awaitingUpload(UUID.randomUUID().toString(), fileName, type, size,
                UploadMethod.STREAM, clock.instant());
        files.create(file);
        ExactLengthInputStream rest = new ExactLengthInputStream(
                new SequenceInputStream(new ByteArrayInputStream(signature), body), size);
        try {
            storage.store(file.id(), type, size, rest);
        } catch (DependencyUnavailableException | ObjectExistsException e) {
            files.delete(file.id());
            if (rest.endedEarly()) {
                throw reject(UploadMethod.STREAM, Reason.INCOMPLETE,
                        "the body ended before the " + size + " bytes in Content-Length");
            }
            throw e;
        }
        var update = files.markSubmitted(file.id(), clock.instant());
        if (update.map(FileRepository.Update::changed).orElse(false)) {
            streamedBytes.increment(size);
        }
        FileRecord submitted = update.map(FileRepository.Update::file).orElse(file);
        accepted.get(UploadMethod.STREAM).increment();
        LogEvent.UPLOAD_STREAMED.at(log).with("file_id", file.id()).with("file_type", type).with("size", size).log();
        return submitted;
    }

    /** The first bytes of the body, to check its type before anything is stored. */
    private byte[] readSignature(InputStream body, long size) {
        try {
            return body.readNBytes((int) Math.min(size, FileType.SIGNATURE_BYTES));
        } catch (IOException e) {
            throw reject(UploadMethod.STREAM, Reason.INCOMPLETE, "the connection failed while reading the body");
        }
    }

    /** The type for this declared media type and size, or a rejection. */
    private FileType accept(UploadMethod method, String mediaType, long size) {
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
        return type;
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
