package com.crisinis.fileuploadservice.config;

import java.time.Duration;

import org.springframework.boot.web.server.context.WebServerGracefulShutdownLifecycle;

/**
 * When background workers stop on SIGTERM, and how long they may take (README "Graceful shutdown").
 *
 * <p>Spring Boot stops {@code SmartLifecycle} beans phase by phase, highest first, giving each phase
 * at most {@code spring.lifecycle.timeout-per-shutdown-phase}. Workers stop in the same phase as the
 * web server's graceful shutdown, asynchronously, so they drain in parallel with in-flight HTTP
 * requests and one phase timeout bounds them all; then scheduled tasks finish (their own phase);
 * only then are beans destroyed and AWS clients closed.
 */
public final class GracefulShutdown {

    /** The phase of the web server's graceful shutdown: workers drain alongside HTTP requests. */
    public static final int DRAIN_PHASE = WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE;

    /** Kept under the phase timeout, so a worker reports and hands its work over before Spring stops waiting. */
    static final Duration MARGIN = Duration.ofSeconds(5);

    private GracefulShutdown() {
    }

    /** How long a worker may spend finishing work in progress, given the phase timeout. */
    public static Duration drainBudget(Duration phaseTimeout) {
        Duration budget = phaseTimeout.minus(MARGIN);
        return budget.compareTo(Duration.ofSeconds(1)) < 0 ? Duration.ofSeconds(1) : budget;
    }
}
