package com.crisinis.fileuploadservice.config;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.interceptor.SdkExecutionAttribute;

/**
 * Times every AWS call, including the SDK's retries, as {@code file-upload-service.aws.requests}
 * tagged {@code service}, {@code operation} and {@code outcome=success|error}: one timer per
 * dependency call, as AGENTS.md asks, without wrapping each call site.
 */
final class AwsCallMetrics implements ExecutionInterceptor {

    private static final ExecutionAttribute<Long> STARTED = new ExecutionAttribute<>("file-upload-service.started");

    private record Key(String service, String operation, boolean success) {
    }

    private final MeterRegistry registry;
    // a handful of operations: each timer registered once, then reused
    private final Map<Key, Timer> timers = new ConcurrentHashMap<>();

    AwsCallMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void beforeExecution(Context.BeforeExecution context, ExecutionAttributes attributes) {
        attributes.putAttribute(STARTED, System.nanoTime());
    }

    @Override
    public void afterExecution(Context.AfterExecution context, ExecutionAttributes attributes) {
        record(attributes, true);
    }

    @Override
    public void onExecutionFailure(Context.FailedExecution context, ExecutionAttributes attributes) {
        record(attributes, false);
    }

    private void record(ExecutionAttributes attributes, boolean success) {
        Long started = attributes.getAttribute(STARTED);
        if (started == null) {
            return;
        }
        Key key = new Key(attributes.getAttribute(SdkExecutionAttribute.SERVICE_NAME),
                attributes.getAttribute(SdkExecutionAttribute.OPERATION_NAME), success);
        timers.computeIfAbsent(key, this::timer).record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    private Timer timer(Key key) {
        return Timer.builder("file-upload-service.aws.requests")
                .description("Calls to AWS services, including the SDK's retries")
                .tag("service", key.service())
                .tag("operation", key.operation())
                .tag("outcome", key.success() ? "success" : "error")
                .register(registry);
    }
}
