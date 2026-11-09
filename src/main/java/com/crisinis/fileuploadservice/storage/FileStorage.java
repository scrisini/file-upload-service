package com.crisinis.fileuploadservice.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import com.crisinis.fileuploadservice.config.FileUploadServiceProperties;
import com.crisinis.fileuploadservice.model.FileType;
import com.crisinis.fileuploadservice.service.DependencyUnavailableException;
import io.micrometer.observation.annotation.Observed;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.MetadataDirective;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.Tag;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

/**
 * The two buckets. Uploads go to quarantine as {@code <quarantinePrefix><id>}, where GuardDuty
 * scans them; promotion copies a clean file to {@code <cleanPrefix><id>} inside S3 (no bytes pass
 * through the service) and deletes it from quarantine. Nothing is written to local disk.
 *
 * <p>Every write to quarantine carries {@code If-None-Match: *}: an object can't be replaced after
 * it landed, so the bytes promoted are the bytes GuardDuty scanned.
 */
@Component
public class FileStorage {

    /** What a promotion did. */
    public enum Promotion {
        /** Copied to the clean bucket and removed from quarantine. */
        COPIED,
        /** The object in quarantine isn't the one GuardDuty scanned (its ETag differs). Nothing copied. */
        SOURCE_CHANGED,
        /** No object in quarantine: promoted earlier, or removed. */
        SOURCE_MISSING
    }

    /** The tag GuardDuty Malware Protection for S3 puts its result in. */
    public static final String SCAN_STATUS_TAG = "GuardDutyMalwareScanStatus";

    private final S3Client s3;
    private final S3Presigner presigner;
    private final FileUploadServiceProperties.Storage storage;
    private final Duration streamTimeout;

    public FileStorage(S3Client s3, S3Presigner presigner, FileUploadServiceProperties properties) {
        this.s3 = s3;
        this.presigner = presigner;
        this.storage = properties.storage();
        this.streamTimeout = properties.uploads().streamTimeout();
    }

    /** The file id for an object, if it's one of this service's uploads in quarantine. */
    public Optional<String> fileIdOf(String bucket, String key) {
        if (!storage.quarantineBucket().equals(bucket) || key == null || !key.startsWith(storage.quarantinePrefix())) {
            return Optional.empty();
        }
        String id = key.substring(storage.quarantinePrefix().length());
        try {
            return UUID.fromString(id).toString().equals(id) ? Optional.of(id) : Optional.empty();
        } catch (IllegalArgumentException notAnId) {
            return Optional.empty();
        }
    }

    /** A URL the caller can PUT exactly this file to, until {@code ttl} passes. */
    public PresignedUpload presignUpload(String id, FileType type, long size, Duration ttl) {
        PresignedPutObjectRequest presigned = call("presignPutObject", () -> presigner.presignPutObject(p -> p
                .signatureDuration(ttl)
                .putObjectRequest(quarantinePut(id, type, size))));
        Map<String, String> headers = new LinkedHashMap<>();
        presigned.signedHeaders().forEach((name, values) -> {
            // Host comes from the URL, and Content-Length from the body: browsers set both themselves
            // and refuse to let scripts set them. Both are still signed, so S3 checks them.
            if (!"host".equalsIgnoreCase(name) && !"content-length".equalsIgnoreCase(name)) {
                headers.put(canonical(name), String.join(",", values));
            }
        });
        try {
            return new PresignedUpload(presigned.url().toURI(), Map.copyOf(headers), presigned.expiration());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("S3 presigned an invalid URL", e);
        }
    }

