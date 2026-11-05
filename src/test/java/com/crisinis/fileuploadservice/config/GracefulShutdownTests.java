package com.crisinis.fileuploadservice.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.context.WebServerGracefulShutdownLifecycle;

class GracefulShutdownTests {

    @Test
    void workersDrainWithTheWebServerAndInsideItsTimeout() {
        assertThat(GracefulShutdown.DRAIN_PHASE).isEqualTo(WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE);
        assertThat(GracefulShutdown.drainBudget(Duration.ofSeconds(30))).isEqualTo(Duration.ofSeconds(25));
        assertThat(GracefulShutdown.drainBudget(Duration.ofSeconds(3))).isEqualTo(Duration.ofSeconds(1));
    }
}
