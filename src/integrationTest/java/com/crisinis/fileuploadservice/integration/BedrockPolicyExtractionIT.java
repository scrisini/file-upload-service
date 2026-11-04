package com.crisinis.fileuploadservice.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import com.crisinis.fileuploadservice.extraction.BedrockPolicyModel;
import com.crisinis.fileuploadservice.extraction.PolicyParser;
import com.crisinis.fileuploadservice.model.FileType;
import com.crisinis.fileuploadservice.model.Money;
import com.crisinis.fileuploadservice.model.PolicyExtraction;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * The real model on Amazon Bedrock reads a synthetic policy. Needs {@link RealAws#REGION},
 * {@link RealAws#MODEL_ID} and {@code bedrock:InvokeModel} on that model. Costs one model call.
 */
class BedrockPolicyExtractionIT {

    static BedrockRuntimeClient bedrock;
    static BedrockPolicyModel model;

    @BeforeAll
    static void connect() {
        String modelId = RealAws.require(RealAws.MODEL_ID);
        bedrock = BedrockRuntimeClient.builder().region(RealAws.region())
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(90))).build();
        model = new BedrockPolicyModel(bedrock, RealAws.properties(Map.of("file-upload-service.extraction.model-id", modelId)));
    }

    @AfterAll
    static void close() {
        if (bedrock != null) {
            bedrock.close();
        }
    }

    @Test
    void theModelReadsAPolicySchedule() {
        String policyNumber = "IT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        BedrockPolicyModel.Answer answer = model.read(FileType.PNG, PolicyDocument.png(policyNumber));
        PolicyExtraction policy = new PolicyParser(JsonMapper.builder().build()).parse(answer.text());

        assertThat(answer.inputTokens()).isPositive();
        assertThat(policy.policyNumber().value()).isEqualTo(policyNumber);
        assertThat(policy.holder().name().value()).containsIgnoringCase("Alex Example");
        assertThat(policy.startDate().value()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(policy.endDate().value()).isEqualTo(LocalDate.of(2027, 7, 31));
        assertThat(policy.currency().value()).isEqualTo("AUD");
        assertThat(policy.premium().value()).isEqualTo(new Money(145680, "AUD", 2));
        assertThat(policy.sumInsured().value()).isEqualTo(new Money(65000000, "AUD", 2));
        assertThat(policy.excess().value()).isEqualTo(new Money(75000, "AUD", 2));
        assertThat(policy.policyNumber().confidence()).isGreaterThan(0.5);
    }
}
