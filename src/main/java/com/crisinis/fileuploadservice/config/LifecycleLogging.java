package com.crisinis.fileuploadservice.config;

import com.crisinis.fileuploadservice.logging.LogEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.stereotype.Component;

/** Logs lifecycle events from the catalogue, so support can see when an instance became ready. */
@Slf4j
@Component
class LifecycleLogging {

    private static final String SSM = "file-upload-service-ssm:";
    private static final String VAULT = "file-upload-service-vault:";

    private final FileUploadServiceProperties properties;

    LifecycleLogging(FileUploadServiceProperties properties) {
        this.properties = properties;
    }

    @EventListener
    void started(ApplicationReadyEvent event) {
        ConfigurableEnvironment environment = event.getApplicationContext().getEnvironment();
        logExternalConfiguration(environment);
        Integer port = environment.getProperty("local.server.port", Integer.class);
        LogEvent.SERVICE_STARTED.at(log).with("port", port).with("mode", properties.mode()).log();
    }

    /**
     * Configuration and secrets are loaded before logging starts, so they're reported here, from the
     * property sources they added: which store, which path, how many keys. Never the values.
     */
    private static void logExternalConfiguration(ConfigurableEnvironment environment) {
        for (PropertySource<?> source : environment.getPropertySources()) {
            int keys = source instanceof EnumerablePropertySource<?> e ? e.getPropertyNames().length : 0;
            if (source.getName().startsWith(SSM)) {
                LogEvent.CONFIG_LOADED.at(log).with("path", source.getName().substring(SSM.length()))
                        .with("keys", keys).log();
            } else if (source.getName().startsWith(VAULT)) {
                LogEvent.SECRETS_LOADED.at(log).with("path", source.getName().substring(VAULT.length()))
                        .with("keys", keys).log();
            }
        }
    }
}
