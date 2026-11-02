package com.crisinis.fileuploadservice.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.Map;

import com.crisinis.fileuploadservice.model.FileType;
import com.crisinis.fileuploadservice.support.TestProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

class FileStorageTests {

    static final String ID = "0b8e2d4a-55a3-4d0e-9f53-3e1b5f0a7c11";

    // presigning is computed locally: no network
    private final S3Presigner presigner = S3Presigner.builder().region(Region.AP_SOUTHEAST_2)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIDEXAMPLE", "secret")))
            .build();
    private final FileStorage storage = new FileStorage(mock(S3Client.class), presigner, TestProperties.defaults());

    @AfterEach
    void close() {
        presigner.close();
    }

    @Test
    void aPresignedUploadAcceptsOnlyTheDeclaredFileOnce() {
        PresignedUpload upload = storage.presignUpload(ID, FileType.PNG, 1234, Duration.ofMinutes(15));

        assertThat(upload.url().getPath()).endsWith("/uploads/" + ID);
        assertThat(upload.url().getHost()).startsWith("file-upload-quarantine.");
        // what S3 checks: each of these is signed, so a PUT without exactly them is refused
        assertThat(upload.url().getQuery()).contains("X-Amz-SignedHeaders=content-length;content-type;host;if-none-match");
        // the caller sets these; the browser sets Content-Length from the body, and it's signed too
        assertThat(upload.headers()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "Content-Type", "image/png", "If-None-Match", "*"));
        assertThat(upload.url().getQuery()).contains("X-Amz-Expires=900");
    }

    @Test
    void recognisesOnlyItsOwnUploads() {
        assertThat(storage.fileIdOf("file-upload-quarantine", "uploads/" + ID)).contains(ID);
        assertThat(storage.fileIdOf("another-bucket", "uploads/" + ID)).isEmpty();
        assertThat(storage.fileIdOf("file-upload-quarantine", "files/" + ID)).isEmpty();
        assertThat(storage.fileIdOf("file-upload-quarantine", "uploads/not-an-id")).isEmpty();
        assertThat(storage.fileIdOf("file-upload-quarantine", "uploads/" + ID.toUpperCase())).isEmpty();
        assertThat(storage.fileIdOf("file-upload-quarantine", null)).isEmpty();
    }
}
