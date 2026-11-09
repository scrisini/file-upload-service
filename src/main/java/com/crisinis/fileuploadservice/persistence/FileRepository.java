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
import com.crisinis.fileuploadservice.model.ExtractionKind;
import com.crisinis.fileuploadservice.model.ExtractionStatus;
import com.crisinis.fileuploadservice.model.FileStatus;
import com.crisinis.fileuploadservice.model.FileType;
import com.crisinis.fileuploadservice.model.ScanResult;
import com.crisinis.fileuploadservice.model.UploadMethod;
import com.crisinis.fileuploadservice.service.DependencyUnavailableException;
import io.micrometer.observation.annotation.Observed;
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

    /**
     * The work a file in flight waits for, kept in {@code stage} for the in-flight index. SCANNING
     * and PROMOTING match the file's status; EXTRACTING is an AVAILABLE file whose extraction is
     * PENDING.
     */
    public enum Stage {
        SCANNING, PROMOTING, EXTRACTING
    }

    /**
     * An extraction a worker claimed: the file, and which attempt this is (1 for the first).
     */
    public record Claim(FileRecord file, int attempt) {
    }

    /**
     * An extraction as stored, for internal readers; the policy is the JSON the service wrote.
     */
    public record StoredExtraction(String fileId, FileStatus fileStatus, ExtractionKind kind, ExtractionStatus status, Instant requestedAt,
                                   Instant completedAt, int attempts, String modelId, String policyJson, String failure) {
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
    @Observed(name = "file-upload-service.file.create", contextualName = "file-repository#createIfAbsent")
    public Update createIfAbsent(FileRecord file) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("id", s(file.id()));
        item.put("fileName", s(file.fileName()));
        item.put("fileType", s(file.type().name()));
        item.put("size", n(file.size()));
        item.put("method", s(file.method().name()));
        item.put("status", s(file.status().name()));
        item.put("createdAt", n(file.createdAt().toEpochMilli()));
        if (file.extraction() != null) {
            item.put("extraction", s(file.extraction().name()));
        }
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

    /**
     * PROMOTING ends: AVAILABLE (with the time) or REJECTED (with the reason). An AVAILABLE file
     * whose uploader opted in to extraction enters EXTRACTING in the same write, so no crash can lose
     * the extraction between the two.
     *
     * @param extract whether the file is to be extracted once AVAILABLE
     */
    public Optional<Update> finishPromotion(String id, FileStatus outcome, Instant at, String reason, boolean extract) {
        if (outcome != FileStatus.AVAILABLE && outcome != FileStatus.REJECTED) {
            throw new IllegalArgumentException("a promotion ends AVAILABLE or REJECTED, not " + outcome);
        }
        Map<String, AttributeValue> values = new HashMap<>(Map.of(":outcome", s(outcome.name()),
                ":promoting", s(FileStatus.PROMOTING.name())));
        String expression;
        if (outcome == FileStatus.REJECTED) {
            expression = "SET #status = :outcome, reason = :reason REMOVE stage, stageSince";
            values.put(":reason", s(reason));
        } else if (extract) {
            expression = "SET #status = :outcome, availableAt = :at, stage = :extracting, stageSince = :at, "
                    + "extractionStatus = :pending, extractionRequestedAt = :at, extractionAttempts = :zero";
            values.putAll(Map.of(":at", n(at.toEpochMilli()), ":extracting", s(Stage.EXTRACTING.name()),
                    ":pending", s(ExtractionStatus.PENDING.name()), ":zero", n(0)));
        } else {
            expression = "SET #status = :outcome, availableAt = :at REMOVE stage, stageSince";
            values.put(":at", n(at.toEpochMilli()));
        }
        try {
            return Optional.of(new Update(true, update("finishPromotion", UpdateItemRequest.builder().key(key(id))
                    .updateExpression(expression)
                    .conditionExpression("#status = :promoting")
                    .expressionAttributeValues(values))));
        } catch (ConditionalCheckFailedException alreadyFinished) {
            return find(id).map(file -> new Update(false, file));
        }
    }

    /**
     * Claims a pending extraction for {@code lease}: no other worker takes it until the lease ends,
     * and if this one dies, the lease ends and another does. Counts the attempt.
     *
     * @return the claim; empty if it's no longer pending or another worker holds it
     */
    public Optional<Claim> claimExtraction(String id, Instant now, Duration lease) {
        try {
            Map<String, AttributeValue> item = rawUpdate("claimExtraction", UpdateItemRequest.builder().key(key(id))
                    .updateExpression("SET leaseUntil = :until ADD extractionAttempts :one")
                    .conditionExpression("stage = :extracting AND (attribute_not_exists(leaseUntil) OR leaseUntil <= :now)")
                    .expressionAttributeValues(Map.of(":until", n(now.plus(lease).toEpochMilli()), ":one", n(1),
                            ":extracting", s(Stage.EXTRACTING.name()), ":now", n(now.toEpochMilli()))));
            return Optional.of(new Claim(toRecord(item), Integer.parseInt(item.get("extractionAttempts").n())));
        } catch (ConditionalCheckFailedException takenOrDone) {
            return Optional.empty();
        }
    }

    /** Stores the result and ends the extraction: COMPLETED. False if it had already ended. */
    public boolean completeExtraction(String id, String modelId, String policyJson, Instant at) {
        return endExtraction("completeExtraction", id, "extractionStatus = :status, extractionModel = :model, "
                        + "extractionResult = :result, extractionCompletedAt = :at",
                Map.of(":status", s(ExtractionStatus.COMPLETED.name()), ":model", s(modelId), ":result", s(policyJson),
                        ":at", n(at.toEpochMilli())));
    }

    /** Ends the extraction: FAILED, saying why. False if it had already ended. */
    public boolean failExtraction(String id, String failure, Instant at) {
        return endExtraction("failExtraction", id, "extractionStatus = :status, extractionFailure = :failure, "
                        + "extractionCompletedAt = :at",
                Map.of(":status", s(ExtractionStatus.FAILED.name()), ":failure", s(failure), ":at", n(at.toEpochMilli())));
    }

    /** After a temporary failure: no worker retries the extraction before {@code retryAt}. */
    public void deferExtraction(String id, Instant retryAt, String lastError) {
        try {
            rawUpdate("deferExtraction", UpdateItemRequest.builder().key(key(id))
                    .updateExpression("SET leaseUntil = :retry, extractionLastError = :error")
                    .conditionExpression("stage = :extracting")
                    .expressionAttributeValues(Map.of(":retry", n(retryAt.toEpochMilli()), ":error", s(lastError),
                            ":extracting", s(Stage.EXTRACTING.name()))));
        } catch (ConditionalCheckFailedException ended) {
            // another worker finished it meanwhile: nothing to defer
        }
    }

    /**
     * Hands a claimed extraction back on shutdown: its lease ends now, so any instance can take it
     * at once rather than when the lease would have run out, and the attempt doesn't count.
     *
     * @return false if it had already ended (the call finished meanwhile)
     */
    public boolean releaseExtraction(String id, Instant now) {
        try {
            rawUpdate("releaseExtraction", UpdateItemRequest.builder().key(key(id))
                    .updateExpression("SET leaseUntil = :now ADD extractionAttempts :minusOne")
                    .conditionExpression("stage = :extracting AND extractionAttempts > :zero")
                    .expressionAttributeValues(Map.of(":now", n(now.toEpochMilli()), ":minusOne", n(-1), ":zero", n(0),
                            ":extracting", s(Stage.EXTRACTING.name()))));
            return true;
        } catch (ConditionalCheckFailedException ended) {
            return false;
        }
    }

    /** The file's extraction; empty if the file doesn't exist or wasn't to be extracted. */
    public Optional<StoredExtraction> findExtraction(String id) {
        Map<String, AttributeValue> item = call("GetItem",
                () -> dynamo.getItem(r -> r.tableName(table).key(key(id)).consistentRead(true))).item();
        if (item == null || !item.containsKey("extraction")) {
            return Optional.empty();
        }
        return Optional.of(new StoredExtraction(id, FileStatus.valueOf(item.get("status").s()),
                ExtractionKind.valueOf(item.get("extraction").s()),
                item.containsKey("extractionStatus") ? ExtractionStatus.valueOf(item.get("extractionStatus").s()) : null,
                instant(item, "extractionRequestedAt"), instant(item, "extractionCompletedAt"),
                item.containsKey("extractionAttempts") ? Integer.parseInt(item.get("extractionAttempts").n()) : 0,
                string(item, "extractionModel"), string(item, "extractionResult"), string(item, "extractionFailure")));
    }

    /** How many files wait in {@code stage}, across all instances. Reads keys only, from the sparse index. */
    public long countInStage(Stage stage) {
        long count = 0;
        Map<String, AttributeValue> start = null;
        do {
            QueryResponse page = query("countInStage", stageQuery(stage).select(Select.COUNT).exclusiveStartKey(start));
            count += page.count();
            start = page.hasLastEvaluatedKey() && !page.lastEvaluatedKey().isEmpty() ? page.lastEvaluatedKey() : null;
        } while (start != null);
        return count;
    }

    private boolean endExtraction(String operation, String id, String set, Map<String, AttributeValue> values) {
        Map<String, AttributeValue> all = new HashMap<>(values);
        all.put(":extracting", s(Stage.EXTRACTING.name()));
        try {
            rawUpdate(operation, UpdateItemRequest.builder().key(key(id))
                    .updateExpression("SET " + set + " REMOVE stage, stageSince, leaseUntil")
                    .conditionExpression("stage = :extracting")
                    .expressionAttributeValues(all));
            return true;
        } catch (ConditionalCheckFailedException ended) {
            return false;
        }
    }

    /** How many files are being scanned now, across all instances. Reads keys only, from the sparse index. */
    public long countScanning() {
        return countInStage(Stage.SCANNING);
    }

    /** When the longest-waiting scan started; empty when none is in progress. */
    public Optional<Instant> oldestScanning() {
        QueryResponse page = query("oldestScanning", stageQuery(Stage.SCANNING).limit(1));
        return page.items().stream().findFirst().map(item -> instant(item, "stageSince"));
    }

    /**
     * Up to {@code limit} files that entered {@code stage} before {@code cutoff} and are still in it,
     * oldest first: overdue scans, promotions a crash interrupted, extractions waiting for a worker.
     */
    public List<String> inStageSince(Stage stage, Instant cutoff, int limit) {
        QueryResponse page = query("inStageSince", QueryRequest.builder().tableName(table).indexName(IN_FLIGHT_INDEX)
                .keyConditionExpression("stage = :stage AND stageSince < :cutoff")
                .expressionAttributeValues(Map.of(":stage", s(stage.name()), ":cutoff", n(cutoff.toEpochMilli())))
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

    private QueryRequest.Builder stageQuery(Stage stage) {
        return QueryRequest.builder().tableName(table).indexName(IN_FLIGHT_INDEX)
                .keyConditionExpression("stage = :stage")
                .expressionAttributeValues(Map.of(":stage", s(stage.name())))
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

    /** An update whose expressions don't name {@code #status}; returns the item as it is now. */
    private Map<String, AttributeValue> rawUpdate(String operation, UpdateItemRequest.Builder request) {
        UpdateItemRequest built = request.tableName(table).returnValues(ReturnValue.ALL_NEW).build();
        return call(operation, () -> dynamo.updateItem(built)).attributes();
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
                item.containsKey("scannedETag") ? item.get("scannedETag").s() : null,
                item.containsKey("extraction") ? ExtractionKind.valueOf(item.get("extraction").s()) : null);
    }

    private static String string(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        return value == null ? null : value.s();
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
