package com.crisinis.fileuploadservice.configdata;

import java.util.LinkedHashMap;
import java.util.Map;

import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParametersByPathRequest;
import software.amazon.awssdk.services.ssm.model.GetParametersByPathResponse;
import software.amazon.awssdk.services.ssm.model.Parameter;

/** Reads a Parameter Store hierarchy as properties. */
final class ParameterStore {

    private final SsmClient client;

    ParameterStore(SsmClient client) {
        this.client = client;
    }

    /** Every parameter under {@code path} (ending in {@code /}), named by {@link #propertyName}. */
    Map<String, Object> read(String path) {
        Map<String, Object> properties = new LinkedHashMap<>();
        String nextToken = null;
        do {
            GetParametersByPathResponse page = client.getParametersByPath(GetParametersByPathRequest.builder()
                    .path(path).recursive(true).withDecryption(true).nextToken(nextToken).build());
            for (Parameter parameter : page.parameters()) {
                properties.put(propertyName(path, parameter.name()), parameter.value());
            }
            nextToken = page.nextToken();
        } while (nextToken != null);
        return properties;
    }

    /** {@code /file-upload-service/prod/file-upload-service/some/setting} and {@code /file-upload-service/prod/file-upload-service.some.setting} under {@code /file-upload-service/prod/}: {@code file-upload-service.some.setting}. */
    static String propertyName(String path, String parameterName) {
        return parameterName.substring(path.length()).replace('/', '.');
    }
}
