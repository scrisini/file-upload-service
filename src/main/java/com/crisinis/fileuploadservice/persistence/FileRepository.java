package com.crisinis.fileuploadservice.persistence;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import com.crisinis.fileuploadservice.config.FileUploadServiceProperties;
import com.crisinis.fileuploadservice.model.FileRecord;
import com.crisinis.fileuploadservice.model.FileStatus;
import com.crisinis.fileuploadservice.model.FileType;
import com.crisinis.fileuploadservice.model.ScanResult;
import com.crisinis.fileuploadservice.model.UploadMethod;
import com.crisinis.fileuploadservice.service.DependencyUnavailableException;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.Select;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

/**
 * File records in DynamoDB: the only state the service keeps, so any instance can answer for any
 * file. Every status change is a conditional write, so events delivered twice, out of order or to
 * two instances at once can't move a file backwards.
 *
 * <p>Files being scanned also carry {@code scanQueue = "SCANNING"}, which puts them in the sparse
 * {@code scanning} index (sort key {@code submittedAt}). Counting scans in progress and finding
 * overdue ones read only that index, never the table. Schema: README "Data".
 */
@Repository
public class FileRepository {

    public static final String SCANNING_INDEX = "scanning";
    private static final String SCANNING = "SCANNING";
    /** A record whose upload never arrives is deleted by DynamoDB's TTL this long after its URL expires. */
    private static final Duration ABANDONED_AFTER = Duration.ofHours(1);

    /**
     * A conditional update's outcome.
     *
     * @param changed whether this call moved the file on; false when another delivery already had
     * @param file    the record as it is now
     */
    public record Update(boolean changed, FileRecord file) {
    }

    private final DynamoDbClient dynamo;
    private final String table;
    private final Duration urlTtl;

    public FileRepository(DynamoDbClient dynamo, FileUploadServiceProperties properties) {
        this.dynamo = dynamo;
        this.table = properties.storage().table();
        this.urlTtl = properties.uploads().urlTtl();
    }

