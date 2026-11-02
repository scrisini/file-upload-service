package com.crisinis.fileuploadservice.configdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParametersByPathRequest;
import software.amazon.awssdk.services.ssm.model.GetParametersByPathResponse;
import software.amazon.awssdk.services.ssm.model.Parameter;

class ParameterStoreTests {

    @Test
    void readsEveryPageAndNamesPropertiesAfterThePath() {
        SsmClient client = mock(SsmClient.class);
        when(client.getParametersByPath(any(GetParametersByPathRequest.class))).thenAnswer(inv -> {
            GetParametersByPathRequest request = inv.getArgument(0);
            assertThat(request.path()).isEqualTo("/service/prod/");
            assertThat(request.recursive()).isTrue();
            return request.nextToken() == null
                    ? GetParametersByPathResponse.builder().nextToken("page-2")
                            .parameters(parameter("/service/prod/file-upload-service/allowed-hosts", "a.example,b.example")).build()
                    : GetParametersByPathResponse.builder()
                            .parameters(parameter("/service/prod/file-upload-service.outbound-proxy.port", "3128")).build();
        });

        Map<String, Object> properties = new ParameterStore(client).read("/service/prod/");

        assertThat(properties).containsExactly(Map.entry("file-upload-service.allowed-hosts", "a.example,b.example"),
                Map.entry("file-upload-service.outbound-proxy.port", "3128"));
    }

    private static Parameter parameter(String name, String value) {
        return Parameter.builder().name(name).value(value).build();
    }
}
