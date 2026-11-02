package com.crisinis.fileuploadservice.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import com.crisinis.fileuploadservice.model.FileRecord;
import com.crisinis.fileuploadservice.model.FileStatus;
import com.crisinis.fileuploadservice.model.FileType;
import com.crisinis.fileuploadservice.model.ScanResult;
import com.crisinis.fileuploadservice.model.UploadMethod;
import com.crisinis.fileuploadservice.service.DependencyUnavailableException;
import com.crisinis.fileuploadservice.support.TestProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;

/** The conditional writes and what happens when their condition fails. Run against DynamoDB: README "Running locally". */
class FileRepositoryTests {

    static final String ID = "0b8e2d4a-55a3-4d0e-9f53-3e1b5f0a7c11";
    static final Instant AT = Instant.parse("2026-11-02T10:00:00Z");

    private final DynamoDbClient dynamo = mock(DynamoDbClient.class);
    private final FileRepository repository = new FileRepository(dynamo, TestProperties.defaults());

    static Map<String, AttributeValue> item(FileStatus status) {
        Map<String, AttributeValue> item = new HashMap<>(Map.of(
                "id", AttributeValue.fromS(ID), "fileName", AttributeValue.fromS("a.png"),
                "fileType", AttributeValue.fromS("PNG"), "size", AttributeValue.fromN("8"),
                "method", AttributeValue.fromS("STREAM"), "status", AttributeValue.fromS(status.name()),
                "createdAt", AttributeValue.fromN(Long.toString(AT.toEpochMilli()))));
        item.put("submittedAt", AttributeValue.fromN(Long.toString(AT.toEpochMilli())));
        return item;
    }

