package com.crisinis.fileuploadservice.config;

import java.util.ArrayList;
import java.util.List;

import com.crisinis.fileuploadservice.logging.LogEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Production mode ({@code file-upload-service.mode=production}) refuses settings that are fine on
 * a laptop but unsafe in production. Checked when the instance starts, which any violation stops
 * ({@link LogEvent#PRODUCTION_SETTINGS_UNSAFE}), and by {@code ConfigCheck} against the stored
 * configuration a restart would read. Outside production mode nothing is checked.
 *
 * <p>Add a rule for every setting whose default is only safe locally: a development credential, a
 * wildcard that opens access, a debugging switch.
 */
@Slf4j
@Component
public class ProductionSafeguards {

    public ProductionSafeguards(Environment environment) {
        List<String> violations = violations(Binder.get(environment));
        if (!violations.isEmpty()) {
            LogEvent.PRODUCTION_SETTINGS_UNSAFE.at(log).with("violations", violations).log();
            throw new IllegalStateException("%s: %s".formatted(
                    LogEvent.PRODUCTION_SETTINGS_UNSAFE.message(), String.join("; ", violations)));
        }
    }

    /**
     * What production mode refuses in these settings, each naming the setting to change; empty when
     * they're safe or the mode isn't production. Names settings, never their values.
     */
    public static List<String> violations(Binder binder) {
        FileUploadServiceProperties properties = binder.bindOrCreate("file-upload-service", FileUploadServiceProperties.class);
        if (properties.mode() != FileUploadServiceProperties.Mode.PRODUCTION) {
            return List.of();
        }
        List<String> violations = new ArrayList<>();
        if (binder.bind("file-upload-service.ssm.path", String.class).orElse("").isBlank()) {
            violations.add("file-upload-service.ssm.path is not set: production settings come from SSM "
                    + "Parameter Store (FILE_UPLOAD_SERVICE_SSM_PATH)");
        }
        if (binder.bind("file-upload-service.vault.address", String.class).orElse("").isBlank()) {
            violations.add("file-upload-service.vault.address is not set: production secrets come from Vault "
                    + "(FILE_UPLOAD_SERVICE_VAULT_ADDRESS)");
        }
        if (properties.aws().overridden()) {
            violations.add("file-upload-service.aws.*-endpoint is set: production talks to AWS itself, not an emulator "
                    + "(FILE_UPLOAD_SERVICE_S3_ENDPOINT, _SQS_ENDPOINT, _DYNAMODB_ENDPOINT)");
        }
        if (!properties.scan().enabled()) {
            violations.add("file-upload-service.scan.queue-url is not set: without GuardDuty's results no upload is "
                    + "ever scanned or promoted (FILE_UPLOAD_SERVICE_SCAN_QUEUE_URL)");
        }
        if (binder.bind("springdoc.api-docs.enabled", Boolean.class).orElse(true)
                || binder.bind("springdoc.swagger-ui.enabled", Boolean.class).orElse(true)) {
            violations.add("springdoc.api-docs.enabled or springdoc.swagger-ui.enabled is on: the API's description "
                    + "and Swagger UI are for development (FILE_UPLOAD_SERVICE_API_DOCS=false)");
        }
        if (properties.scan().simulator().enabled()) {
            violations.add("file-upload-service.scan.simulator.enabled is on: it invents scan results instead of "
                    + "GuardDuty's (FILE_UPLOAD_SERVICE_SCAN_SIMULATOR)");
        }
        if (!"ecs".equals(binder.bind("logging.structured.format.console", String.class).orElse(""))) {
            violations.add("logs are plain text: set FILE_UPLOAD_SERVICE_LOG_FORMAT=ecs for JSON logs");
        }
        return violations;
    }
}
