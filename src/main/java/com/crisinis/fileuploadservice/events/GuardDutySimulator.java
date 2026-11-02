package com.crisinis.fileuploadservice.events;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.crisinis.fileuploadservice.config.FileUploadServiceProperties;
import com.crisinis.fileuploadservice.logging.LogEvent;
import com.crisinis.fileuploadservice.model.ScanResult;
import com.crisinis.fileuploadservice.storage.FileStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.sqs.SqsClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Local development only ({@code scan.simulator.enabled}; production mode refuses it): stands in for
 * S3's EventBridge notifications and GuardDuty, which no local emulator offers. It watches the
 * quarantine bucket and puts the same events on the scan queue that AWS would: "Object Created" when
 * an upload appears, then a scan result {@code scan.simulator.delay} later. A file containing the
 * EICAR test string is reported {@code THREATS_FOUND}; anything else {@code NO_THREATS_FOUND}.
 *
 * <p>Keeps what it has reported in memory, which is fine for one developer's instance and is why it
 * must never run in production.
 */
@Slf4j
@Component
public class GuardDutySimulator {

    private static final byte[] EICAR = "EICAR-STANDARD-ANTIVIRUS-TEST-FILE".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_TRACKED = 10_000;
    private static final int LIST_LIMIT = 200;

    /** An object the simulator has announced, and whether its result has been sent. */
    private static final class Tracked {
        final Instant seenAt;
        boolean resultSent;

        Tracked(Instant seenAt) {
            this.seenAt = seenAt;
        }
    }

    private final FileStorage storage;
    private final SqsClient sqs;
    private final JsonMapper json;
    private final Clock clock;
    private final FileUploadServiceProperties properties;
    private final Map<String, Tracked> tracked = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Tracked> eldest) {
            return size() > MAX_TRACKED;
        }
    };

    public GuardDutySimulator(FileStorage storage, SqsClient sqs, JsonMapper json, Clock clock,
                              FileUploadServiceProperties properties) {
        this.storage = storage;
        this.sqs = sqs;
        this.json = json;
        this.clock = clock;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    void announce() {
        if (enabled()) {
            LogEvent.SCAN_SIMULATOR_ON.at(log).with("delay", properties.scan().simulator().delay()).log();
        }
    }

    @Scheduled(fixedDelay = 1000)
    synchronized void tick() {
        if (!enabled()) {
            return;
        }
        Instant now = clock.instant();
        List<S3Object> objects = storage.listQuarantine(LIST_LIMIT);
        for (S3Object object : objects) {
            Tracked seen = tracked.get(object.key());
            if (seen == null) {
                send(objectCreated(object));
                tracked.put(object.key(), new Tracked(now));
            } else if (!seen.resultSent && !now.isBefore(seen.seenAt.plus(properties.scan().simulator().delay()))) {
                send(scanResult(object, now));
                seen.resultSent = true;
            }
        }
    }

    private boolean enabled() {
        return properties.scan().simulator().enabled() && properties.scan().enabled();
    }

    private Map<String, Object> objectCreated(S3Object object) {
        return Map.of(
                "detail-type", ScanEventParser.OBJECT_CREATED,
                "source", "aws.s3",
                "time", object.lastModified().toString(),
                "detail", Map.of(
                        "bucket", Map.of("name", properties.storage().quarantineBucket()),
                        "object", Map.of("key", object.key(), "size", object.size(), "etag", unquoted(object.eTag())),
                        "reason", "PutObject"));
    }

    private Map<String, Object> scanResult(S3Object object, Instant now) {
        String id = object.key().substring(properties.storage().quarantinePrefix().length());
        boolean infected = storage.readAll(id).map(GuardDutySimulator::containsEicar).orElse(false);
        ScanResult result = infected ? ScanResult.THREATS_FOUND : ScanResult.NO_THREATS_FOUND;
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("scanResultStatus", result.name());
        details.put("threats", infected ? List.of(Map.of("name", "EICAR-Test-File (not a virus)")) : null);
        details.put("statusReasons", null);
        return Map.of(
                "detail-type", ScanEventParser.SCAN_RESULT,
                "source", "aws.guardduty",
                "time", now.toString(),
                "detail", Map.of(
                        "scanStatus", "COMPLETED",
                        "resourceType", "S3_OBJECT",
                        "s3ObjectDetails", Map.of(
                                "bucketName", properties.storage().quarantineBucket(),
                                "objectKey", object.key(),
                                "eTag", unquoted(object.eTag()),
                                "s3Throttled", false),
                        "scanResultDetails", details));
    }

    private void send(Map<String, Object> event) {
        String body = json.writeValueAsString(event);
        sqs.sendMessage(m -> m.queueUrl(properties.scan().queueUrl()).messageBody(body));
    }

    private static boolean containsEicar(byte[] content) {
        outer:
        for (int i = 0; i <= content.length - EICAR.length; i++) {
            for (int j = 0; j < EICAR.length; j++) {
                if (content[i + j] != EICAR[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static String unquoted(String eTag) {
        return eTag == null ? null : eTag.replace("\"", "");
    }
}
