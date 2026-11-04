package com.crisinis.fileuploadservice.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import com.crisinis.fileuploadservice.model.FileType;
import com.crisinis.fileuploadservice.storage.FileStorage;
import com.crisinis.fileuploadservice.storage.PresignedUpload;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Real S3: what the emulator can't show, above all that S3 enforces what a presigned URL is signed
 * for. Needs {@link RealAws#REGION}, {@link RealAws#QUARANTINE_BUCKET}, {@link RealAws#CLEAN_BUCKET}
 * and the service's S3 permissions. Objects are written under the configured prefixes and removed.
 */
class S3StorageIT {

    static final byte[] PNG = PolicyDocument.png("IT-S3");

    static S3Client s3;
    static S3Presigner presigner;
    static FileStorage storage;
    static String quarantine;
    static String clean;
    final String id = UUID.randomUUID().toString();
    final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void connect() {
        quarantine = RealAws.require(RealAws.QUARANTINE_BUCKET);
        clean = RealAws.require(RealAws.CLEAN_BUCKET);
        s3 = S3Client.builder().region(RealAws.region()).build();
        presigner = S3Presigner.builder().region(RealAws.region()).build();
        storage = new FileStorage(s3, presigner, RealAws.properties(Map.of(
                "file-upload-service.storage.quarantine-bucket", quarantine,
                "file-upload-service.storage.clean-bucket", clean)));
    }

    @AfterEach
    void removeObjects() {
        s3.deleteObject(d -> d.bucket(quarantine).key(storage.quarantineKey(id)));
        s3.deleteObject(d -> d.bucket(clean).key(storage.cleanKey(id)));
    }

    @AfterAll
    static void close() {
        if (s3 != null) {
            s3.close();
            presigner.close();
        }
    }

    HttpResponse<String> put(URI url, String contentType, byte[] body, boolean ifNoneMatch) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(30))
                .header("Content-Type", contentType).PUT(HttpRequest.BodyPublishers.ofByteArray(body));
        if (ifNoneMatch) {
            request.header("If-None-Match", "*");
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void aPresignedUrlAcceptsExactlyTheDeclaredFileOnce() throws Exception {
        PresignedUpload upload = storage.presignUpload(id, FileType.PNG, PNG.length, Duration.ofMinutes(5));

        assertThat(put(upload.url(), "image/jpeg", PNG, true).statusCode()).as("another content type").isEqualTo(403);
        assertThat(put(upload.url(), "image/png", java.util.Arrays.copyOf(PNG, PNG.length - 1), true).statusCode())
                .as("another length").isEqualTo(403);
        assertThat(put(upload.url(), "image/png", PNG, false).statusCode()).as("without If-None-Match").isEqualTo(403);
        assertThat(put(upload.url(), "image/png", PNG, true).statusCode()).as("as signed").isEqualTo(200);
        assertThat(put(upload.url(), "image/png", PNG, true).statusCode()).as("a second time").isEqualTo(412);
    }

    @Test
    void aStreamedFileIsPromotedOnlyAsTheVersionScanned() throws Exception {
        storage.store(id, FileType.PNG, PNG.length, new ByteArrayInputStream(PNG));
        assertThat(storage.readSignature(id)).hasValueSatisfying(head -> assertThat(FileType.PNG.matches(head)).isTrue());
        String eTag = s3.headObject(h -> h.bucket(quarantine).key(storage.quarantineKey(id))).eTag();

        assertThat(storage.promote(id, FileType.PNG, "\"0000000000000000000000000000dead\""))
                .as("not the version scanned").isEqualTo(FileStorage.Promotion.SOURCE_CHANGED);
        assertThat(storage.promote(id, FileType.PNG, eTag)).isEqualTo(FileStorage.Promotion.COPIED);

        assertThat(storage.isPromoted(id)).isTrue();
        assertThat(storage.readSignature(id)).as("gone from quarantine").isEmpty();
        assertThat(storage.readClean(id, 4_500_000)).hasValueSatisfying(bytes -> assertThat(bytes).isEqualTo(PNG));
        HttpResponse<byte[]> download = http.send(HttpRequest.newBuilder(storage.presignDownload(id, "policy.png",
                Duration.ofMinutes(1))).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.body()).isEqualTo(PNG);
        assertThat(download.headers().firstValue("Content-Disposition")).hasValueSatisfying(d -> assertThat(d).contains("policy.png"));
    }
}
