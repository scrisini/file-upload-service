package com.crisinis.fileuploadservice.config;

import java.net.Proxy;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.crisinis.fileuploadservice.model.FileType;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Service settings bound from the {@code file-upload-service.*} namespace. Add every new setting
 * here with a {@code @DefaultValue}, then document it in {@code application.yml} and the README.
 * Validate each value here (in the record's compact constructor), not in the bean that uses it: the
 * configuration check ({@code ConfigCheck}) finds a stored value that would stop a restart by
 * binding these records.
 *
 * @param outboundProxy proxy for all outgoing connections to external hosts
 * @param mode          {@code production} refuses settings that are unsafe there ({@link ProductionSafeguards})
 * @param aws           region and endpoint overrides for S3, SQS and DynamoDB
 * @param storage       the quarantine and clean buckets, and the table of file records
 * @param uploads       what callers may upload, and how
 * @param scan          GuardDuty's results: the queue they arrive on, and when a scan is overdue
 */
@ConfigurationProperties("file-upload-service")
public record FileUploadServiceProperties(
        @DefaultValue OutboundProxy outboundProxy,
        @DefaultValue("development") Mode mode,
        @DefaultValue Aws aws,
        @DefaultValue Storage storage,
        @DefaultValue Uploads uploads,
        @DefaultValue Scan scan) {

    private static final Pattern BUCKET = Pattern.compile("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]");
    private static final Pattern PREFIX = Pattern.compile("([A-Za-z0-9!_.*'()-]+/)*");
    private static final Pattern TABLE = Pattern.compile("[A-Za-z0-9_.-]{3,255}");
    /** CopyObject copies at most 5 GB in one request; promotion relies on it. */
    private static final DataSize COPY_LIMIT = DataSize.ofGigabytes(5);

    /** Where the instance runs. Production refuses unsafe settings; development allows local defaults. */
    public enum Mode {
        DEVELOPMENT, PRODUCTION;

        /** As configured and shown: {@code development}, {@code production}. */
        @Override
        public String toString() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * Every client that connects to an external host must use this proxy when {@code host} is set,
     * and must refuse to start rather than connect directly if it can't (see AGENTS.md).
     *
     * @param type {@code HTTP} (HTTP CONNECT, e.g. tinyproxy or Squid) or {@code SOCKS} (SOCKS5)
     * @param host proxy host; blank connects directly
     * @param port proxy port
     */
    public record OutboundProxy(
            @DefaultValue("HTTP") Proxy.Type type,
            String host,
            @DefaultValue("8888") int port) {

        public OutboundProxy {
            host = host == null ? "" : host.trim();
            if (type == Proxy.Type.DIRECT) {
                throw new IllegalArgumentException(
                        "file-upload-service.outbound-proxy.type must be HTTP or SOCKS; leave host blank to connect directly");
            }
            if (!host.isEmpty() && (port < 1 || port > 65535)) {
                throw new IllegalArgumentException(
                        "file-upload-service.outbound-proxy.port must be between 1 and 65535, was " + port);
            }
        }

        public boolean enabled() {
            return !host.isEmpty();
        }

        @Override
        public String toString() {
            return enabled() ? type + " " + host + ":" + port : "none";
        }
    }

    /**
     * Where the AWS SDK sends requests. Credentials come from the SDK's default chain (EKS Pod
     * Identity or IRSA when deployed). S3, SQS and DynamoDB are reached through VPC endpoints, like
     * Parameter Store, not through the outbound proxy.
     *
     * @param region           blank for the SDK's default chain ({@code AWS_REGION})
     * @param s3Endpoint       override, e.g. a local S3 emulator; blank for AWS. Also the host in presigned URLs
     * @param sqsEndpoint      override for SQS; blank for AWS
     * @param dynamodbEndpoint override for DynamoDB; blank for AWS
     * @param timeout          limit for each call except a streamed upload's PUT
     */
    public record Aws(
            @DefaultValue("") String region,
            @DefaultValue("") String s3Endpoint,
            @DefaultValue("") String sqsEndpoint,
            @DefaultValue("") String dynamodbEndpoint,
            @DefaultValue("10s") Duration timeout) {

        public Aws {
            region = trim(region);
            s3Endpoint = endpoint("s3-endpoint", s3Endpoint);
            sqsEndpoint = endpoint("sqs-endpoint", sqsEndpoint);
            dynamodbEndpoint = endpoint("dynamodb-endpoint", dynamodbEndpoint);
            positive("aws.timeout", timeout);
        }

        /** Whether any service is pointed away from AWS (an emulator): never in production. */
        public boolean overridden() {
            return !s3Endpoint.isEmpty() || !sqsEndpoint.isEmpty() || !dynamodbEndpoint.isEmpty();
        }

        private static String endpoint(String name, String value) {
            value = trim(value);
            if (!value.isEmpty()) {
                URI uri = URI.create(value);
                if (uri.getScheme() == null || uri.getHost() == null) {
                    throw new IllegalArgumentException(
                            "file-upload-service.aws." + name + " must be a URL like http://localhost:9099, was '" + value + "'");
                }
            }
            return value;
        }
    }

    /**
     * Where files live. Uploads land in {@code quarantineBucket}, which GuardDuty Malware Protection
     * protects; files scanned clean are copied to {@code cleanBucket} and deleted from quarantine.
     * Infected and unscanned files stay in quarantine, where a lifecycle rule expires them.
     *
     * @param quarantineBucket bucket with the GuardDuty Malware Protection plan
     * @param quarantinePrefix "folder" for uploads, ending in {@code /}; the protection plan may be limited to it
     * @param cleanBucket      bucket for files that passed; may be the quarantine bucket with another prefix
     * @param cleanPrefix      "folder" for files that passed, ending in {@code /}
     * @param table            DynamoDB table of file records (docs/deployment.md "AWS resources")
     */
    public record Storage(
            @DefaultValue("file-upload-quarantine") String quarantineBucket,
            @DefaultValue("uploads/") String quarantinePrefix,
            @DefaultValue("file-upload-clean") String cleanBucket,
            @DefaultValue("files/") String cleanPrefix,
            @DefaultValue("file-upload-service-files") String table) {

        public Storage {
            quarantineBucket = bucket("quarantine-bucket", quarantineBucket);
            cleanBucket = bucket("clean-bucket", cleanBucket);
            quarantinePrefix = prefix("quarantine-prefix", quarantinePrefix);
            cleanPrefix = prefix("clean-prefix", cleanPrefix);
            if (quarantineBucket.equals(cleanBucket)
                    && (quarantinePrefix.startsWith(cleanPrefix) || cleanPrefix.startsWith(quarantinePrefix))) {
                throw new IllegalArgumentException("file-upload-service.storage: in one bucket, the quarantine and "
                        + "clean prefixes must not contain each other, or clean files would be scanned and served from quarantine");
            }
            table = trim(table);
            if (!TABLE.matcher(table).matches()) {
                throw new IllegalArgumentException("file-upload-service.storage.table must be a DynamoDB table name, was '" + table + "'");
            }
        }

        private static String bucket(String name, String value) {
            value = trim(value);
            if (!BUCKET.matcher(value).matches()) {
                throw new IllegalArgumentException(
                        "file-upload-service.storage." + name + " must be an S3 bucket name, was '" + value + "'");
            }
            return value;
        }

        private static String prefix(String name, String value) {
            value = trim(value);
            if (value.isEmpty() || !value.endsWith("/") || !PREFIX.matcher(value).matches()) {
                throw new IllegalArgumentException(
                        "file-upload-service.storage." + name + " must be a key prefix ending in '/', e.g. uploads/, was '" + value + "'");
            }
            return value;
        }
    }

    /**
     * What callers may upload.
     *
     * @param maxSize              largest file accepted, by either style
     * @param allowedTypes         types accepted; the declared media type and the content must agree
     * @param urlTtl               how long a presigned upload URL works
     * @param maxConcurrentStreams streamed uploads one instance handles at once; more get 503 with Retry-After.
     *                             Bounds the memory and threads streaming uses
     * @param streamTimeout        limit for streaming one file to S3, which runs at the caller's speed
     */
    public record Uploads(
            @DefaultValue("25MB") DataSize maxSize,
            @DefaultValue({"JPEG", "PNG", "GIF", "WEBP", "HEIC", "TIFF", "PDF"}) List<FileType> allowedTypes,
            @DefaultValue("15m") Duration urlTtl,
            @DefaultValue("64") int maxConcurrentStreams,
            @DefaultValue("5m") Duration streamTimeout) {

        public Uploads {
            if (maxSize == null || maxSize.toBytes() < 1 || maxSize.compareTo(COPY_LIMIT) > 0) {
                throw new IllegalArgumentException(
                        "file-upload-service.uploads.max-size must be between 1B and 5GB (S3's single-copy limit), was " + maxSize);
            }
            if (allowedTypes == null || allowedTypes.isEmpty()) {
                throw new IllegalArgumentException("file-upload-service.uploads.allowed-types must name at least one of "
                        + List.of(FileType.values()));
            }
            allowedTypes = List.copyOf(allowedTypes);
            positive("uploads.url-ttl", urlTtl);
            if (urlTtl.compareTo(Duration.ofDays(7)) > 0) {
                throw new IllegalArgumentException("file-upload-service.uploads.url-ttl must be at most 7d (SigV4's limit), was " + urlTtl);
            }
            if (maxConcurrentStreams < 1) {
                throw new IllegalArgumentException(
                        "file-upload-service.uploads.max-concurrent-streams must be at least 1, was " + maxConcurrentStreams);
            }
            positive("uploads.stream-timeout", streamTimeout);
        }
    }

    /**
     * GuardDuty's results, which EventBridge puts on an SQS queue with S3's "Object Created" events.
     *
     * @param queueUrl        the SQS queue; blank turns event handling and the scan monitor off
     * @param consumers       threads receiving from the queue on each instance
     * @param overdueAfter    a scan with no result after this long is reported (2009)
     * @param monitorInterval how often the count of scans in progress is refreshed, and overdue scans looked for
     * @param simulator       a stand-in for GuardDuty on a laptop; refused in production
     */
    public record Scan(
            @DefaultValue("") String queueUrl,
            @DefaultValue("2") int consumers,
            @DefaultValue("5m") Duration overdueAfter,
            @DefaultValue("15s") Duration monitorInterval,
            @DefaultValue Simulator simulator) {

        public Scan {
            queueUrl = trim(queueUrl);
            if (!queueUrl.isEmpty() && URI.create(queueUrl).getHost() == null) {
                throw new IllegalArgumentException("file-upload-service.scan.queue-url must be the queue's URL, was '" + queueUrl + "'");
            }
            if (consumers < 1 || consumers > 32) {
                throw new IllegalArgumentException("file-upload-service.scan.consumers must be between 1 and 32, was " + consumers);
            }
            positive("scan.overdue-after", overdueAfter);
            positive("scan.monitor-interval", monitorInterval);
        }

        public boolean enabled() {
            return !queueUrl.isEmpty();
        }
    }

    /**
     * Local development only: watches the quarantine bucket and sends the events S3 and GuardDuty
     * would send, so the whole flow runs without AWS. A file containing the EICAR test string is
     * reported infected. Production mode refuses it.
     *
     * @param enabled whether to run it
     * @param delay   how long a simulated scan takes
     */
    public record Simulator(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("3s") Duration delay) {

        public Simulator {
            if (delay == null || delay.isNegative()) {
                throw new IllegalArgumentException("file-upload-service.scan.simulator.delay must not be negative, was " + delay);
            }
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static void positive(String name, Duration value) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("file-upload-service." + name + " must be positive, was " + value);
        }
    }
}
