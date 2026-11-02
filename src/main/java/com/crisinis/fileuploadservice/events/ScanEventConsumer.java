package com.crisinis.fileuploadservice.events;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.crisinis.fileuploadservice.config.FileUploadServiceProperties;
import com.crisinis.fileuploadservice.logging.LogEvent;
import com.crisinis.fileuploadservice.service.ScanOutcomes;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;

/**
 * Receives S3 and GuardDuty events from the scan queue on {@code scan.consumers} virtual threads per
 * instance; SQS shares the messages between all instances. A message is deleted once handled; if
 * handling fails it stays, SQS delivers it again after the visibility timeout, and after
 * {@code maxReceiveCount} attempts moves it to the dead-letter queue (docs/deployment.md).
 *
 * <p>Stops first on shutdown: no new receives, and the current ones finish (a receive waits at most
 * {@link #WAIT}).
 */
@Slf4j
@Component
public class ScanEventConsumer implements SmartLifecycle {

    static final Duration WAIT = Duration.ofSeconds(10);
    private static final int BATCH = 10;
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    private final SqsClient sqs;
    private final ScanEventParser parser;
    private final ScanOutcomes outcomes;
    private final FileUploadServiceProperties.Scan scan;
    private final Timer queueWait;
    private final Map<String, Counter> handled;
    private final List<Thread> workers = new ArrayList<>();
    private volatile boolean running;

    public ScanEventConsumer(SqsClient sqs, ScanEventParser parser, ScanOutcomes outcomes,
                             FileUploadServiceProperties properties, MeterRegistry registry) {
        this.sqs = sqs;
        this.parser = parser;
        this.outcomes = outcomes;
        this.scan = properties.scan();
        this.queueWait = Timer.builder("file-upload-service.events.wait")
                .description("Time an event waited in the scan queue before this service received it")
                .register(registry);
        this.handled = Map.of(
                "handled", eventCounter(registry, "handled"),
                "failed", eventCounter(registry, "failed"),
                "unrecognised", eventCounter(registry, "unrecognised"),
                "unknown_file", eventCounter(registry, "unknown_file"));
    }

    @Override
    public synchronized void start() {
        if (!scan.enabled()) {
            LogEvent.SCAN_EVENTS_OFF.at(log).log();
            return;
        }
        running = true;
        for (int i = 0; i < scan.consumers(); i++) {
            workers.add(Thread.ofVirtual().name("scan-events-" + i).start(this::receiveUntilStopped));
        }
        LogEvent.SCAN_EVENTS_STARTED.at(log).with("queue_url", scan.queueUrl()).with("consumers", scan.consumers()).log();
    }

    @Override
    public synchronized void stop() {
        running = false;
        long deadline = System.nanoTime() + WAIT.plusSeconds(5).toNanos();
        for (Thread worker : workers) {
            try {
                worker.join(Duration.ofNanos(Math.max(0, deadline - System.nanoTime())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        workers.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void receiveUntilStopped() {
        Duration backoff = Duration.ofSeconds(1);
        while (running) {
            try {
                List<Message> messages = sqs.receiveMessage(r -> r.queueUrl(scan.queueUrl())
                        .maxNumberOfMessages(BATCH).waitTimeSeconds((int) WAIT.toSeconds())
                        .messageSystemAttributeNames(MessageSystemAttributeName.SENT_TIMESTAMP,
                                MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT)).messages();
                backoff = Duration.ofSeconds(1);
                for (Message message : messages) {
                    process(message);
                }
            } catch (SdkException e) {
                LogEvent.SCAN_QUEUE_UNREACHABLE.at(log).with("retry_in", backoff).cause(e).log();
                if (!pause(backoff)) {
                    return;
                }
                backoff = backoff.multipliedBy(2).compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff.multipliedBy(2);
            }
        }
    }

    /** Handles one message, deleting it unless it should be delivered again. */
    void process(Message message) {
        recordWait(message);
        ScanEvent event = parser.parse(message.body());
        if (event instanceof ScanEvent.Unrecognised unrecognised) {
            handled.get("unrecognised").increment();
            LogEvent.SCAN_EVENT_UNRECOGNISED.at(log).with("message_id", message.messageId())
                    .with("why", unrecognised.why()).log();
            delete(message);
            return;
        }
        try {
            if (outcomes.handle(event) == ScanOutcomes.Outcome.UNKNOWN_FILE) {
                handled.get("unknown_file").increment();
                LogEvent.SCAN_EVENT_UNKNOWN_FILE.at(log).with("message_id", message.messageId())
                        .with("event", event).log();
            } else {
                handled.get("handled").increment();
            }
            delete(message);
        } catch (RuntimeException e) {
            handled.get("failed").increment();
            LogEvent.SCAN_EVENT_FAILED.at(log).with("message_id", message.messageId())
                    .with("receive_count", message.attributes().get(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT))
                    .with("event", event).cause(e).log();
        }
    }

    private void delete(Message message) {
        sqs.deleteMessage(d -> d.queueUrl(scan.queueUrl()).receiptHandle(message.receiptHandle()));
    }

    private void recordWait(Message message) {
        String sent = message.attributes().get(MessageSystemAttributeName.SENT_TIMESTAMP);
        if (sent != null) {
            queueWait.record(Math.max(0, System.currentTimeMillis() - Long.parseLong(sent)), TimeUnit.MILLISECONDS);
        }
    }

    /** Sleeps unless stopped first; false if interrupted. */
    private boolean pause(Duration duration) {
        try {
            long until = System.nanoTime() + duration.toNanos();
            while (running && System.nanoTime() < until) {
                Thread.sleep(Math.min(200, Math.max(1, (until - System.nanoTime()) / 1_000_000)));
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static Counter eventCounter(MeterRegistry registry, String outcome) {
        return Counter.builder("file-upload-service.events")
                .description("Scan queue messages, by what became of them").tag("outcome", outcome).register(registry);
    }
}
