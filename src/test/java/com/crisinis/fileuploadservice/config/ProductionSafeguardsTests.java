package com.crisinis.fileuploadservice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.mock.env.MockEnvironment;

class ProductionSafeguardsTests {

    /** Everything production mode asks for. */
    private static final Map<String, String> SAFE = Map.of(
            "file-upload-service.mode", "production",
            "file-upload-service.ssm.path", "/service/prod/",
            "file-upload-service.vault.address", "https://vault.internal:8200",
            "file-upload-service.scan.queue-url", "https://sqs.ap-southeast-2.amazonaws.com/123456789012/scan-events",
            "springdoc.api-docs.enabled", "false",
            "springdoc.swagger-ui.enabled", "false",
            "logging.structured.format.console", "ecs");

    @Test
    void developmentModeAllowsLocalDefaults() {
        assertThat(ProductionSafeguards.violations(binder(Map.of()))).isEmpty();
    }

    @Test
    void productionModeAcceptsSafeSettings() {
        assertThat(ProductionSafeguards.violations(binder(SAFE))).isEmpty();
    }

    @Test
    void productionModeNamesEveryUnsafeSetting() {
        List<String> violations = ProductionSafeguards.violations(binder(Map.of("file-upload-service.mode", "PRODUCTION")));

        assertThat(violations).hasSize(5)
                .anySatisfy(v -> assertThat(v).startsWith("file-upload-service.ssm.path"))
                .anySatisfy(v -> assertThat(v).startsWith("file-upload-service.vault.address"))
                .anySatisfy(v -> assertThat(v).startsWith("file-upload-service.scan.queue-url"))
                .anySatisfy(v -> assertThat(v).contains("FILE_UPLOAD_SERVICE_API_DOCS=false"))
                .anySatisfy(v -> assertThat(v).contains("FILE_UPLOAD_SERVICE_LOG_FORMAT=ecs"));
    }

    @Test
    void productionModeRefusesEmulatorsAndTheScanSimulator() {
        Map<String, String> local = new java.util.HashMap<>(SAFE);
        local.put("file-upload-service.aws.s3-endpoint", "http://localhost:9099");
        local.put("file-upload-service.scan.simulator.enabled", "true");
        local.put("file-upload-service.extraction.simulator.enabled", "true");

        assertThat(ProductionSafeguards.violations(binder(local))).hasSize(3)
                .anySatisfy(v -> assertThat(v).startsWith("file-upload-service.extraction.simulator.enabled"))
                .anySatisfy(v -> assertThat(v).startsWith("file-upload-service.aws.*-endpoint"))
                .anySatisfy(v -> assertThat(v).startsWith("file-upload-service.scan.simulator.enabled"))
                .noneSatisfy(v -> assertThat(v).contains("localhost"));
    }

    @Test
    void productionModeRefusesToStartWithUnsafeSettings() {
        MockEnvironment environment = new MockEnvironment().withProperty("file-upload-service.mode", "production");

        assertThatThrownBy(() -> new ProductionSafeguards(environment))
                .hasMessageContaining("FILE_UPLOAD_SERVICE-1005")
                .hasMessageContaining("file-upload-service.ssm.path");
    }

    @Test
    void productionModeStartsWithSafeSettings() {
        MockEnvironment environment = new MockEnvironment();
        SAFE.forEach(environment::setProperty);

        new ProductionSafeguards(environment);
    }

    private static Binder binder(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties));
    }
}
