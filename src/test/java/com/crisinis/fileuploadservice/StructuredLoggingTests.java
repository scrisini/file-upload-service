package com.crisinis.fileuploadservice;

import com.crisinis.fileuploadservice.logging.LogEvent;
import com.crisinis.fileuploadservice.logging.RequestIds;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The logging standard end to end, in ECS JSON mode: a caller's request id reaches the coded log
 * line and comes back in the error response, and every line on stdout is JSON for log processors.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "logging.structured.format.console=ecs")
@ExtendWith(OutputCaptureExtension.class)
@Import(StructuredLoggingTests.FailingController.class)
class StructuredLoggingTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** An endpoint that fails the way a broken dependency would. */
    @RestController
    static class FailingController {
        @GetMapping("/test/fail")
        String fail() {
            throw new IllegalStateException("database unreachable");
        }
    }

    @Autowired
    Environment environment;

    /**
     * Spring Boot configures logging once per JVM, so a context another test class started first
     * would leave text logging in place for this one. Resetting the logging system makes this
     * context configure it as ECS JSON, and afterwards lets the next context configure its own.
     */
    @BeforeAll
    static void reconfigureLoggingForThisContext() {
        resetLogging();
    }

    @AfterAll
    static void reconfigureLoggingForTheNextContext() {
        resetLogging();
    }

    private static void resetLogging() {
        LoggingSystem.get(StructuredLoggingTests.class.getClassLoader()).cleanUp();
    }

    @Test
    void anUnexpectedFailureIsCodedTracedToTheRequestAndEveryLineIsJson(CapturedOutput output) throws Exception {
        int port = environment.getProperty("local.server.port", Integer.class);
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/test/fail"))
                        .header(RequestIds.HEADER, "support-ticket-7").build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.headers().firstValue(RequestIds.HEADER)).contains("support-ticket-7");
        assertThat(JSON.readValue(response.body(), Map.class)).containsEntry("requestId", "support-ticket-7");

        // The test framework logs while bootstrapping, before this context configures logging;
        // from the application's first line on, every line must be JSON
        List<Map<String, Object>> lines = output.getOut().lines()
                .filter(line -> !line.isBlank())
                .dropWhile(line -> !line.startsWith("{"))
                .map(StructuredLoggingTests::parseFlattened)
                .toList();

        Map<String, Object> failure = withCode(lines, LogEvent.UNEXPECTED_ERROR.code());
        assertThat(failure)
                .containsEntry("log.level", "ERROR")
                .containsEntry("http.request.id", "support-ticket-7")
                .containsEntry("event.reference", LogEvent.UNEXPECTED_ERROR.reference())
                .containsEntry("file-upload-service.path", "/test/fail")
                .containsEntry("error.type", IllegalStateException.class.getName());
        assertThat((String) failure.get("message")).startsWith("[" + LogEvent.UNEXPECTED_ERROR.code() + "] ");

        assertThat(withCode(lines, LogEvent.SERVICE_STARTED.code()))
                .containsEntry("file-upload-service.port", port);
    }

    private static Map<String, Object> withCode(List<Map<String, Object>> lines, String code) {
        return lines.stream().filter(line -> code.equals(line.get("event.code"))).findFirst()
                .orElseThrow(() -> new AssertionError("no log line with event.code " + code));
    }

    /** Parses one stdout line; fails if it isn't JSON. Nested ECS objects become dotted keys. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseFlattened(String line) {
        Map<String, Object> flat = new HashMap<>();
        flatten("", JSON.readValue(line, Map.class), flat);
        return flat;
    }

    @SuppressWarnings("unchecked")
    private static void flatten(String prefix, Map<String, Object> json, Map<String, Object> flat) {
        json.forEach((key, value) -> {
            String name = prefix.isEmpty() ? key : prefix + "." + key;
            if (value instanceof Map<?, ?> nested) {
                flatten(name, (Map<String, Object>) nested, flat);
            } else {
                flat.put(name, value);
            }
        });
    }
}