    @Test
    void submittingMovesAnAwaitingFileToScanning() {
        ArgumentCaptor<UpdateItemRequest> update = ArgumentCaptor.forClass(UpdateItemRequest.class);
        when(dynamo.updateItem(update.capture())).thenReturn(UpdateItemResponse.builder().attributes(item(FileStatus.SCANNING)).build());

        var result = repository.markSubmitted(ID, AT);

        assertThat(result).hasValueSatisfying(u -> assertThat(u.changed()).isTrue());
        assertThat(update.getValue().key()).containsEntry("id", AttributeValue.fromS(ID));
        assertThat(update.getValue().conditionExpression()).isEqualTo("#status = :awaiting");
        assertThat(update.getValue().updateExpression()).contains("stage = :scanning, stageSince = :at").contains("REMOVE expiresAt");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aRecordOutlivesEveryEventStillOnItsWay() {
        ArgumentCaptor<Consumer<PutItemRequest.Builder>> put = ArgumentCaptor.forClass(Consumer.class);
        when(dynamo.putItem(put.capture())).thenReturn(null);

        repository.createIfAbsent(FileRecord.awaitingUpload(ID, "a.png", FileType.PNG, 8, UploadMethod.STREAM, AT));

        PutItemRequest.Builder request = PutItemRequest.builder();
        put.getValue().accept(request);
        long expiresAt = Long.parseLong(request.build().item().get("expiresAt").n());
        // SQS keeps events 4 days and its dead-letter queue up to 14: the record must still be there
        assertThat(Duration.ofSeconds(expiresAt - AT.getEpochSecond())).isGreaterThan(Duration.ofDays(14));
    }

    @Test
    void creatingAnIdThatExistsReturnsTheExistingRecordUnchanged() {
        when(dynamo.putItem(org.mockito.ArgumentMatchers.<Consumer<PutItemRequest.Builder>>any()))
                .thenThrow(ConditionalCheckFailedException.builder().message("exists").build());
        when(dynamo.getItem(org.mockito.ArgumentMatchers.<Consumer<GetItemRequest.Builder>>any()))
                .thenReturn(GetItemResponse.builder().item(item(FileStatus.SCANNING)).build());

        FileRepository.Update result = repository.createIfAbsent(
                FileRecord.awaitingUpload(ID, "a.png", FileType.PNG, 8, UploadMethod.STREAM, AT));

        assertThat(result.changed()).isFalse();
        assertThat(result.file().status()).isEqualTo(FileStatus.SCANNING);
    }

    @Test
    void aCleanResultEntersPromotingWithTheVersionScannedAndStaysInFlight() {
        ArgumentCaptor<UpdateItemRequest> update = ArgumentCaptor.forClass(UpdateItemRequest.class);
        when(dynamo.updateItem(update.capture())).thenReturn(UpdateItemResponse.builder().attributes(item(FileStatus.PROMOTING)).build());

        repository.recordScan(ID, ScanResult.NO_THREATS_FOUND, AT, null, "etag-1");

        assertThat(update.getValue().updateExpression())
                .contains("stage = :next, stageSince = :at").contains("scannedETag = :etag").doesNotContain("REMOVE expiresAt, stage");
        assertThat(update.getValue().expressionAttributeValues()).containsEntry(":etag", AttributeValue.fromS("etag-1"));
    }

    @Test
    void aFinalResultLeavesTheInFlightIndex() {
        ArgumentCaptor<UpdateItemRequest> update = ArgumentCaptor.forClass(UpdateItemRequest.class);
        when(dynamo.updateItem(update.capture())).thenReturn(UpdateItemResponse.builder().attributes(item(FileStatus.INFECTED)).build());

        repository.recordScan(ID, ScanResult.THREATS_FOUND, AT, "EICAR", "etag-1");
        repository.finishPromotion(ID, FileStatus.AVAILABLE, AT, null);

        assertThat(update.getAllValues().get(0).updateExpression()).contains("REMOVE expiresAt, stage, stageSince");
        assertThat(update.getAllValues().get(1).updateExpression()).contains("REMOVE stage, stageSince");
    }

    @Test
    void onlyFilesInFlightAreInTheIndex() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> repository.inStageSince(FileStatus.AVAILABLE, AT, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void submittingAFileAlreadyPastScanningOnlyFillsInTheTime() {
        when(dynamo.updateItem(any(UpdateItemRequest.class)))
                .thenThrow(ConditionalCheckFailedException.builder().message("status").build())
                .thenReturn(UpdateItemResponse.builder().attributes(item(FileStatus.INFECTED)).build());

        assertThat(repository.markSubmitted(ID, AT)).hasValueSatisfying(u -> {
            assertThat(u.changed()).isFalse();
            assertThat(u.file().status()).isEqualTo(FileStatus.INFECTED);
        });
    }

    @Test
    void submittingAnUnknownFileFindsNothing() {
        when(dynamo.updateItem(any(UpdateItemRequest.class)))
                .thenThrow(ConditionalCheckFailedException.builder().message("status").build());

        assertThat(repository.markSubmitted(ID, AT)).isEmpty();
    }

    @Test
    void aSecondResultForTheSameFileChangesNothing() {
        when(dynamo.updateItem(any(UpdateItemRequest.class)))
                .thenThrow(ConditionalCheckFailedException.builder().message("status").build());
        when(dynamo.getItem(org.mockito.ArgumentMatchers.<Consumer<GetItemRequest.Builder>>any()))
                .thenReturn(GetItemResponse.builder().item(item(FileStatus.AVAILABLE)).build());

        assertThat(repository.recordScan(ID, ScanResult.NO_THREATS_FOUND, AT, null, "etag-1"))
                .hasValueSatisfying(u -> assertThat(u.changed()).isFalse());
    }

    @Test
    void aDynamoDbFailureIsADependencyFailure() {
        when(dynamo.getItem(org.mockito.ArgumentMatchers.<Consumer<GetItemRequest.Builder>>any()))
                .thenThrow(SdkClientException.create("Unable to execute HTTP request"));

        assertThatThrownBy(() -> repository.find(ID)).isInstanceOfSatisfying(DependencyUnavailableException.class,
                e -> assertThat(e.dependency()).isEqualTo("dynamodb"));
    }
}
