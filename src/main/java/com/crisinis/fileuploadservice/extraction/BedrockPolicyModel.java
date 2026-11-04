package com.crisinis.fileuploadservice.extraction;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import com.crisinis.fileuploadservice.config.FileUploadServiceProperties;
import com.crisinis.fileuploadservice.model.FileType;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.AccessDeniedException;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.DocumentFormat;
import software.amazon.awssdk.services.bedrockruntime.model.ImageFormat;
import software.amazon.awssdk.services.bedrockruntime.model.InternalServerException;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.ModelErrorException;
import software.amazon.awssdk.services.bedrockruntime.model.ModelNotReadyException;
import software.amazon.awssdk.services.bedrockruntime.model.ModelTimeoutException;
import software.amazon.awssdk.services.bedrockruntime.model.ResourceNotFoundException;
import software.amazon.awssdk.services.bedrockruntime.model.ServiceQuotaExceededException;
import software.amazon.awssdk.services.bedrockruntime.model.ServiceUnavailableException;
import software.amazon.awssdk.services.bedrockruntime.model.StopReason;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ThrottlingException;
import software.amazon.awssdk.services.bedrockruntime.model.ValidationException;

/**
 * The insurance-policy model on Amazon Bedrock, called with the Converse API: the prompt as the
 * system message, and the document as an image (PNG, JPEG, GIF, WebP) or a PDF. It reaches Bedrock
 * Runtime through its VPC endpoint, not the outbound proxy, like the other AWS services.
 *
 * <p>Bedrock's limits decide what can be extracted ({@link #accepts}); uploads that opt in are
 * checked against them, so an extraction never fails for a reason known at upload.
 */
@Component
public class BedrockPolicyModel {

    /** Bedrock's limit per image, rounded down to decimal megabytes to stay inside it (3.75 MB). */
    static final long MAX_IMAGE_BYTES = 3_750_000;
    /** Bedrock's limit per document (4.5 MB), likewise. */
    static final long MAX_DOCUMENT_BYTES = 4_500_000;
    private static final Map<FileType, ImageFormat> IMAGE_FORMATS = Map.of(
            FileType.PNG, ImageFormat.PNG, FileType.JPEG, ImageFormat.JPEG,
            FileType.GIF, ImageFormat.GIF, FileType.WEBP, ImageFormat.WEBP);
    private static final String INSTRUCTION =
            "Read the insurance policy in this document and answer with the JSON object only.";

    /**
     * The model's answer.
     *
     * @param text         what it said
     * @param inputTokens  tokens it read (cost)
     * @param outputTokens tokens it wrote (cost)
     */
    public record Answer(String text, int inputTokens, int outputTokens) {
    }

    private final BedrockRuntimeClient bedrock;
    private final FileUploadServiceProperties.Extraction settings;
    private final String prompt;

    public BedrockPolicyModel(BedrockRuntimeClient bedrock, FileUploadServiceProperties properties) {
        this.bedrock = bedrock;
        this.settings = properties.extraction();
        this.prompt = loadPrompt();
    }

    /**
     * Why Bedrock couldn't take a {@code type} file of {@code size} bytes; empty when it can.
     * HEIC and TIFF aren't formats Bedrock reads.
     */
    public static Optional<String> refusal(FileType type, long size) {
        if (type == FileType.PDF) {
            return size <= MAX_DOCUMENT_BYTES ? Optional.empty()
                    : Optional.of("a PDF for extraction must be at most " + MAX_DOCUMENT_BYTES + " bytes, was " + size);
        }
        if (!IMAGE_FORMATS.containsKey(type)) {
            return Optional.of("extraction reads PNG, JPEG, GIF, WebP and PDF files, not " + type.mediaType());
        }
        return size <= MAX_IMAGE_BYTES ? Optional.empty()
                : Optional.of("an image for extraction must be at most " + MAX_IMAGE_BYTES + " bytes, was " + size);
    }

