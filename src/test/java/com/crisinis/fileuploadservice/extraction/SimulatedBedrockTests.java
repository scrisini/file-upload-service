package com.crisinis.fileuploadservice.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;

import com.crisinis.fileuploadservice.model.FileType;
import com.crisinis.fileuploadservice.model.PolicyExtraction;
import com.crisinis.fileuploadservice.support.TestProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The local stand-in answers through the real request building and parsing. */
class SimulatedBedrockTests {

    private final BedrockPolicyModel model = new BedrockPolicyModel(new SimulatedBedrock(Duration.ZERO),
            TestProperties.of(Map.of("file-upload-service.extraction.model-id", "simulated")));
    private final PolicyParser parser = new PolicyParser(JsonMapper.builder().build());

    @Test
    void answersWithAPolicyThatParsesAndDiffersPerFile() {
        PolicyExtraction first = parser.parse(model.read(FileType.PNG, "first file".getBytes()).text());
        PolicyExtraction second = parser.parse(model.read(FileType.PDF, "second file".getBytes()).text());

        assertThat(first.warnings()).isEmpty();
        assertThat(first.fieldsFound()).isGreaterThan(10);
        assertThat(first.policyNumber().value()).startsWith("SIM-").isNotEqualTo(second.policyNumber().value());
    }

    @Test
    void markersMakeItMisbehaveForTests() {
        assertThatThrownBy(() -> model.read(FileType.PNG, "x BEDROCK-THROTTLE x".getBytes()))
                .isInstanceOfSatisfying(ExtractionException.class, e -> assertThat(e.reason()).isEqualTo("MODEL_THROTTLED"));
        assertThatThrownBy(() -> model.read(FileType.PNG, "x BEDROCK-REFUSE x".getBytes()))
                .isInstanceOfSatisfying(ExtractionException.class, e -> assertThat(e.retryable()).isFalse());
        String garbage = model.read(FileType.PNG, "x BEDROCK-GARBAGE x".getBytes()).text();
        assertThatThrownBy(() -> parser.parse(garbage)).isInstanceOf(ExtractionException.class);
    }
}
