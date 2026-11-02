package com.crisinis.fileuploadservice.configdata;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.crisinis.fileuploadservice.config.FileUploadServiceProperties;
import com.crisinis.fileuploadservice.support.FakeParameterStore;
import com.crisinis.fileuploadservice.support.FakeVault;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.health.contributor.Status;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * The check against a fake Parameter Store and a fake Vault. The environment is built the way
 * startup leaves it: the settings, then what each store held at startup, Vault taking precedence.
 */
class ConfigCheckTests {

    private static final String SSM_PATH = "/service/test/";
    private static final String SECRET = "file-upload-service.database.password";

    private final Map<String, Object> secret = new ConcurrentHashMap<>(Map.of(SECRET, "secret-v1"));
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private FakeParameterStore ssm;
    private FakeVault vault;

    @BeforeEach
    void start() throws IOException {
        ssm = new FakeParameterStore(Map.of(SSM_PATH + "file-upload-service/outbound-proxy/port", "3128"));
        vault = new FakeVault(secret);
        System.setProperty("aws.accessKeyId", "test");
        System.setProperty("aws.secretAccessKey", "test");
    }

    @AfterEach
    void stop() {
        ssm.close();
        vault.close();
        System.clearProperty("aws.accessKeyId");
        System.clearProperty("aws.secretAccessKey");
    }

    @Test
    void unchangedStoresWouldRestart() {
        ConfigCheck.Result result = check(FakeVault.ROOT_TOKEN, Map.of()).run();

        assertThat(result.restartable()).isTrue();
        assertThat(result.problems()).isEmpty();
        assertThat(result.changed()).isEmpty();
    }

    @Test
    void changedKeysAreReportedByNameOnly() {
        ConfigCheck check = check(FakeVault.ROOT_TOKEN, Map.of());
        ssm.parameters.put(SSM_PATH + "file-upload-service/outbound-proxy/host", "proxy.internal");
        secret.put(SECRET, "secret-v2");

        ConfigCheck.Result result = check.run();

        assertThat(result.restartable()).isTrue();
        assertThat(result.changed()).containsExactly("file-upload-service.database.password",
                "file-upload-service.outbound-proxy.host");
        assertThat(result.toString()).doesNotContain("secret-v2").doesNotContain("proxy.internal");
    }

    @Test
    void aValueThatWouldNotValidateStopsARestart() {
        ConfigCheck check = check(FakeVault.ROOT_TOKEN, Map.of());
        ssm.parameters.put(SSM_PATH + "file-upload-service/outbound-proxy/host", "proxy.internal");
        ssm.parameters.put(SSM_PATH + "file-upload-service/outbound-proxy/port", "99999");

        ConfigCheck.Result result = check.run();

        assertThat(result.restartable()).isFalse();
        assertThat(result.problems()).singleElement().asString().contains("must be between 1 and 65535");
        assertThat(check.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(registry.get("file-upload-service.config.restartable").gauge().value()).isZero();
    }

    @Test
    void aVaultLoginThatNoLongerWorksStopsARestartWithoutShowingTheToken() {
        ConfigCheck.Result result = check("revoked-token", Map.of()).run();

        assertThat(result.restartable()).isFalse();
        assertThat(result.problems()).singleElement().asString()
                .startsWith("Vault secret secret/service/test: ")
                .contains("HTTP 403: permission denied")
                .doesNotContain("revoked-token");
    }

    @Test
    void productionModeRulesApplyToTheStoredConfiguration() {
        ConfigCheck check = check(FakeVault.ROOT_TOKEN, Map.of(
                "file-upload-service.mode", "production",
                "file-upload-service.scan.queue-url", "https://sqs.ap-southeast-2.amazonaws.com/123456789012/scan-events",
                "logging.structured.format.console", "ecs"));
        assertThat(check.run().restartable()).isTrue();

        ssm.parameters.put(SSM_PATH + "logging/structured/format/console", "logstash");

        assertThat(check.run().problems()).singleElement().asString().contains("FILE_UPLOAD_SERVICE_LOG_FORMAT=ecs");
    }

    @Test
    void recoversOnceTheStoreIsFixed() {
        ConfigCheck check = check(FakeVault.ROOT_TOKEN, Map.of());
        ssm.parameters.put(SSM_PATH + "file-upload-service/outbound-proxy/host", "proxy.internal");
        ssm.parameters.put(SSM_PATH + "file-upload-service/outbound-proxy/port", "99999");
        check.run();

        ssm.parameters.put(SSM_PATH + "file-upload-service/outbound-proxy/port", "3128");

        assertThat(check.run().restartable()).isTrue();
        assertThat(check.health().getStatus()).isEqualTo(Status.UP);
        assertThat(registry.get("file-upload-service.config.restartable").gauge().value()).isEqualTo(1);
    }

    /** A check over an environment as startup left it, with the stores' current contents as its startup values. */
    private ConfigCheck check(String vaultToken, Map<String, Object> more) {
        Map<String, Object> settings = new HashMap<>(more);
        settings.put("file-upload-service.ssm.path", SSM_PATH);
        settings.put("file-upload-service.ssm.region", "eu-west-1");
        settings.put("file-upload-service.ssm.endpoint", ssm.endpoint());
        settings.put("file-upload-service.vault.address", vault.address());
        settings.put("file-upload-service.vault.path", "service/test");
        settings.put("file-upload-service.vault.token", vaultToken);
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addLast(new MapPropertySource("settings", settings));
        SsmSettings ssmSettings = Binder.get(environment).bindOrCreate("file-upload-service.ssm", SsmSettings.class);
        environment.getPropertySources().addBefore("settings",
                new MapPropertySource(SsmConfigData.sourceName(ssmSettings), SsmConfigData.read(ssmSettings)));
        VaultSettings vaultSettings = Binder.get(environment).bindOrCreate("file-upload-service.vault", VaultSettings.class);
        environment.getPropertySources().addFirst(
                new MapPropertySource(VaultConfigData.sourceName(vaultSettings), new HashMap<>(secret)));
        FileUploadServiceProperties running = Binder.get(environment).bindOrCreate("file-upload-service", FileUploadServiceProperties.class);
        return new ConfigCheck(environment, running, registry,
                Clock.fixed(Instant.parse("2026-10-23T00:00:00Z"), ZoneOffset.UTC));
    }
}
