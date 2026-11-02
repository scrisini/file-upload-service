package com.crisinis.fileuploadservice.events;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import com.crisinis.fileuploadservice.model.ScanResult;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the EventBridge events EventBridge rules put on the scan queue (docs/deployment.md "AWS
 * resources"). The formats are AWS's: S3's "Object Created" and GuardDuty's "GuardDuty Malware
 * Protection Object Scan Result". Anything else becomes {@link ScanEvent.Unrecognised}.
 */
@Component
public class ScanEventParser {

    static final String OBJECT_CREATED = "Object Created";
    static final String SCAN_RESULT = "GuardDuty Malware Protection Object Scan Result";

    private final JsonMapper json;

    public ScanEventParser(JsonMapper json) {
        this.json = json;
    }

    public ScanEvent parse(String body) {
        JsonNode event;
        try {
            event = json.readTree(body);
        } catch (JacksonException e) {
            return new ScanEvent.Unrecognised("not JSON");
        }
        String type = event.path("detail-type").asString("");
        try {
            return switch (type) {
                case OBJECT_CREATED -> objectCreated(event);
                case SCAN_RESULT -> scanCompleted(event);
                default -> new ScanEvent.Unrecognised("detail-type '" + type + "'");
            };
        } catch (DateTimeParseException e) {
            return new ScanEvent.Unrecognised(type + " with an invalid time");
        }
    }

    private static ScanEvent objectCreated(JsonNode event) {
        JsonNode detail = event.path("detail");
        String bucket = detail.path("bucket").path("name").asString("");
        String key = detail.path("object").path("key").asString("");
        if (bucket.isEmpty() || key.isEmpty()) {
            return new ScanEvent.Unrecognised("Object Created without a bucket or key");
        }
        return new ScanEvent.ObjectCreated(bucket, key, detail.path("object").path("size").asLong(-1),
                detail.path("object").path("etag").asString(null), time(event));
    }

    private static ScanEvent scanCompleted(JsonNode event) {
        JsonNode detail = event.path("detail");
        JsonNode object = detail.path("s3ObjectDetails");
        String bucket = object.path("bucketName").asString("");
        String key = object.path("objectKey").asString("");
        String status = detail.path("scanResultDetails").path("scanResultStatus").asString("");
        var result = ScanResult.of(status);
        if (bucket.isEmpty() || key.isEmpty() || result.isEmpty()) {
            return new ScanEvent.Unrecognised("scan result without a bucket, key or known status ('" + status + "')");
        }
        return new ScanEvent.ScanCompleted(bucket, key, object.path("eTag").asString(null), result.get(),
                reason(detail.path("scanResultDetails")), time(event));
    }

    /** The first threat's name (GuardDuty reports one), or the status reasons; null when neither is given. */
    private static String reason(JsonNode details) {
        for (JsonNode threat : details.path("threats")) {
            String name = threat.path("name").asString("");
            if (!name.isEmpty()) {
                return name;
            }
        }
        List<String> reasons = new ArrayList<>();
        for (JsonNode reason : details.path("statusReasons")) {
            reasons.add(reason.asString(""));
        }
        return reasons.isEmpty() ? null : String.join(",", reasons);
    }

    private static Instant time(JsonNode event) {
        return Instant.parse(event.path("time").asString(""));
    }
}
