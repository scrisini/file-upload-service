package com.crisinis.fileuploadservice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import com.crisinis.fileuploadservice.model.FileType;
import com.crisinis.fileuploadservice.support.TestProperties;
import org.junit.jupiter.api.Test;

class FileUploadServicePropertiesTests {

    @Test
    void defaultsAreTheDocumentedOnes() {
        FileUploadServiceProperties properties = TestProperties.defaults();

        assertThat(properties.uploads().maxSize().toMegabytes()).isEqualTo(25);
        assertThat(properties.uploads().allowedTypes()).containsExactly(FileType.values());
        assertThat(properties.scan().enabled()).isFalse();
        assertThat(properties.aws().overridden()).isFalse();
    }

    @Test
    void refusesSettingsThatWouldBreakTheFlow() {
        assertRefused("file-upload-service.storage.clean-bucket", "file-upload-quarantine",
                "file-upload-service.storage.clean-prefix", "uploads/clean/", "must not contain each other");
        assertRefused("file-upload-service.storage.quarantine-prefix", "uploads", "ending in '/'");
        assertRefused("file-upload-service.storage.quarantine-bucket", "Not_A_Bucket", "S3 bucket name");
        assertRefused("file-upload-service.uploads.max-size", "6GB", "5GB");
        assertRefused("file-upload-service.uploads.url-ttl", "8d", "7d");
        assertRefused("file-upload-service.scan.queue-url", "scan-events", "queue's URL");
        assertRefused("file-upload-service.aws.s3-endpoint", "localhost:9090", "must be a URL");
    }

    @Test
    void presignedUrlsUseThePresignEndpointWhenSet() {
        assertThat(TestProperties.of(Map.of("file-upload-service.aws.s3-endpoint", "http://s3:9090"))
                .aws().presignEndpoint()).isEqualTo("http://s3:9090");
        FileUploadServiceProperties inDocker = TestProperties.of(Map.of(
                "file-upload-service.aws.s3-endpoint", "http://s3:9090",
                "file-upload-service.aws.s3-presign-endpoint", "http://localhost:9099"));
        assertThat(inDocker.aws().presignEndpoint()).isEqualTo("http://localhost:9099");
        assertThat(TestProperties.defaults().aws().presignEndpoint()).isEmpty();
        assertThat(TestProperties.of(Map.of("file-upload-service.aws.s3-presign-endpoint", "http://localhost:9099"))
                .aws().overridden()).as("an emulator setting, refused in production").isTrue();
    }

    @Test
    void acceptsOneBucketWithSeparateFolders() {
        FileUploadServiceProperties properties = TestProperties.of(Map.of(
                "file-upload-service.storage.clean-bucket", "file-upload-quarantine",
                "file-upload-service.storage.clean-prefix", "clean/"));

        assertThat(properties.storage().cleanBucket()).isEqualTo(properties.storage().quarantineBucket());
    }

    private static void assertRefused(String... settingsThenMessage) {
        Map<String, String> settings = new java.util.HashMap<>();
        for (int i = 0; i + 1 < settingsThenMessage.length; i += 2) {
            settings.put(settingsThenMessage[i], settingsThenMessage[i + 1]);
        }
        String message = settingsThenMessage[settingsThenMessage.length - 1];
        assertThatThrownBy(() -> TestProperties.of(settings)).rootCause().hasMessageContaining(message);
    }
}