    /** Whether Bedrock can take a {@code type} file of {@code size} bytes. */
    public static boolean accepts(FileType type, long size) {
        return refusal(type, size).isEmpty();
    }

    /** Asks the model to read the policy in {@code content}. */
    public Answer read(FileType type, byte[] content) {
        ConverseResponse response;
        try {
            response = bedrock.converse(request(type, content));
        } catch (SdkException e) {
            throw classify(e);
        }
        if (response.stopReason() == StopReason.MAX_TOKENS) {
            throw new ExtractionException(false, "ANSWER_TRUNCATED",
                    "the answer reached extraction.max-tokens (" + settings.maxTokens() + ")", null);
        }
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : response.output().message().content()) {
            if (block.text() != null) {
                text.append(block.text());
            }
        }
        return new Answer(text.toString(), tokens(response.usage() == null ? null : response.usage().inputTokens()),
                tokens(response.usage() == null ? null : response.usage().outputTokens()));
    }

    /** The configured model. */
    public String modelId() {
        return settings.modelId();
    }

    ConverseRequest request(FileType type, byte[] content) {
        SdkBytes bytes = SdkBytes.fromByteArray(content);
        ContentBlock document = type == FileType.PDF
                // a neutral name: the model reads it, so it mustn't carry the uploader's file name
                ? ContentBlock.fromDocument(d -> d.format(DocumentFormat.PDF).name("policy document").source(s -> s.bytes(bytes)))
                : ContentBlock.fromImage(i -> i.format(IMAGE_FORMATS.get(type)).source(s -> s.bytes(bytes)));
        return ConverseRequest.builder()
                .modelId(settings.modelId())
                .system(SystemContentBlock.fromText(prompt))
                .messages(Message.builder().role("user").content(document, ContentBlock.fromText(INSTRUCTION)).build())
                .inferenceConfig(c -> c.maxTokens(settings.maxTokens()).temperature(0f))
                .build();
    }

    /** Temporary failures retry; the rest can't succeed by trying again. */
    static ExtractionException classify(SdkException e) {
        return switch (e) {
            case ThrottlingException t -> new ExtractionException(true, "MODEL_THROTTLED", e.getMessage(), e);
            case ServiceQuotaExceededException q -> new ExtractionException(true, "MODEL_QUOTA_EXCEEDED", e.getMessage(), e);
            case ModelTimeoutException t -> new ExtractionException(true, "MODEL_TIMEOUT", e.getMessage(), e);
            case ModelNotReadyException r -> new ExtractionException(true, "MODEL_NOT_READY", e.getMessage(), e);
            case ServiceUnavailableException u -> new ExtractionException(true, "BEDROCK_UNAVAILABLE", e.getMessage(), e);
            case InternalServerException i -> new ExtractionException(true, "BEDROCK_INTERNAL_ERROR", e.getMessage(), e);
            case ModelErrorException m -> new ExtractionException(true, "MODEL_ERROR", e.getMessage(), e);
            case AccessDeniedException a -> new ExtractionException(false, "MODEL_ACCESS_DENIED", e.getMessage(), e);
            case ResourceNotFoundException n -> new ExtractionException(false, "MODEL_NOT_FOUND", e.getMessage(), e);
            case ValidationException v -> new ExtractionException(false, "MODEL_REFUSED_REQUEST", e.getMessage(), e);
            // network failures and the SDK's own timeout
            case SdkClientException c -> new ExtractionException(true, "BEDROCK_UNREACHABLE", e.getMessage(), e);
            default -> new ExtractionException(true, "BEDROCK_ERROR", e.getMessage(), e);
        };
    }

    private static int tokens(Integer count) {
        return count == null ? 0 : count;
    }

    private static String loadPrompt() {
        try (InputStream in = BedrockPolicyModel.class.getResourceAsStream("/extraction/insurance-policy-prompt.txt")) {
            if (in == null) {
                throw new IllegalStateException("extraction/insurance-policy-prompt.txt is missing from the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
