package com.crisinis.fileuploadservice.extraction;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.crisinis.fileuploadservice.config.FileUploadServiceProperties;
import com.crisinis.fileuploadservice.logging.LogEvent;
import com.crisinis.fileuploadservice.model.FileRecord;
import com.crisinis.fileuploadservice.model.PolicyExtraction;
import com.crisinis.fileuploadservice.persistence.FileRepository;
import com.crisinis.fileuploadservice.persistence.FileRepository.Claim;
import com.crisinis.fileuploadservice.persistence.FileRepository.Stage;
import com.crisinis.fileuploadservice.service.DependencyUnavailableException;
import com.crisinis.fileuploadservice.storage.FileStorage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Extracts policies from opted-in files once they're AVAILABLE. {@code extraction.concurrency}
 * virtual threads per instance each claim one pending extraction at a time (a DynamoDB lease, so
 * across all instances each runs once), read the file from the clean bucket, ask the model, and
 * store the policy. Nothing is held in memory between files.
 *
 * <p>Crash safety: a worker that dies leaves its claim to expire ({@code extraction.lease}), and
 * another worker takes the file over. Temporary failures wait {@code retry-backoff × attempt} and
 * retry; after {@code max-attempts} calls, or a permanent failure, the extraction is FAILED.
 */
@Slf4j
@Component
public class ExtractionWorker implements SmartLifecycle {

    /** Pending extractions looked at per claim attempt; shuffled, so workers don't all race for the oldest. */
    private static final int CANDIDATES = 10;

    private final FileRepository files;
    private final FileStorage storage;
    private final BedrockPolicyModel model;
    private final PolicyParser parser;
    private final JsonMapper json;
    private final FileUploadServiceProperties.Extraction settings;
    private final Clock clock;
    private final Map<String, Timer> modelCalls;
    private final Counter inputTokens;
    private final Counter outputTokens;
    private final Timer timeToComplete;
    private final List<Thread> workers = new ArrayList<>();
    private volatile boolean running;

    public ExtractionWorker(FileRepository files, FileStorage storage, BedrockPolicyModel model, PolicyParser parser,
                            JsonMapper json, FileUploadServiceProperties properties, Clock clock, MeterRegistry registry) {
        this.files = files;
        this.storage = storage;
        this.model = model;
        this.parser = parser;
        this.json = json;
        this.settings = properties.extraction();
        this.clock = clock;
        this.modelCalls = Map.of(
                "success", modelTimer(registry, "success"),
                "retry", modelTimer(registry, "retry"),
                "failed", modelTimer(registry, "failed"));
        this.inputTokens = Counter.builder("file-upload-service.extraction.tokens").tag("direction", "input")
                .description("Tokens sent to the model (cost)").register(registry);
        this.outputTokens = Counter.builder("file-upload-service.extraction.tokens").tag("direction", "output")
                .description("Tokens the model wrote (cost)").register(registry);
        this.timeToComplete = Timer.builder("file-upload-service.extraction.time-to-complete")
                .description("From the file becoming available to its policy being stored").register(registry);
    }

    @Override
    public synchronized void start() {
        if (!settings.enabled()) {
            LogEvent.EXTRACTION_OFF.at(log).log();
            return;
        }
        if (settings.simulator().enabled()) {
            LogEvent.BEDROCK_SIMULATOR_ON.at(log).with("delay", settings.simulator().delay()).log();
        }
        running = true;
        for (int i = 0; i < settings.concurrency(); i++) {
            workers.add(Thread.ofVirtual().name("extraction-" + i).start(this::workUntilStopped));
        }
        LogEvent.EXTRACTION_STARTED.at(log).with("model", settings.modelId()).with("concurrency", settings.concurrency()).log();
    }

