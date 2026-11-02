package com.crisinis.fileuploadservice.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.function.Consumer;

import com.crisinis.fileuploadservice.service.DependencyUnavailableException;
import com.crisinis.fileuploadservice.service.ScanOutcomes;
import com.crisinis.fileuploadservice.support.TestProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import tools.jackson.databind.json.JsonMapper;

class ScanEventConsumerTests {

    static final String QUEUE = "https://sqs.ap-southeast-2.amazonaws.com/123456789012/scan-events";

    private final SqsClient sqs = mock(SqsClient.class);
    private final ScanOutcomes outcomes = mock(ScanOutcomes.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ScanEventConsumer consumer = new ScanEventConsumer(sqs, new ScanEventParser(JsonMapper.builder().build()),
            outcomes, TestProperties.of(Map.of("file-upload-service.scan.queue-url", QUEUE)), registry);

    static Message message(String body) {
        return Message.builder().messageId("m-1").receiptHandle("r-1").body(body)
                .attributes(Map.of(MessageSystemAttributeName.SENT_TIMESTAMP, Long.toString(System.currentTimeMillis() - 2000),
                        MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT, "1"))
                .build();
    }

    static final String CLEAN = ScanEventParserTests.scanResult("COMPLETED", "NO_THREATS_FOUND", "null", "null");

    @Test
    void deletesAnEventOnceHandled() {
        when(outcomes.handle(any())).thenReturn(ScanOutcomes.Outcome.HANDLED);

        consumer.process(message(CLEAN));

        verify(sqs).deleteMessage(org.mockito.ArgumentMatchers.<Consumer<DeleteMessageRequest.Builder>>any());
        assertThat(registry.get("file-upload-service.events").tag("outcome", "handled").counter().count()).isEqualTo(1);
        assertThat(registry.get("file-upload-service.events.wait").timer().totalTime(java.util.concurrent.TimeUnit.SECONDS))
                .isGreaterThanOrEqualTo(2);
    }

    @Test
    void keepsAnEventWhoseHandlingFailedSoSqsDeliversItAgain() {
        when(outcomes.handle(any())).thenThrow(new DependencyUnavailableException("s3", "CopyObject", new RuntimeException("timeout")));

        consumer.process(message(CLEAN));

        verify(sqs, never()).deleteMessage(org.mockito.ArgumentMatchers.<Consumer<DeleteMessageRequest.Builder>>any());
        assertThat(registry.get("file-upload-service.events").tag("outcome", "failed").counter().count()).isEqualTo(1);
    }

    @Test
    void deletesWhatItCantUse() {
        when(outcomes.handle(any())).thenReturn(ScanOutcomes.Outcome.UNKNOWN_FILE);

        consumer.process(message("{\"detail-type\":\"Object Deleted\"}"));
        consumer.process(message(CLEAN));

        verify(sqs, org.mockito.Mockito.times(2))
                .deleteMessage(org.mockito.ArgumentMatchers.<Consumer<DeleteMessageRequest.Builder>>any());
        assertThat(registry.get("file-upload-service.events").tag("outcome", "unrecognised").counter().count()).isEqualTo(1);
        assertThat(registry.get("file-upload-service.events").tag("outcome", "unknown_file").counter().count()).isEqualTo(1);
    }

    @Test
    void withoutAQueueItDoesNotStart() {
        ScanEventConsumer off = new ScanEventConsumer(sqs, new ScanEventParser(JsonMapper.builder().build()), outcomes,
                TestProperties.defaults(), new SimpleMeterRegistry());

        off.start();

        assertThat(off.isRunning()).isFalse();
    }
}