    /** Stores a new record; it expires unless its upload arrives ({@code expiresAt}, DynamoDB TTL). */
    public void create(FileRecord file) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("id", s(file.id()));
        item.put("fileName", s(file.fileName()));
        item.put("fileType", s(file.type().name()));
        item.put("size", n(file.size()));
        item.put("method", s(file.method().name()));
        item.put("status", s(file.status().name()));
        item.put("createdAt", n(file.createdAt().toEpochMilli()));
        item.put("expiresAt", n(file.createdAt().plus(urlTtl).plus(ABANDONED_AFTER).getEpochSecond()));
        call("PutItem", () -> dynamo.putItem(r -> r.tableName(table).item(item)
                .conditionExpression("attribute_not_exists(id)")));
    }

    /** The record with this id; empty if there's none (never created, or expired unused). */
    public Optional<FileRecord> find(String id) {
        Map<String, AttributeValue> item = call("GetItem",
                () -> dynamo.getItem(r -> r.tableName(table).key(key(id)).consistentRead(true))).item();
        return item == null || item.isEmpty() ? Optional.empty() : Optional.of(toRecord(item));
    }

    /** Deletes a record whose upload failed before anything was stored. */
    public void delete(String id) {
        call("DeleteItem", () -> dynamo.deleteItem(r -> r.tableName(table).key(key(id))));
    }

    /**
     * The object landed in quarantine at {@code at}: AWAITING_UPLOAD becomes SCANNING. If the file
     * is already past that (its scan result came first), only the time is filled in.
     */
    public Optional<Update> markSubmitted(String id, Instant at) {
        try {
            return Optional.of(new Update(true, update("markSubmitted", UpdateItemRequest.builder().key(key(id))
                    .updateExpression("SET #status = :scanning, submittedAt = :at, scanQueue = :scanning REMOVE expiresAt")
                    .conditionExpression("#status = :awaiting")
                    .expressionAttributeValues(Map.of(":scanning", s(SCANNING), ":at", n(at.toEpochMilli()),
                            ":awaiting", s(FileStatus.AWAITING_UPLOAD.name()))))));
        } catch (ConditionalCheckFailedException alreadyPast) {
            return fillIn(id, "submittedAt", at);
        }
    }

    /** Records GuardDuty's result: SCANNING (or AWAITING_UPLOAD) moves to the result's status. */
    public Optional<Update> recordScan(String id, ScanResult result, Instant at, String reason) {
        Map<String, AttributeValue> values = new HashMap<>(Map.of(
                ":next", s(result.next().name()), ":result", s(result.name()), ":at", n(at.toEpochMilli()),
                ":awaiting", s(FileStatus.AWAITING_UPLOAD.name()), ":scanning", s(FileStatus.SCANNING.name())));
        String set = "SET #status = :next, scanResult = :result, scannedAt = :at";
        if (reason != null) {
            set += ", reason = :reason";
            values.put(":reason", s(reason));
        }
        try {
            return Optional.of(new Update(true, update("recordScan", UpdateItemRequest.builder().key(key(id))
                    .updateExpression(set + " REMOVE scanQueue, expiresAt")
                    .conditionExpression("#status IN (:awaiting, :scanning)")
                    .expressionAttributeValues(values))));
        } catch (ConditionalCheckFailedException alreadyRecorded) {
            return find(id).map(file -> new Update(false, file));
        }
    }

    /** PROMOTING ends: AVAILABLE (with the time) or REJECTED (with the reason). */
    public Optional<Update> finishPromotion(String id, FileStatus outcome, Instant at, String reason) {
        if (outcome != FileStatus.AVAILABLE && outcome != FileStatus.REJECTED) {
            throw new IllegalArgumentException("a promotion ends AVAILABLE or REJECTED, not " + outcome);
        }
        String set = outcome == FileStatus.AVAILABLE ? "SET #status = :outcome, availableAt = :value"
                : "SET #status = :outcome, reason = :value";
        AttributeValue value = outcome == FileStatus.AVAILABLE ? n(at.toEpochMilli()) : s(reason);
        try {
            return Optional.of(new Update(true, update("finishPromotion", UpdateItemRequest.builder().key(key(id))
                    .updateExpression(set)
                    .conditionExpression("#status = :promoting")
                    .expressionAttributeValues(Map.of(":outcome", s(outcome.name()), ":value", value,
                            ":promoting", s(FileStatus.PROMOTING.name()))))));
        } catch (ConditionalCheckFailedException alreadyFinished) {
            return find(id).map(file -> new Update(false, file));
        }
    }

    /** How many files are being scanned now, across all instances. Reads keys only, from the sparse index. */
    public long countScanning() {
        long count = 0;
        Map<String, AttributeValue> start = null;
        do {
            QueryResponse page = query("countScanning", scanningQuery().select(Select.COUNT).exclusiveStartKey(start));
            count += page.count();
            start = page.hasLastEvaluatedKey() && !page.lastEvaluatedKey().isEmpty() ? page.lastEvaluatedKey() : null;
        } while (start != null);
        return count;
    }

    /** When the longest-waiting scan started; empty when none is in progress. */
    public Optional<Instant> oldestScanning() {
        QueryResponse page = query("oldestScanning", scanningQuery().limit(1));
        return page.items().stream().findFirst().map(item -> instant(item, "submittedAt"));
    }

    /** Up to {@code limit} files submitted before {@code cutoff} and still scanning, oldest first. */
    public List<String> scanningSince(Instant cutoff, int limit) {
        QueryResponse page = query("scanningSince", QueryRequest.builder().tableName(table).indexName(SCANNING_INDEX)
                .keyConditionExpression("scanQueue = :scanning AND submittedAt < :cutoff")
                .expressionAttributeValues(Map.of(":scanning", s(SCANNING), ":cutoff", n(cutoff.toEpochMilli())))
                .limit(limit));
        List<String> ids = new ArrayList<>(page.items().size());
        for (Map<String, AttributeValue> item : page.items()) {
            ids.add(item.get("id").s());
        }
        return ids;
    }

    /** Claims the one report of an overdue scan, so only one instance logs it; false if already reported or finished. */
    public boolean claimOverdueReport(String id, Instant at) {
        try {
            update("claimOverdueReport", UpdateItemRequest.builder().key(key(id))
                    .updateExpression("SET overdueReportedAt = :at")
                    .conditionExpression("#status = :scanning AND attribute_not_exists(overdueReportedAt)")
                    .expressionAttributeValues(Map.of(":at", n(at.toEpochMilli()),
                            ":scanning", s(FileStatus.SCANNING.name()))));
            return true;
        } catch (ConditionalCheckFailedException alreadyReported) {
            return false;
        }
    }

    private QueryRequest.Builder scanningQuery() {
        return QueryRequest.builder().tableName(table).indexName(SCANNING_INDEX)
                .keyConditionExpression("scanQueue = :scanning")
                .expressionAttributeValues(Map.of(":scanning", s(SCANNING)))
                .scanIndexForward(true);
    }

    /** Sets {@code attribute} if it's missing, on a record past the step that sets it. */
    private Optional<Update> fillIn(String id, String attribute, Instant at) {
        try {
            return Optional.of(new Update(false, update("fillIn", UpdateItemRequest.builder().key(key(id))
                    .updateExpression("SET " + attribute + " = if_not_exists(" + attribute + ", :at)")
                    .conditionExpression("attribute_exists(id) AND #status <> :status")
                    .expressionAttributeValues(Map.of(":at", n(at.toEpochMilli()),
                            ":status", s(FileStatus.AWAITING_UPLOAD.name()))))));
        } catch (ConditionalCheckFailedException unknown) {
            return Optional.empty();
        }
    }

    private FileRecord update(String operation, UpdateItemRequest.Builder request) {
        UpdateItemRequest built = request.tableName(table).returnValues(ReturnValue.ALL_NEW)
                .expressionAttributeNames(Map.of("#status", "status")).build();
        return toRecord(call(operation, () -> dynamo.updateItem(built)).attributes());
    }

    private QueryResponse query(String operation, QueryRequest.Builder request) {
        return call(operation, () -> dynamo.query(request.build()));
    }

    private static <T> T call(String operation, Supplier<T> call) {
        try {
            return call.get();
        } catch (ConditionalCheckFailedException e) {
            throw e;
        } catch (SdkException e) {
            throw new DependencyUnavailableException("dynamodb", operation, e);
        }
    }

    private static FileRecord toRecord(Map<String, AttributeValue> item) {
        return new FileRecord(
                item.get("id").s(),
                item.get("fileName").s(),
                FileType.valueOf(item.get("fileType").s()),
                Long.parseLong(item.get("size").n()),
                UploadMethod.valueOf(item.get("method").s()),
                FileStatus.valueOf(item.get("status").s()),
                instant(item, "createdAt"),
                instant(item, "submittedAt"),
                instant(item, "scannedAt"),
                instant(item, "availableAt"),
                item.containsKey("scanResult") ? ScanResult.valueOf(item.get("scanResult").s()) : null,
                item.containsKey("reason") ? item.get("reason").s() : null);
    }

    private static Instant instant(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        return value == null ? null : Instant.ofEpochMilli(Long.parseLong(value.n()));
    }

    private static Map<String, AttributeValue> key(String id) {
        return Map.of("id", s(id));
    }

    private static AttributeValue s(String value) {
        return AttributeValue.fromS(value);
    }

    private static AttributeValue n(long value) {
        return AttributeValue.fromN(Long.toString(value));
    }
}
