package com.crisinis.fileuploadservice.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import com.crisinis.fileuploadservice.model.FileType;
import com.crisinis.fileuploadservice.support.TestProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.AccessDeniedException;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.DocumentFormat;
import software.amazon.awssdk.services.bedrockruntime.model.ImageFormat;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.ModelTimeoutException;
import software.amazon.awssdk.services.bedrockruntime.model.ResourceNotFoundException;
import software.amazon.awssdk.services.bedrockruntime.model.ServiceUnavailableException;
import software.amazon.awssdk.services.bedrockruntime.model.StopReason;
import software.amazon.awssdk.services.bedrockruntime.model.ThrottlingException;
import software.amazon.awssdk.services.bedrockruntime.model.ValidationException;

/** The Bedrock SDK is mocked: these tests never reach AWS. */
class BedrockPolicyModelTests {

    static final String MODEL = "arn:aws:bedrock:ap-southeast-2:123456789012:provisioned-model/abc123";
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    private final BedrockRuntimeClient bedrock = mock(BedrockRuntimeClient.class);
    private final BedrockPolicyModel model = new BedrockPolicyModel(bedrock,
            TestProperties.of(Map.of("file-upload-service.extraction.model-id", MODEL)));

    static ConverseResponse answer(StopReason stop, String... texts) {
        ContentBlock[] blocks = new ContentBlock[texts.length];
        for (int i = 0; i < texts.length; i++) {
            blocks[i] = ContentBlock.fromText(texts[i]);
        }
        return ConverseResponse.builder()
                .output(ConverseOutput.fromMessage(Message.builder().role("assistant").content(blocks).build()))
                .stopReason(stop).usage(u -> u.inputTokens(1200).outputTokens(300).totalTokens(1500)).build();
    }

    @Test
    void sendsAnImageWithThePromptToTheConfiguredModel() {
        ArgumentCaptor<ConverseRequest> request = ArgumentCaptor.forClass(ConverseRequest.class);
        when(bedrock.converse(request.capture())).thenReturn(answer(StopReason.END_TURN, "{\"a\":", "1}"));

        BedrockPolicyModel.Answer answer = model.read(FileType.PNG, PNG);

        ConverseRequest sent = request.getValue();
        assertThat(sent.modelId()).isEqualTo(MODEL);
        assertThat(sent.system().getFirst().text()).contains("insurance policy").contains("JSON object only");
        assertThat(sent.inferenceConfig().maxTokens()).isEqualTo(2000);
        assertThat(sent.inferenceConfig().temperature()).isZero();
        Message message = sent.messages().getFirst();
        assertThat(message.roleAsString()).isEqualTo("user");
        assertThat(message.content().get(0).image().format()).isEqualTo(ImageFormat.PNG);
        assertThat(message.content().get(0).image().source().bytes().asByteArray()).isEqualTo(PNG);
        assertThat(message.content().get(1).text()).contains("JSON object only");
        assertThat(answer).isEqualTo(new BedrockPolicyModel.Answer("{\"a\":1}", 1200, 300));
    }

    @Test
    void sendsAPdfAsADocumentWithANeutralName() {
        ArgumentCaptor<ConverseRequest> request = ArgumentCaptor.forClass(ConverseRequest.class);
        when(bedrock.converse(request.capture())).thenReturn(answer(StopReason.END_TURN, "{}"));

        model.read(FileType.PDF, "%PDF-1.7".getBytes());

        ContentBlock document = request.getValue().messages().getFirst().content().getFirst();
        assertThat(document.document().format()).isEqualTo(DocumentFormat.PDF);
        // the uploader's file name could carry instructions to the model: never sent
        assertThat(document.document().name()).isEqualTo("policy document");
    }

    @Test
    void aTruncatedAnswerFailsForGood() {
        when(bedrock.converse(any(ConverseRequest.class))).thenReturn(answer(StopReason.MAX_TOKENS, "{\"insurer\":"));

        assertThatThrownBy(() -> model.read(FileType.PNG, PNG)).isInstanceOfSatisfying(ExtractionException.class, e -> {
            assertThat(e.retryable()).isFalse();
            assertThat(e.reason()).isEqualTo("ANSWER_TRUNCATED");
        });
    }

    static Object[][] errors() {
        return new Object[][] {
                {ThrottlingException.builder().message("slow down").build(), true, "MODEL_THROTTLED"},
                {ModelTimeoutException.builder().message("timeout").build(), true, "MODEL_TIMEOUT"},
                {ServiceUnavailableException.builder().message("unavailable").build(), true, "BEDROCK_UNAVAILABLE"},
                {SdkClientException.create("Unable to execute HTTP request"), true, "BEDROCK_UNREACHABLE"},
                {AccessDeniedException.builder().message("denied").build(), false, "MODEL_ACCESS_DENIED"},
                {ResourceNotFoundException.builder().message("no model").build(), false, "MODEL_NOT_FOUND"},
                {ValidationException.builder().message("image too large").build(), false, "MODEL_REFUSED_REQUEST"}};
    }

    @ParameterizedTest
    @MethodSource("errors")
    void temporaryErrorsRetryAndTheRestDont(SdkException error, boolean retryable, String reason) {
        when(bedrock.converse(any(ConverseRequest.class))).thenThrow(error);

        assertThatThrownBy(() -> model.read(FileType.JPEG, PNG)).isInstanceOfSatisfying(ExtractionException.class, e -> {
            assertThat(e.retryable()).isEqualTo(retryable);
            assertThat(e.reason()).isEqualTo(reason);
        });
    }

    @Test
    void knowsWhatBedrockCanTake() {
        assertThat(BedrockPolicyModel.accepts(FileType.PNG, 3_750_000)).isTrue();
        assertThat(BedrockPolicyModel.accepts(FileType.PNG, 3_750_001)).isFalse();
        assertThat(BedrockPolicyModel.accepts(FileType.PDF, 4_500_000)).isTrue();
        assertThat(BedrockPolicyModel.accepts(FileType.PDF, 4_500_001)).isFalse();
        assertThat(BedrockPolicyModel.refusal(FileType.HEIC, 10)).hasValueSatisfying(r -> assertThat(r).contains("image/heic"));
        assertThat(BedrockPolicyModel.accepts(FileType.TIFF, 10)).isFalse();
        assertThat(BedrockPolicyModel.accepts(FileType.WEBP, 10)).isTrue();
        assertThat(BedrockPolicyModel.accepts(FileType.GIF, 10)).isTrue();
    }
}
