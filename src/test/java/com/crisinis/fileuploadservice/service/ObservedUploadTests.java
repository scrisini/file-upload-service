package com.crisinis.fileuploadservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.crisinis.fileuploadservice.persistence.FileRepository;
import com.crisinis.fileuploadservice.storage.FileStorage;
import com.crisinis.fileuploadservice.support.TestProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

/** What {@code @Observed} on the streaming upload produces: an observation per annotated call, which tracing turns into a span. */
class ObservedUploadTests {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13, 'I', 'H', 'D', 'R', 1, 2, 3};

    /** Remembers each observation's name, contextual name and low-cardinality tags, as a tracing handler would read them. */
    private record Seen(String name, String contextualName, Map<String, String> tags) {}

    @Test
    void aStreamedUploadIsObservedAsOneCallWithTheStorageAndDatabaseCallsBelowIt() {
        List<Seen> seen = new ArrayList<>();
        ObservationRegistry observations = ObservationRegistry.create();
        observations.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }

            @Override
            public void onStop(Observation.Context context) {
                Map<String, String> tags = new java.util.HashMap<>();
                context.getLowCardinalityKeyValues().forEach(kv -> tags.put(kv.getKey(), kv.getValue()));
                seen.add(new Seen(context.getName(), context.getContextualName(), tags));
            }
        });
        ObservedAspect aspect = new ObservedAspect(observations);

        // stub the mocks before wrapping them: a call through the proxy is observed too
        FileRepository rawFiles = mock(FileRepository.class);
        when(rawFiles.createIfAbsent(any())).thenAnswer(call -> new FileRepository.Update(true, call.getArgument(0)));
        when(rawFiles.markSubmitted(any(), any())).thenReturn(Optional.empty());
        FileRepository files = proxied(rawFiles, aspect);
        FileStorage storage = proxied(mock(FileStorage.class), aspect);
        UploadService uploads = proxied(new UploadService(files, storage, TestProperties.of(Map.of()),
                Clock.fixed(Instant.parse("2026-11-02T10:00:00Z"), ZoneOffset.UTC), new SimpleMeterRegistry()), aspect);

        uploads.stream(DeclaredFile.of("a.png", "image/png", PNG.length), new ByteArrayInputStream(PNG), Optional.empty());

        // innermost calls stop first
        assertThat(seen).extracting(Seen::name).containsExactly(
                "file-upload-service.file.create",
                "file-upload-service.quarantine.store",
                "file-upload-service.upload.stream");
        assertThat(seen).extracting(Seen::contextualName).containsExactly(
                "file-repository#createIfAbsent", "file-storage#store", "upload-service#stream");
        assertThat(seen.get(2).tags()).containsEntry("method", "stream").containsEntry("class", UploadService.class.getName());
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxied(T target, ObservedAspect aspect) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAspect(aspect);
        return (T) factory.getProxy();
    }
}