    /**
     * Streams exactly {@code size} bytes from {@code body} to quarantine without buffering the file:
     * the SDK sends what it reads. Not retried, as the body can't be read twice.
     *
     * @throws ObjectExistsException if an object with this id is already there
     */
    @Observed(name = "file-upload-service.quarantine.store", contextualName = "file-storage#store")
    public void store(String id, FileType type, long size, InputStream body) {
        PutObjectRequest request = quarantinePut(id, type, size).toBuilder()
                .overrideConfiguration(c -> c.apiCallTimeout(streamTimeout).apiCallAttemptTimeout(streamTimeout))
                .build();
        try {
            s3.putObject(request, RequestBody.fromInputStream(body, size));
        } catch (S3Exception e) {
            if (e.statusCode() == 412) {
                throw new ObjectExistsException(id);
            }
            throw new DependencyUnavailableException("s3", "PutObject", e);
        } catch (SdkException e) {
            throw new DependencyUnavailableException("s3", "PutObject", e);
        }
    }

    /** The first bytes of a file in quarantine, enough to recognise its type; empty if it isn't there. */
    public Optional<byte[]> readSignature(String id) {
        return read(id, "bytes=0-" + (FileType.SIGNATURE_BYTES - 1));
    }

    /**
     * Copies a scanned file to the clean bucket inside S3, then deletes it from quarantine. The copy
     * is conditional on {@code eTag}, the version GuardDuty scanned. Safe to repeat.
     */
    public Promotion promote(String id, FileType type, String eTag) {
        try {
            s3.copyObject(c -> {
                c.sourceBucket(storage.quarantineBucket()).sourceKey(quarantineKey(id))
                        .destinationBucket(storage.cleanBucket()).destinationKey(cleanKey(id))
                        .metadataDirective(MetadataDirective.REPLACE).contentType(type.mediaType());
                if (eTag != null && !eTag.isBlank()) {
                    c.copySourceIfMatch(quoted(eTag));
                }
            });
        } catch (NoSuchKeyException e) {
            return Promotion.SOURCE_MISSING;
        } catch (S3Exception e) {
            if (e.statusCode() == 412) {
                return Promotion.SOURCE_CHANGED;
            }
            if (e.statusCode() == 404) {
                return Promotion.SOURCE_MISSING;
            }
            throw new DependencyUnavailableException("s3", "CopyObject", e);
        } catch (SdkException e) {
            throw new DependencyUnavailableException("s3", "CopyObject", e);
        }
        call("DeleteObject", () -> s3.deleteObject(d -> d.bucket(storage.quarantineBucket()).key(quarantineKey(id))));
        return Promotion.COPIED;
    }

    /**
     * GuardDuty's result from the object's {@value #SCAN_STATUS_TAG} tag, which it adds after each
     * scan when the protection plan has tagging on: a record of the result that doesn't depend on
     * the event reaching the service. Empty while unscanned, or if the object isn't there.
     */
    public Optional<String> scanStatusTag(String id) {
        try {
            return s3.getObjectTagging(t -> t.bucket(storage.quarantineBucket()).key(quarantineKey(id))).tagSet().stream()
                    .filter(tag -> SCAN_STATUS_TAG.equals(tag.key()))
                    .map(Tag::value)
                    .findFirst();
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw new DependencyUnavailableException("s3", "GetObjectTagging", e);
        } catch (SdkException e) {
            throw new DependencyUnavailableException("s3", "GetObjectTagging", e);
        }
    }

    /** Tags an object in quarantine as GuardDuty does (the local GuardDuty simulator). */
    public void tagScanStatus(String id, String scanResultStatus) {
        call("PutObjectTagging", () -> s3.putObjectTagging(t -> t.bucket(storage.quarantineBucket())
                .key(quarantineKey(id)).tagging(g -> g.tagSet(Tag.builder().key(SCAN_STATUS_TAG).value(scanResultStatus).build()))));
    }

    /**
     * A clean file's bytes, for extraction; empty if it isn't there. Read whole into memory, so only
     * for files Bedrock can take (at most {@code maxBytes}, checked at upload and again here).
     *
     * @throws IllegalStateException if the object is larger than {@code maxBytes}
     */
    public Optional<byte[]> readClean(String id, long maxBytes) {
        try (ResponseInputStream<GetObjectResponse> in = s3.getObject(g -> g.bucket(storage.cleanBucket()).key(cleanKey(id)))) {
            Long length = in.response().contentLength();
            if (length != null && length > maxBytes) {
                throw new IllegalStateException("clean object " + id + " is " + length + " bytes, more than " + maxBytes);
            }
            return Optional.of(in.readNBytes((int) maxBytes + 1));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new DependencyUnavailableException("s3", "GetObject", new UncheckedIOException(e));
        } catch (SdkException e) {
            throw new DependencyUnavailableException("s3", "GetObject", e);
        }
    }

