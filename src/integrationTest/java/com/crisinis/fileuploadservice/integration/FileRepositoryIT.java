package com.crisinis.fileuploadservice.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import com.crisinis.fileuploadservice.model.ExtractionKind;
import com.crisinis.fileuploadservice.model.ExtractionStatus;
import com.crisinis.fileuploadservice.model.FileRecord;
import com.crisinis.fileuploadservice.model.FileStatus;
import com.crisinis.fileuploadservice.model.FileType;
import com.crisinis.fileuploadservice.model.ScanResult;
import com.crisinis.fileuploadservice.model.UploadMethod;
import com.crisinis.fileuploadservice.persistence.FileRepository;
import com.crisinis.fileuploadservice.persistence.FileRepository.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Every conditional transition against a real table created from deploy/aws/dynamodb-table.json:
 * the expressions, the in-flight index and the extraction lease. Needs {@link RealAws#REGION},
 * {@link RealAws#TABLE} and the service's DynamoDB permissions. Items are removed afterwards.
 */
class FileRepositoryIT {

    static DynamoDbClient dynamo;
    static FileRepository files;
    static String table;
    final String id = UUID.randomUUID().toString();
    // DynamoDB keeps milliseconds; the index's sort key is compared exactly
    final Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    @BeforeAll
    static void connect() {
        table = RealAws.require(RealAws.TABLE);
        dynamo = DynamoDbClient.builder().region(RealAws.region()).build();
        files = new FileRepository(dynamo, RealAws.properties(Map.of("file-upload-service.storage.table", table)));
    }

    @AfterEach
    void removeItem() {
        dynamo.deleteItem(d -> d.tableName(table).key(Map.of("id", AttributeValue.fromS(id))));
    }

    @AfterAll
    static void close() {
        if (dynamo != null) {
            dynamo.close();
        }
    }

    @Test
    void aFileGoesThroughEveryStatusOnceAndIntoExtraction() throws Exception {
        FileRecord file = FileRecord.awaitingUpload(id, "policy.png", FileType.PNG, 1234, UploadMethod.STREAM, now,
                ExtractionKind.INSURANCE_POLICY);

        assertThat(files.createIfAbsent(file).changed()).isTrue();
        assertThat(files.createIfAbsent(file).changed()).as("a second create returns the first").isFalse();
        assertThat(files.markSubmitted(id, now)).hasValueSatisfying(u -> assertThat(u.changed()).isTrue());
        assertThat(files.markSubmitted(id, now)).hasValueSatisfying(u -> assertThat(u.changed()).isFalse());
        awaitInStage(Stage.SCANNING);

        assertThat(files.recordScan(id, ScanResult.NO_THREATS_FOUND, now, null, "\"etag-1\""))
                .hasValueSatisfying(u -> assertThat(u.file().status()).isEqualTo(FileStatus.PROMOTING));
        assertThat(files.recordScan(id, ScanResult.THREATS_FOUND, now, "late", null))
                .as("a later result changes nothing").hasValueSatisfying(u -> assertThat(u.changed()).isFalse());
        assertThat(files.find(id)).hasValueSatisfying(f -> assertThat(f.scannedETag()).isEqualTo("\"etag-1\""));

        assertThat(files.finishPromotion(id, FileStatus.AVAILABLE, now, null, true))
                .hasValueSatisfying(u -> assertThat(u.file().status()).isEqualTo(FileStatus.AVAILABLE));
        awaitInStage(Stage.EXTRACTING);
        assertThat(files.findExtraction(id)).hasValueSatisfying(e -> assertThat(e.status()).isEqualTo(ExtractionStatus.PENDING));

        var claim = files.claimExtraction(id, now, Duration.ofMinutes(3));
        assertThat(claim).hasValueSatisfying(c -> assertThat(c.attempt()).isEqualTo(1));
        assertThat(files.claimExtraction(id, now.plusSeconds(60), Duration.ofMinutes(3))).as("held by the first").isEmpty();
        assertThat(files.claimExtraction(id, now.plusSeconds(181), Duration.ofMinutes(3))).as("its lease ended")
                .hasValueSatisfying(c -> assertThat(c.attempt()).isEqualTo(2));

        assertThat(files.completeExtraction(id, "model-1", "{\"insurer\":null}", now)).isTrue();
        assertThat(files.failExtraction(id, "LATE", now)).as("already ended").isFalse();
        assertThat(files.findExtraction(id)).hasValueSatisfying(e -> {
            assertThat(e.status()).isEqualTo(ExtractionStatus.COMPLETED);
            assertThat(e.attempts()).isEqualTo(2);
            assertThat(e.policyJson()).isEqualTo("{\"insurer\":null}");
        });
    }

    /** The index is eventually consistent: wait for the item to show up in it. */
    private void awaitInStage(Stage stage) throws InterruptedException {
        for (int i = 0; i < 40; i++) {
            if (files.inStageSince(stage, now.plusMillis(1), 1000).contains(id)) {
                return;
            }
            Thread.sleep(250);
        }
        throw new AssertionError(id + " never appeared in the in-flight index as " + stage);
    }
}
