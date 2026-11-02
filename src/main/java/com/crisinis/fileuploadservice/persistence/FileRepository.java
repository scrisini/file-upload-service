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
 * <p>A file in flight (SCANNING or PROMOTING) also carries {@code stage} (its status) and
 * {@code stageSince} (when it entered it), which put it in the sparse {@code in-flight} index.
 * Counting scans in progress, finding overdue scans and finding promotions a crash interrupted read
 * only that index, never the table. Schema: README "Data".
 *
 * <p>Crash safety (README "Crash safety"): every write here is one atomic, conditional item update,
 * and the record holds everything needed to carry on from it, so a process that dies between any
 * two steps leaves a state another instance can finish.
 */
@Repository
public class FileRepository {

    public static final String IN_FLIGHT_INDEX = "in-flight";
    /**
     * A record whose upload never lands is deleted by DynamoDB's TTL this long after its URL expires.
     * It must outlast every event still on its way: SQS keeps events 4 days and the dead-letter queue
     * up to 14, and an upload's "Object Created" may wait there while the service is down. A record
     * deleted before its event is handled would strand a scanned file in quarantine.
     */
    static final Duration ABANDONED_AFTER = Duration.ofDays(15);

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

    /**
     * Stores a new record, unless one with its id exists: then that one is returned, unchanged. Two
     * attempts with the same idempotency key race here, and exactly one creates the record. A new
     * record expires unless its upload lands ({@code expiresAt}, DynamoDB TTL).
     *
     * <p>Records are never deleted by the service, even when an upload fails: a retry or an event
     * for the same id may still be on its way. Only the TTL removes them.
     *
     * @return changed when this call created the record
     */
    public Update createIfAbsent(FileRecord file) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("id", s(file.id()));
        item.put("fileName", s(file.fileName()));
        item.put("fileType", s(file.type().name()));
        item.put("size", n(file.size()));
        item.put("method", s(file.method().name()));
        item.put("status", s(file.status().name()));
        item.put("createdAt", n(file.createdAt().toEpochMilli()));
        item.put("expiresAt", n(file.createdAt().plus(urlTtl).plus(ABANDONED_AFTER).getEpochSecond()));
        try {
            call("PutItem", () -> dynamo.putItem(r -> r.tableName(table).item(item)
                    .conditionExpression("attribute_not_exists(id)")));
            return new Update(true, file);
        } catch (ConditionalCheckFailedException exists) {
            // another attempt created it first; if TTL removed it in between, this one creates it again
            return find(file.id()).map(existing -> new Update(false, existing)).orElseGet(() -> createIfAbsent(file));
        }
    }

    /** The record with this id; empty if there's none (never created, or expired unused). */
    public Optional<FileRecord> find(String id) {
        Map<String, AttributeValue> item = call("GetItem",
                () -> dynamo.getItem(r -> r.tableName(table).key(key(id)).consistentRead(true))).item();
        return item == null || item.isEmpty() ? Optional.empty() : Optional.of(toRecord(item));
    }

    /**
     * The object landed in quarantine at {@code at}: AWAITING_UPLOAD becomes SCANNING. If the file
     * is already past that (its scan result came first), only the time is filled in.
     */
    public Optional<Update> markSubmitted(String id, Instant at) {
        try {
            return Optional.of(new Update(true, update("markSubmitted", UpdateItemRequest.builder().key(key(id))
                    .updateExpression("SET #status = :scanning, submittedAt = :at, stage = :scanning, stageSince = :at "
                            + "REMOVE expiresAt")
                    .conditionExpression("#status = :awaiting")
                    .expressionAttributeValues(Map.of(":scanning", s(FileStatus.SCANNING.name()), ":at", n(at.toEpochMilli()),
                            ":awaiting", s(FileStatus.AWAITING_UPLOAD.name()))))));
        } catch (ConditionalCheckFailedException alreadyPast) {
            return fillIn(id, "submittedAt", at);
        }
    }

    /**
     * Records GuardDuty's result: SCANNING (or AWAITING_UPLOAD) moves to the result's status. A clean
     * file enters PROMOTING with the ETag GuardDuty scanned, so the promotion can be finished from
     * the record if the process handling it dies.
     *
     * @param scannedETag the version scanned; null when unknown (a result recovered from the object's tag)
     */
    public Optional<Update> recordScan(String id, ScanResult result, Instant at, String reason, String scannedETag) {
        Map<String, AttributeValue> values = new HashMap<>(Map.of(
                ":next", s(result.next().name()), ":result", s(result.name()), ":at", n(at.toEpochMilli()),
                ":awaiting", s(FileStatus.AWAITING_UPLOAD.name()), ":scanning", s(FileStatus.SCANNING.name())));
        String set = "SET #status = :next, scanResult = :result, scannedAt = :at";
        String remove = " REMOVE expiresAt";
        if (result.next() == FileStatus.PROMOTING) {
            set += ", stage = :next, stageSince = :at";
        } else {
            remove += ", stage, stageSince";
        }
        if (reason != null) {
            set += ", reason = :reason";
            values.put(":reason", s(reason));
        }
        if (scannedETag != null) {
            set += ", scannedETag = :etag";
            values.put(":etag", s(scannedETag));
        }
        try {
            return Optional.of(new Update(true, update("recordScan", UpdateItemRequest.builder().key(key(id))
                    .updateExpression(set + remove)
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
                    .updateExpression(set + " REMOVE stage, stageSince")
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
            QueryResponse page = query("countScanning",
                    stageQuery(FileStatus.SCANNING).select(Select.COUNT).exclusiveStartKey(start));
            count += page.count();
            start = page.hasLastEvaluatedKey() && !page.lastEvaluatedKey().isEmpty() ? page.lastEvaluatedKey() : null;
        } while (start != null);
        return count;
    }

    /** When the longest-waiting scan started; empty when none is in progress. */
    public Optional<Instant> oldestScanning() {
        QueryResponse page = query("oldestScanning", stageQuery(FileStatus.SCANNING).limit(1));
        return page.items().stream().findFirst().map(item -> instant(item, "stageSince"));
    }

    /**
     * Up to {@code limit} files that entered {@code stage} (SCANNING or PROMOTING) before {@code cutoff}
     * and are still in it, oldest first: overdue scans, and promotions a crash interrupted.
     */
    public List<String> inStageSince(FileStatus stage, Instant cutoff, int limit) {
        QueryResponse page = query("inStageSince", QueryRequest.builder().tableName(table).indexName(IN_FLIGHT_INDEX)
                .keyConditionExpression("stage = :stage AND stageSince < :cutoff")
                .expressionAttributeValues(Map.of(":stage", s(inFlight(stage).name()), ":cutoff", n(cutoff.toEpochMilli())))
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

    private QueryRequest.Builder stageQuery(FileStatus stage) {
        return QueryRequest.builder().tableName(table).indexName(IN_FLIGHT_INDEX)
                .keyConditionExpression("stage = :stage")
                .expressionAttributeValues(Map.of(":stage", s(inFlight(stage).name())))
                .scanIndexForward(true);
    }

    private static FileStatus inFlight(FileStatus stage) {
        if (stage != FileStatus.SCANNING && stage != FileStatus.PROMOTING) {
            throw new IllegalArgumentException("only SCANNING and PROMOTING files are in the in-flight index, not " + stage);
        }
        return stage;
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
                item.containsKey("reason") ? item.get("reason").s() : null,
                item.containsKey("scannedETag") ? item.get("scannedETag").s() : null);
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
