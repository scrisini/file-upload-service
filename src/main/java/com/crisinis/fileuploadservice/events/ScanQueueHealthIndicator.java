package com.crisinis.fileuploadservice.events;

import java.util.Map;

import com.crisinis.fileuploadservice.config.FileUploadServiceProperties;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * {@code scanQueue} in {@code /actuator/health}: the queue answers, with its backlog. Not part of
 * readiness. Without a queue configured it's UNKNOWN: uploads are never scanned.
 */
@Component
class ScanQueueHealthIndicator implements HealthIndicator {

    private final SqsClient sqs;
    private final FileUploadServiceProperties.Scan scan;

    ScanQueueHealthIndicator(SqsClient sqs, FileUploadServiceProperties properties) {
        this.sqs = sqs;
        this.scan = properties.scan();
    }

    @Override
    public Health health() {
        if (!scan.enabled()) {
            return Health.unknown().withDetail("queue", "not configured: uploads are not scanned").build();
        }
        try {
            Map<QueueAttributeName, String> attributes = sqs.getQueueAttributes(g -> g.queueUrl(scan.queueUrl())
                    .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                            QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE)).attributes();
            return Health.up().withDetail("queue", scan.queueUrl())
                    .withDetail("waiting", attributes.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES))
                    .withDetail("inFlight", attributes.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE))
                    .build();
        } catch (SdkException e) {
            return Health.down().withDetail("queue", scan.queueUrl()).withDetail("error", e.getMessage()).build();
        }
    }
}
