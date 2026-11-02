package com.crisinis.fileuploadservice.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import com.crisinis.fileuploadservice.model.FileStatus;
import com.crisinis.fileuploadservice.model.ScanResult;
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
        assertThat(update.getValue().updateExpression()).contains("scanQueue = :scanning").contains("REMOVE expiresAt");
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

        assertThat(repository.recordScan(ID, ScanResult.NO_THREATS_FOUND, AT, null))
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
