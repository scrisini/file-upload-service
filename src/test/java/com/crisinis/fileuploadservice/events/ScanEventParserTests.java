package com.crisinis.fileuploadservice.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import com.crisinis.fileuploadservice.model.ScanResult;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The samples are AWS's own, from the GuardDuty and S3 EventBridge documentation. */
class ScanEventParserTests {

    private final ScanEventParser parser = new ScanEventParser(JsonMapper.builder().build());

    static String scanResult(String scanStatus, String result, String threats, String reasons) {
        return """
                {"version":"0","id":"72c7d362-737a-6dce-fc78-9e27a0171419",
                 "detail-type":"GuardDuty Malware Protection Object Scan Result","source":"aws.guardduty",
                 "account":"111122223333","time":"2024-02-28T01:01:01Z","region":"us-east-1",
                 "resources":["arn:aws:guardduty:us-east-1:111122223333:malware-protection-plan/b4c7f464ab3a4EXAMPLE"],
                 "detail":{"schemaVersion":"1.0","scanStatus":"%s","resourceType":"S3_OBJECT",
                   "s3ObjectDetails":{"bucketName":"file-upload-quarantine",
                     "objectKey":"uploads/0b8e2d4a-55a3-4d0e-9f53-3e1b5f0a7c11",
                     "eTag":"0e9eeec810ad8b61d69112c15c2a5hb6","versionId":"d41d8cd98f00b204e9800998eEXAMPLE",
                     "s3Throttled":false},
                   "scanResultDetails":{"scanResultStatus":"%s","threats":%s,"statusReasons":%s}}}
                """.formatted(scanStatus, result, threats, reasons);
    }

    @Test
    void readsACleanResult() {
        ScanEvent event = parser.parse(scanResult("COMPLETED", "NO_THREATS_FOUND", "null", "null"));

        assertThat(event).isEqualTo(new ScanEvent.ScanCompleted("file-upload-quarantine",
                "uploads/0b8e2d4a-55a3-4d0e-9f53-3e1b5f0a7c11", "0e9eeec810ad8b61d69112c15c2a5hb6",
                ScanResult.NO_THREATS_FOUND, null, Instant.parse("2024-02-28T01:01:01Z")));
    }

    @Test
    void keepsTheThreatsName() {
        ScanEvent event = parser.parse(scanResult("COMPLETED", "THREATS_FOUND", """
                [{"name":"EICAR-Test-File (not a virus)","source":"AMAZON",
                  "itemDetails":[{"hash":"e3b0c442","itemPath":"uploads/x"}]}]""", "null"));

        assertThat(event).isInstanceOfSatisfying(ScanEvent.ScanCompleted.class, completed -> {
            assertThat(completed.result()).isEqualTo(ScanResult.THREATS_FOUND);
            assertThat(completed.reason()).isEqualTo("EICAR-Test-File (not a virus)");
        });
    }

    @Test
    void keepsTheReasonsForASkippedScan() {
        ScanEvent event = parser.parse(scanResult("SKIPPED", "UNSUPPORTED", "null", "[\"PASSWORD_PROTECTED\"]"));

        assertThat(event).isInstanceOfSatisfying(ScanEvent.ScanCompleted.class, completed -> {
            assertThat(completed.result()).isEqualTo(ScanResult.UNSUPPORTED);
            assertThat(completed.reason()).isEqualTo("PASSWORD_PROTECTED");
        });
    }

    @Test
    void readsObjectCreated() {
        ScanEvent event = parser.parse("""
                {"version":"0","id":"17793124-05d4-b198-2fde-7ededc63b103","detail-type":"Object Created",
                 "source":"aws.s3","account":"111122223333","time":"2021-11-12T00:00:00Z","region":"ca-central-1",
                 "resources":["arn:aws:s3:::file-upload-quarantine"],
                 "detail":{"version":"0","bucket":{"name":"file-upload-quarantine"},
                   "object":{"key":"uploads/0b8e2d4a-55a3-4d0e-9f53-3e1b5f0a7c11","size":5,
                     "etag":"b1946ac92492d2347c6235b4d2611184","sequencer":"00617F08299329D189"},
                   "request-id":"N4N7GDK58NMKJ12R","requester":"123456789012","source-ip-address":"1.2.3.4",
                   "reason":"PutObject"}}
                """);

        assertThat(event).isEqualTo(new ScanEvent.ObjectCreated("file-upload-quarantine",
                "uploads/0b8e2d4a-55a3-4d0e-9f53-3e1b5f0a7c11", 5, "b1946ac92492d2347c6235b4d2611184",
                Instant.parse("2021-11-12T00:00:00Z")));
    }

    @Test
    void anythingElseIsUnrecognised() {
        assertThat(parser.parse("not json")).isInstanceOf(ScanEvent.Unrecognised.class);
        assertThat(parser.parse("{\"detail-type\":\"Object Deleted\",\"time\":\"2024-02-28T01:01:01Z\"}"))
                .isEqualTo(new ScanEvent.Unrecognised("detail-type 'Object Deleted'"));
        assertThat(parser.parse(scanResult("COMPLETED", "SOMETHING_NEW", "null", "null")))
                .isInstanceOf(ScanEvent.Unrecognised.class);
        assertThat(parser.parse(scanResult("COMPLETED", "NO_THREATS_FOUND", "null", "null")
                .replace("2024-02-28T01:01:01Z", "yesterday"))).isInstanceOf(ScanEvent.Unrecognised.class);
    }
}
