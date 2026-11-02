package com.crisinis.fileuploadservice.support;

import java.util.Map;

import com.crisinis.fileuploadservice.config.FileUploadServiceProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** Settings bound the way Spring binds them, from {@code file-upload-service.*} properties. */
public final class TestProperties {

    private TestProperties() {
    }

    public static FileUploadServiceProperties of(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bindOrCreate("file-upload-service", FileUploadServiceProperties.class);
    }

    public static FileUploadServiceProperties defaults() {
        return of(Map.of());
    }
}
