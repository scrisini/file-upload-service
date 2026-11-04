package com.crisinis.fileuploadservice.extraction;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.zip.CRC32;

import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeServiceClientConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.StopReason;
import software.amazon.awssdk.services.bedrockruntime.model.ThrottlingException;
import software.amazon.awssdk.services.bedrockruntime.model.ValidationException;

/**
 * Local development only ({@code extraction.simulator.enabled}; production mode refuses it): a
 * Bedrock Runtime client that answers Converse with an invented policy, so the whole extraction path
 * (request building, answer parsing, retries) runs without AWS. The policy number is derived from the
 * document's bytes, so different files give different answers.
 *
 * <p>Markers in the document's bytes make it misbehave, for tests: {@code BEDROCK-THROTTLE} throws
 * Bedrock's throttling error every time, {@code BEDROCK-GARBAGE} answers with prose instead of JSON,
 * {@code BEDROCK-REFUSE} throws a validation error (permanent).
 */
public final class SimulatedBedrock implements BedrockRuntimeClient {

    private final Duration delay;

    public SimulatedBedrock(Duration delay) {
        this.delay = delay;
    }

    @Override
    public ConverseResponse converse(ConverseRequest request) {
        byte[] document = document(request);
        pause();
        if (contains(document, "BEDROCK-THROTTLE")) {
            throw ThrottlingException.builder().message("Too many requests (simulated)").statusCode(429).build();
        }
        if (contains(document, "BEDROCK-REFUSE")) {
            throw ValidationException.builder().message("The model can't process this input (simulated)").statusCode(400).build();
        }
        String answer = contains(document, "BEDROCK-GARBAGE")
                ? "I'm sorry, I can't read this document clearly."
                : policy(document);
        return ConverseResponse.builder()
                .output(ConverseOutput.fromMessage(Message.builder().role("assistant").content(ContentBlock.fromText(answer)).build()))
                .stopReason(StopReason.END_TURN)
                .usage(u -> u.inputTokens(1500 + document.length / 1000).outputTokens(420).totalTokens(1920 + document.length / 1000))
                .build();
    }

    private static String policy(byte[] document) {
        CRC32 crc = new CRC32();
        crc.update(document);
        String number = "SIM-" + HexFormat.of().toHexDigits((int) crc.getValue()).toUpperCase();
        return """
                {
                  "insurer": {"value": "Simulated Mutual Insurance", "confidence": 0.98},
                  "policyNumber": {"value": "%s", "confidence": 0.97},
                  "policyType": {"value": "HOME_AND_CONTENTS", "confidence": 0.93},
                  "holder": {
                    "name": {"value": "Jane Citizen", "confidence": 0.96},
                    "dateOfBirth": {"value": "1984-07-21", "confidence": 0.88},
                    "address": {"value": "1 Example Street, Sydney NSW 2000", "confidence": 0.91}
                  },
                  "insuredPersons": [
                    {"name": {"value": "John Citizen", "confidence": 0.9}, "dateOfBirth": {"value": "1982-03-02", "confidence": 0.85}}
                  ],
                  "startDate": {"value": "2026-07-01", "confidence": 0.95},
                  "endDate": {"value": "2027-06-30", "confidence": 0.95},
                  "currency": {"value": "AUD", "confidence": 0.99},
                  "premium": {"value": "1842.60", "confidence": 0.94},
                  "premiumFrequency": {"value": "ANNUAL", "confidence": 0.9},
                  "sumInsured": {"value": "850000", "confidence": 0.92},
                  "excess": {"value": "500.00", "confidence": 0.89}
                }""".formatted(number);
    }

    private static byte[] document(ConverseRequest request) {
        for (Message message : request.messages()) {
            for (ContentBlock block : message.content()) {
                if (block.image() != null) {
                    return block.image().source().bytes().asByteArray();
                }
                if (block.document() != null) {
                    return block.document().source().bytes().asByteArray();
                }
            }
        }
        throw ValidationException.builder().message("no image or document in the request (simulated)").statusCode(400).build();
    }

    private static boolean contains(byte[] content, String marker) {
        byte[] needle = marker.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i <= content.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (content[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private void pause() {
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public String serviceName() {
        return SERVICE_NAME;
    }

    @Override
    public BedrockRuntimeServiceClientConfiguration serviceClientConfiguration() {
        throw new UnsupportedOperationException("the simulated Bedrock client has no configuration");
    }

    @Override
    public void close() {
        // nothing to release
    }
}
