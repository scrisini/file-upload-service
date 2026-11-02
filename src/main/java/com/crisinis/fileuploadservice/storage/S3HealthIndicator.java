package com.crisinis.fileuploadservice.storage;

import com.crisinis.fileuploadservice.config.FileUploadServiceProperties;
import com.crisinis.fileuploadservice.service.DependencyUnavailableException;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** {@code s3} in {@code /actuator/health}: both buckets reachable. Not part of readiness. */
@Component
class S3HealthIndicator implements HealthIndicator {

    private final FileStorage storage;
    private final FileUploadServiceProperties.Storage buckets;

    S3HealthIndicator(FileStorage storage, FileUploadServiceProperties properties) {
        this.storage = storage;
        this.buckets = properties.storage();
    }

    @Override
    public Health health() {
        Health.Builder health = Health.up();
        try {
            storage.checkBuckets();
        } catch (DependencyUnavailableException e) {
            health = Health.down().withDetail("error", e.getMessage());
        }
        return health.withDetail("quarantine", buckets.quarantineBucket() + "/" + buckets.quarantinePrefix())
                .withDetail("clean", buckets.cleanBucket() + "/" + buckets.cleanPrefix()).build();
    }
}
