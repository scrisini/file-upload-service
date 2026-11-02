package com.crisinis.fileuploadservice.persistence;

import com.crisinis.fileuploadservice.config.FileUploadServiceProperties;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.TableStatus;

/** {@code dynamoDb} in {@code /actuator/health}: the file table exists and is ACTIVE. Not part of readiness. */
@Component
class DynamoDbHealthIndicator implements HealthIndicator {

    private final DynamoDbClient dynamo;
    private final String table;

    DynamoDbHealthIndicator(DynamoDbClient dynamo, FileUploadServiceProperties properties) {
        this.dynamo = dynamo;
        this.table = properties.storage().table();
    }

    @Override
    public Health health() {
        try {
            TableStatus status = dynamo.describeTable(d -> d.tableName(table)).table().tableStatus();
            return (status == TableStatus.ACTIVE ? Health.up() : Health.down())
                    .withDetail("table", table).withDetail("status", status.toString()).build();
        } catch (SdkException e) {
            return Health.down().withDetail("table", table).withDetail("error", e.getMessage()).build();
        }
    }
}
