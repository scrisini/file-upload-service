package com.crisinis.fileuploadservice.config;

import java.net.URI;
import java.time.Duration;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * The AWS SDK clients, sharing one HTTP client. They reach AWS through VPC endpoints, not the
 * outbound proxy (README "Outbound proxy"). Retries are the SDK's standard mode: up to 3 attempts
 * with jittered backoff. A streamed upload's body can't be replayed, so its PUT isn't retried.
 */
@Configuration(proxyBeanMethods = false)
class AwsClients {

    /** SQS long polls for 20 s; reads must outlast that. */
    private static final Duration SOCKET_TIMEOUT = Duration.ofSeconds(30);

    @Bean(destroyMethod = "close")
    SdkHttpClient awsHttpClient() {
        return UrlConnectionHttpClient.builder()
                .connectionTimeout(Duration.ofSeconds(2))
                .socketTimeout(SOCKET_TIMEOUT)
                .build();
    }

    @Bean
    AwsCallMetrics awsCallMetrics(MeterRegistry registry) {
        return new AwsCallMetrics(registry);
    }

    @Bean(destroyMethod = "close")
    S3Client s3Client(FileUploadServiceProperties properties, SdkHttpClient http, AwsCallMetrics metrics) {
        FileUploadServiceProperties.Aws aws = properties.aws();
        return configure(S3Client.builder(), aws, aws.s3Endpoint(), metrics)
                .httpClient(http)
                // emulators serve buckets as paths, not host names
                .forcePathStyle(!aws.s3Endpoint().isEmpty())
                .build();
    }

    @Bean(destroyMethod = "close")
    S3Presigner s3Presigner(FileUploadServiceProperties properties) {
        FileUploadServiceProperties.Aws aws = properties.aws();
        // browsers use these URLs, so they carry the address browsers reach S3 by
        String endpoint = aws.presignEndpoint();
        S3Presigner.Builder builder = S3Presigner.builder()
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(!endpoint.isEmpty()).build());
        if (!aws.region().isEmpty()) {
            builder.region(Region.of(aws.region()));
        }
        if (!endpoint.isEmpty()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }

    @Bean(destroyMethod = "close")
    SqsClient sqsClient(FileUploadServiceProperties properties, SdkHttpClient http, AwsCallMetrics metrics) {
        FileUploadServiceProperties.Aws aws = properties.aws();
        // a receive waits up to 20 s for messages
        return configure(SqsClient.builder(), aws, aws.sqsEndpoint(), metrics, SOCKET_TIMEOUT)
                .httpClient(http).build();
    }

    @Bean(destroyMethod = "close")
    DynamoDbClient dynamoDbClient(FileUploadServiceProperties properties, SdkHttpClient http, AwsCallMetrics metrics) {
        FileUploadServiceProperties.Aws aws = properties.aws();
        return configure(DynamoDbClient.builder(), aws, aws.dynamodbEndpoint(), metrics).httpClient(http).build();
    }

    private static <B extends AwsClientBuilder<B, ?>> B configure(B builder, FileUploadServiceProperties.Aws aws,
                                                                    String endpoint, AwsCallMetrics metrics) {
        return configure(builder, aws, endpoint, metrics, aws.timeout());
    }

    private static <B extends AwsClientBuilder<B, ?>> B configure(B builder, FileUploadServiceProperties.Aws aws,
                                                                    String endpoint, AwsCallMetrics metrics, Duration timeout) {
        builder.overrideConfiguration(ClientOverrideConfiguration.builder()
                .apiCallTimeout(timeout)
                .addExecutionInterceptor(metrics)
                .build());
        if (!aws.region().isEmpty()) {
            builder.region(Region.of(aws.region()));
        }
        if (!endpoint.isEmpty()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder;
    }
}
