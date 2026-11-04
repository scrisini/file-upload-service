package com.crisinis.fileuploadservice.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.crisinis.fileuploadservice.model.FileType;
import com.crisinis.fileuploadservice.storage.FileStorage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Real GuardDuty Malware Protection scans uploads to the quarantine bucket and tags its result.
 * Opt-in on top of the S3 settings ({@link RealAws#GUARDDUTY}{@code =true}): each scan takes from
 * seconds to minutes and is charged. Needs the protection plan on the bucket with tagging on.
 */
class GuardDutyScanIT {

    static final Duration SCAN_TIMEOUT = Duration.ofMinutes(5);
    static final String EICAR = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";

    static S3Client s3;
    static FileStorage storage;
    static String quarantine;

    @BeforeAll
    static void connect() {
        assumeTrue("true".equalsIgnoreCase(System.getenv(RealAws.GUARDDUTY)),
                RealAws.GUARDDUTY + "=true runs the GuardDuty scans (slow, charged)");
        quarantine = RealAws.require(RealAws.QUARANTINE_BUCKET);
        s3 = S3Client.builder().region(RealAws.region()).build();
        storage = new FileStorage(s3, S3Presigner.builder().region(RealAws.region()).build(), RealAws.properties(Map.of(
                "file-upload-service.storage.quarantine-bucket", quarantine,
                "file-upload-service.storage.clean-bucket", RealAws.require(RealAws.CLEAN_BUCKET))));
    }

    @AfterAll
    static void close() {
        if (s3 != null) {
            s3.close();
        }
    }

    @Test
    void aCleanFileIsTaggedNoThreatsFound() throws Exception {
        assertThat(scan(PolicyDocument.png("IT-CLEAN"))).isEqualTo("NO_THREATS_FOUND");
    }

    @Test
    void theEicarTestFileIsTaggedThreatsFound() throws Exception {
        byte[] png = PolicyDocument.png("IT-EICAR");
        byte[] infected = new byte[png.length + EICAR.length()];
        System.arraycopy(png, 0, infected, 0, png.length);
        System.arraycopy(EICAR.getBytes(StandardCharsets.US_ASCII), 0, infected, png.length, EICAR.length());

        assertThat(scan(infected)).isEqualTo("THREATS_FOUND");
    }

    /** Uploads to quarantine and waits for GuardDuty's tag; removes the object. */
    private String scan(byte[] content) throws InterruptedException {
        String id = UUID.randomUUID().toString();
        try {
            storage.store(id, FileType.PNG, content.length, new ByteArrayInputStream(content));
            long deadline = System.nanoTime() + SCAN_TIMEOUT.toNanos();
            while (System.nanoTime() < deadline) {
                Optional<String> tag = storage.scanStatusTag(id);
                if (tag.isPresent()) {
                    return tag.get();
                }
                Thread.sleep(5000);
            }
            throw new AssertionError("no GuardDuty result within " + SCAN_TIMEOUT
                    + ": is the protection plan ACTIVE on " + quarantine + ", with tagging on?");
        } finally {
            s3.deleteObject(d -> d.bucket(quarantine).key(storage.quarantineKey(id)));
        }
    }
}
