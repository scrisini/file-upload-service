package com.crisinis.fileuploadservice.events;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.crisinis.fileuploadservice.config.FileUploadServiceProperties;
import com.crisinis.fileuploadservice.config.GracefulShutdown;
import com.crisinis.fileuploadservice.logging.LogEvent;
import com.crisinis.fileuploadservice.service.ScanOutcomes;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;

/**
 * Receives S3 and GuardDuty events from the scan queue on {@code scan.consumers} virtual threads per
 * instance; SQS shares the messages between all instances. A message is deleted once handled; if
 * handling fails it stays, SQS delivers it again after the visibility timeout, and after
 * {@code maxReceiveCount} attempts moves it to the dead-letter queue (docs/deployment.md).
 *
 * <p>On shutdown (README "Graceful shutdown") it stops in the web server's graceful phase, in
 * parallel with HTTP requests: no new receives; the message being handled finishes; messages
 * received but not started are released (visibility 0), so another instance handles them at once
 * instead of after the visibility timeout. A receive in progress returns within {@link #WAIT}.
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
    private final Duration drainBudget;
    private final AtomicInteger finishedWhileStopping = new AtomicInteger();
    private final AtomicInteger released = new AtomicInteger();
    private volatile boolean running;

    public ScanEventConsumer(SqsClient sqs, ScanEventParser parser, ScanOutcomes outcomes,
                             FileUploadServiceProperties properties, MeterRegistry registry,
                             @Value("${spring.lifecycle.timeout-per-shutdown-phase:30s}") Duration phaseTimeout) {
        this.drainBudget = GracefulShutdown.drainBudget(phaseTimeout);
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

    /** Stops taking messages and drains in the background; Spring continues when {@code done} runs. */
    @Override
    public synchronized void stop(Runnable done) {
        if (!running) {
            done.run();
            return;
        }
        running = false;
        List<Thread> draining = List.copyOf(workers);
        workers.clear();
        Thread.ofVirtual().name("scan-events-drain").start(() -> {
            long started = System.nanoTime();
            awaitAll(draining, started + drainBudget.toNanos());
            LogEvent.WORK_DRAINED.at(log).with("component", "scan-events")
                    .with("finished", finishedWhileStopping.get()).with("handed_over", released.get())
                    .with("drain_ms", (System.nanoTime() - started) / 1_000_000).log();
            done.run();
        });
    }

    @Override
    public void stop() {
        CountDownLatch stopped = new CountDownLatch(1);
        stop(stopped::countDown);
        try {
            stopped.await(drainBudget.toMillis() + 1000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Drains with the HTTP requests (README "Graceful shutdown"). */
    @Override
    public int getPhase() {
        return GracefulShutdown.DRAIN_PHASE;
    }

    private static void awaitAll(List<Thread> threads, long deadline) {
        for (Thread thread : threads) {
            try {
                thread.join(Duration.ofNanos(Math.max(0, deadline - System.nanoTime())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
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
                handle(messages);
            } catch (SdkException e) {
                LogEvent.SCAN_QUEUE_UNREACHABLE.at(log).with("retry_in", backoff).cause(e).log();
                if (!pause(backoff)) {
                    return;
                }
                backoff = backoff.multipliedBy(2).compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff.multipliedBy(2);
            }
        }
    }

    /**
     * Handles a batch in order. Once shutdown starts, the message in hand finishes and the rest are
     * released at once for other instances.
     */
    void handle(List<Message> messages) {
        for (int i = 0; i < messages.size(); i++) {
            if (!running) {
                release(messages.subList(i, messages.size()));
                return;
            }
            boolean stoppingAlready = !running;
            process(messages.get(i));
            if (!running && !stoppingAlready) {
                finishedWhileStopping.incrementAndGet();
            }
        }
    }

    /** Makes messages visible again now, rather than after the visibility timeout. */
    private void release(List<Message> messages) {
        List<ChangeMessageVisibilityBatchRequestEntry> entries = new ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            entries.add(ChangeMessageVisibilityBatchRequestEntry.builder().id(Integer.toString(i))
                    .receiptHandle(messages.get(i).receiptHandle()).visibilityTimeout(0).build());
        }
        try {
            sqs.changeMessageVisibilityBatch(r -> r.queueUrl(scan.queueUrl()).entries(entries));
            released.addAndGet(messages.size());
        } catch (SdkException e) {
            // they become visible anyway when the visibility timeout ends
            LogEvent.SCAN_QUEUE_UNREACHABLE.at(log).with("retry_in", "visibility timeout").cause(e).log();
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