    /** Whether the file is in the clean bucket. */
    public boolean isPromoted(String id) {
        try {
            s3.headObject(h -> h.bucket(storage.cleanBucket()).key(cleanKey(id)));
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw new DependencyUnavailableException("s3", "HeadObject", e);
        } catch (SdkException e) {
            throw new DependencyUnavailableException("s3", "HeadObject", e);
        }
    }

    /** A URL to download a clean file for {@code ttl}, saved under its original name. */
    public URI presignDownload(String id, String fileName, Duration ttl) {
        String disposition = "attachment; filename*=UTF-8''" + java.net.URLEncoder.encode(fileName, StandardCharsets.UTF_8)
                .replace("+", "%20");
        try {
            return call("presignGetObject", () -> presigner.presignGetObject(p -> p.signatureDuration(ttl)
                    .getObjectRequest(g -> g.bucket(storage.cleanBucket()).key(cleanKey(id))
                            .responseContentDisposition(disposition))))
                    .url().toURI();
        } catch (URISyntaxException e) {
            throw new IllegalStateException("S3 presigned an invalid URL", e);
        }
    }

    /** Checks both buckets can be reached (health). */
    public void checkBuckets() {
        call("HeadBucket", () -> s3.headBucket(h -> h.bucket(storage.quarantineBucket())));
        call("HeadBucket", () -> s3.headBucket(h -> h.bucket(storage.cleanBucket())));
    }

    /** Objects in quarantine, at most {@code max} (the local GuardDuty simulator). */
    public List<S3Object> listQuarantine(int max) {
        return call("ListObjectsV2", () -> s3.listObjectsV2(l -> l.bucket(storage.quarantineBucket())
                .prefix(storage.quarantinePrefix()).maxKeys(max))).contents();
    }

    /** A whole object in quarantine (the local GuardDuty simulator); empty if it isn't there. */
    public Optional<byte[]> readAll(String id) {
        return read(id, null);
    }

    public String quarantineKey(String id) {
        return storage.quarantinePrefix() + id;
    }

    public String cleanKey(String id) {
        return storage.cleanPrefix() + id;
    }

    private PutObjectRequest quarantinePut(String id, FileType type, long size) {
        return PutObjectRequest.builder()
                .bucket(storage.quarantineBucket()).key(quarantineKey(id))
                .contentType(type.mediaType()).contentLength(size)
                .ifNoneMatch("*")
                .build();
    }

    private Optional<byte[]> read(String id, String range) {
        try (ResponseInputStream<GetObjectResponse> in = s3.getObject(g -> g.bucket(storage.quarantineBucket())
                .key(quarantineKey(id)).range(range))) {
            return Optional.of(in.readAllBytes());
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new DependencyUnavailableException("s3", "GetObject", new UncheckedIOException(e));
        } catch (SdkException e) {
            throw new DependencyUnavailableException("s3", "GetObject", e);
        }
    }

    /** {@code content-type} as {@code Content-Type}, as documentation and browsers write it. */
    private static String canonical(String header) {
        StringBuilder name = new StringBuilder(header.length());
        boolean upper = true;
        for (char c : header.toCharArray()) {
            name.append(upper ? Character.toUpperCase(c) : c);
            upper = c == '-';
        }
        return name.toString();
    }

    private static String quoted(String eTag) {
        return eTag.startsWith("\"") ? eTag : "\"" + eTag + "\"";
    }

    private static <T> T call(String operation, Supplier<T> call) {
        try {
            return call.get();
        } catch (SdkException e) {
            throw new DependencyUnavailableException("s3", operation, e);
        }
    }
}
