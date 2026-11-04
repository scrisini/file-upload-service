package com.crisinis.fileuploadservice.integration;

import java.util.HashMap;
import java.util.Map;

import com.crisinis.fileuploadservice.config.FileUploadServiceProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import software.amazon.awssdk.regions.Region;

/**
 * The real AWS account the integration tests use, from {@code FILE_UPLOAD_SERVICE_IT_*} environment
 * variables; credentials from the AWS SDK's default chain ({@code AWS_PROFILE}, SSO, ...). A test
 * that needs a setting that isn't there fails, saying which: these tests exist to touch AWS, so a
 * silent skip would pass without testing anything.
 *
 * <p>Use dedicated test resources: uploads land in the quarantine bucket, where GuardDuty scans them
 * and a deployed service may see their events.
 */
public final class RealAws {

    public static final String REGION = "FILE_UPLOAD_SERVICE_IT_REGION";
    public static final String MODEL_ID = "FILE_UPLOAD_SERVICE_IT_BEDROCK_MODEL_ID";
    public static final String QUARANTINE_BUCKET = "FILE_UPLOAD_SERVICE_IT_QUARANTINE_BUCKET";
    public static final String CLEAN_BUCKET = "FILE_UPLOAD_SERVICE_IT_CLEAN_BUCKET";
    public static final String TABLE = "FILE_UPLOAD_SERVICE_IT_TABLE";
    /** {@code true} to run the GuardDuty scans, which take minutes and cost a scan each. */
    public static final String GUARDDUTY = "FILE_UPLOAD_SERVICE_IT_GUARDDUTY";

    private RealAws() {
    }

    /** The setting's value; fails the test, naming it, if it isn't set. */
    public static String require(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " isn't set: this integration test needs it to reach real AWS "
                    + "(README \"Testing against real AWS\")");
        }
        return value.trim();
    }

    public static Region region() {
        return Region.of(require(REGION));
    }

    /** The service's settings pointing at the test resources, as the application would bind them. */
    public static FileUploadServiceProperties properties(Map<String, String> settings) {
        Map<String, String> all = new HashMap<>(settings);
        all.put("file-upload-service.aws.region", require(REGION));
        return new Binder(new MapConfigurationPropertySource(all)).bindOrCreate("file-upload-service", FileUploadServiceProperties.class);
    }
}