    /**
     * Stops claiming. Calls in progress get a few seconds; one still running when the instance stops
     * is taken over by another instance when its lease ends.
     */
    @Override
    public synchronized void stop() {
        running = false;
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
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

    private void workUntilStopped() {
        while (running) {
            try {
                Optional<Claim> claim = claimNext();
                if (claim.isPresent()) {
                    extract(claim.get());
                } else {
                    pause(settings.pollInterval());
                }
            } catch (DependencyUnavailableException e) {
                LogEvent.EXTRACTION_WORKER_FAILED.at(log).with("operation", e.operation()).cause(e).log();
                pause(settings.pollInterval().multipliedBy(5));
            }
        }
    }

    /** One pending extraction, claimed for this worker; empty when there's none to take. */
    Optional<Claim> claimNext() {
        Instant now = clock.instant();
        List<String> pending = new ArrayList<>(files.inStageSince(Stage.EXTRACTING, now.plusMillis(1), CANDIDATES));
        Collections.shuffle(pending);
        for (String id : pending) {
            Optional<Claim> claim = files.claimExtraction(id, now, settings.lease());
            if (claim.isPresent()) {
                return claim;
            }
        }
        return Optional.empty();
    }

    /** Reads, asks, stores: one attempt at one file's extraction. */
    void extract(Claim claim) {
        FileRecord file = claim.file();
        if (claim.attempt() > settings.maxAttempts()) {
            // workers died holding it (each claim counts): stop before it costs more
            fail(file, claim.attempt() - 1, "ATTEMPTS_EXHAUSTED");
            return;
        }
        long started = System.nanoTime();
        try {
            byte[] content = storage.readClean(file.id(), BedrockPolicyModel.MAX_DOCUMENT_BYTES)
                    .orElseThrow(() -> new ExtractionException(false, "FILE_MISSING", "not in the clean bucket", null));
            BedrockPolicyModel.Answer answer = model.read(file.type(), content);
            inputTokens.increment(answer.inputTokens());
            outputTokens.increment(answer.outputTokens());
            PolicyExtraction policy = parser.parse(answer.text());
            modelCalls.get("success").record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
            complete(file, claim.attempt(), policy, answer);
        } catch (ExtractionException e) {
            boolean retry = e.retryable() && claim.attempt() < settings.maxAttempts();
            modelCalls.get(retry ? "retry" : "failed").record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
            if (retry) {
                defer(file, claim.attempt(), e);
            } else {
                fail(file, claim.attempt(), e.reason());
            }
        }
    }

    private void complete(FileRecord file, int attempt, PolicyExtraction policy, BedrockPolicyModel.Answer answer) {
        Instant now = clock.instant();
        if (!files.completeExtraction(file.id(), model.modelId(), json.writeValueAsString(policy), now)) {
            return;
        }
        if (file.availableAt() != null) {
            timeToComplete.record(Duration.between(file.availableAt(), now));
        }
        // counts only: the values are personal data
        LogEvent.EXTRACTION_COMPLETED.at(log).with("file_id", file.id()).with("model", model.modelId())
                .with("attempt", attempt).with("fields_found", policy.fieldsFound())
                .with("warnings", policy.warnings().size())
                .with("input_tokens", answer.inputTokens()).with("output_tokens", answer.outputTokens()).log();
    }

    private void defer(FileRecord file, int attempt, ExtractionException e) {
        Instant retryAt = clock.instant().plus(settings.retryBackoff().multipliedBy(attempt));
        files.deferExtraction(file.id(), retryAt, e.reason());
        LogEvent.EXTRACTION_RETRYING.at(log).with("file_id", file.id()).with("attempt", attempt)
                .with("reason", e.reason()).with("retry_at", retryAt).log();
    }

    private void fail(FileRecord file, int attempts, String reason) {
        if (files.failExtraction(file.id(), reason, clock.instant())) {
            LogEvent.EXTRACTION_FAILED.at(log).with("file_id", file.id()).with("reason", reason)
                    .with("attempts", attempts).log();
        }
    }

    private void pause(Duration duration) {
        try {
            long until = System.nanoTime() + duration.toNanos();
            while (running && System.nanoTime() < until) {
                Thread.sleep(Math.min(200, Math.max(1, (until - System.nanoTime()) / 1_000_000)));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }

    private static Timer modelTimer(MeterRegistry registry, String outcome) {
        return Timer.builder("file-upload-service.extraction.model.calls")
                .description("Extraction attempts: reading the file, the model call and parsing its answer")
                .tag("outcome", outcome).register(registry);
    }
}
