package com.crisinis.fileuploadservice.configdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.crisinis.fileuploadservice.support.FakeVault;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VaultClientTests {

    @TempDir
    Path dir;

    private FakeVault vault;

    @BeforeEach
    void start() throws Exception {
        vault = new FakeVault(Map.of(
                "file-upload-service.database.password", "db-secret",
                "file-upload-service", Map.of("outbound-proxy", Map.of("host", "proxy.internal")),
                "file-upload-service.allowed-hosts", List.of("a.example", "b.example")));
    }

    @AfterEach
    void stop() {
        vault.close();
    }

    @Test
    void readsASecretWithATokenAndFlattensNestedKeys() {
        Map<String, Object> secrets = new VaultClient(settings(VaultSettings.Auth.TOKEN, FakeVault.ROOT_TOKEN, "",
                "")).readSecret();

        assertThat(secrets).containsEntry("file-upload-service.database.password", "db-secret")
                .containsEntry("file-upload-service.outbound-proxy.host", "proxy.internal")
                .containsEntry("file-upload-service.allowed-hosts", "a.example,b.example");
        assertThat(vault.revoked).as("a token it was given isn't its to revoke").isEmpty();
    }

    @Test
    void logsInWithTheKubernetesServiceAccountAndRevokesTheTokenAfterwards() throws Exception {
        Path jwt = Files.writeString(dir.resolve("token"), FakeVault.KUBERNETES_JWT + "\n");

        Map<String, Object> secrets = new VaultClient(settings(VaultSettings.Auth.KUBERNETES, "", jwt.toString(), ""))
                .readSecret();

        assertThat(secrets).containsEntry("file-upload-service.database.password", "db-secret");
        assertThat(vault.revoked).containsExactly(FakeVault.LOGIN_TOKEN);
    }

    @Test
    void logsInWithAnAppRole() throws Exception {
        Path secretId = Files.writeString(dir.resolve("secret-id"), FakeVault.SECRET_ID);

        assertThat(new VaultClient(settings(VaultSettings.Auth.APP_ROLE, "", "", secretId.toString())).readSecret())
                .containsEntry("file-upload-service.database.password", "db-secret");
        assertThat(vault.revoked).containsExactly(FakeVault.LOGIN_TOKEN);
    }

    @Test
    void aRefusalSaysWhyWithoutTheToken() {
        VaultClient client = new VaultClient(settings(VaultSettings.Auth.TOKEN, "wrong-token", "", ""));

        assertThatThrownBy(client::readSecret)
                .hasMessageContaining("HTTP 403: permission denied")
                .hasMessageNotContaining("wrong-token");
    }

    @Test
    void refusesPlainHttpExceptToThisMachine() {
        assertThatThrownBy(() -> VaultClient.requireSecureAddress("http://vault.internal:8200"))
                .hasMessageContaining("must use https");
        VaultClient.requireSecureAddress("https://vault.internal:8200");
        VaultClient.requireSecureAddress("http://localhost:8200");
        VaultClient.requireSecureAddress("http://127.0.0.1:8200");
    }

    @Test
    void settingsNeverPrintTheToken() {
        assertThat(settings(VaultSettings.Auth.TOKEN, "s3cr3t-token", "", "").toString()).doesNotContain("s3cr3t-token");
    }

    private VaultSettings settings(VaultSettings.Auth auth, String token, String jwtFile, String secretIdFile) {
        return new VaultSettings(vault.address(), "secret", "service/test", 2, "", "", Duration.ofSeconds(5), auth, token,
                new VaultSettings.Kubernetes("service", "kubernetes", Path.of(jwtFile.isEmpty() ? "/none" : jwtFile)),
                new VaultSettings.AppRole("service-role", secretIdFile, "approle"));
    }
}
