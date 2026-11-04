package com.crisinis.fileuploadservice.logging;

import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.event.Level;

/**
 * The catalogue of things this service logs for operators and support. Each event has a stable
 * code ({@code FILE_UPLOAD_SERVICE-nnnn}), a fixed level, and a summary that says what happened, what it
 * means for callers and what happens next. Its SOP is the section with the same code in
 * {@code docs/runbook.md}.
 *
 * <p>Codes are a contract with support staff, dashboards and alert rules: never reuse or renumber
 * one. Add a new constant (and its runbook section; {@code LogEventCatalogueTests} checks) instead.
 *
 * <ul>
 *   <li>{@code 1xxx}: lifecycle (startup, shutdown)</li>
 *   <li>{@code 2xxx}: requests and business outcomes</li>
 *   <li>{@code 3xxx}: dependencies (database, brokers, external APIs)</li>
 *   <li>{@code 9xxx}: unexpected failures</li>
 * </ul>
 */
public enum LogEvent {

    SERVICE_STARTED("FILE_UPLOAD_SERVICE-1001", Level.INFO,
            "Service started and accepting requests"),
    CONFIG_LOADED("FILE_UPLOAD_SERVICE-1002", Level.INFO,
            "Configuration loaded from AWS SSM Parameter Store at startup"),
    SECRETS_LOADED("FILE_UPLOAD_SERVICE-1003", Level.INFO,
            "Secrets loaded from HashiCorp Vault at startup"),
    CONFIG_LOAD_FAILED("FILE_UPLOAD_SERVICE-1004", Level.ERROR,
            "Could not load configuration or secrets at startup; this instance refuses to start"),
    PRODUCTION_SETTINGS_UNSAFE("FILE_UPLOAD_SERVICE-1005", Level.ERROR,
            "Production mode with settings that are unsafe in production; this instance refuses to start"),
    CONFIG_CHECK_FAILED("FILE_UPLOAD_SERVICE-1006", Level.ERROR,
            "The stored configuration would stop this instance from starting again; fix it before the next restart"),
    CONFIG_CHECK_PASSED("FILE_UPLOAD_SERVICE-1007", Level.INFO,
            "The stored configuration is good again; a restart would succeed"),
    CONFIG_CHANGED("FILE_UPLOAD_SERVICE-1008", Level.INFO,
            "Parameters or secrets changed since startup; they take effect when the instance restarts"),
    SCAN_EVENTS_STARTED("FILE_UPLOAD_SERVICE-1010", Level.INFO,
            "Receiving scan events from the SQS queue"),
    SCAN_EVENTS_OFF("FILE_UPLOAD_SERVICE-1011", Level.WARN,
            "No scan queue configured: uploads are accepted but never scanned or promoted"),
    SCAN_SIMULATOR_ON("FILE_UPLOAD_SERVICE-1012", Level.WARN,
            "The GuardDuty simulator is on: scan results are invented locally, not real scans"),
    EXTRACTION_STARTED("FILE_UPLOAD_SERVICE-1013", Level.INFO,
            "Extraction workers started: opted-in files are read by the model on Bedrock once available"),
    EXTRACTION_OFF("FILE_UPLOAD_SERVICE-1014", Level.INFO,
            "No extraction model configured: uploads that opt in to extraction are refused"),
    BEDROCK_SIMULATOR_ON("FILE_UPLOAD_SERVICE-1015", Level.WARN,
            "The Bedrock simulator is on: extracted policies are invented locally, not read by the model"),

    UPLOAD_URL_ISSUED("FILE_UPLOAD_SERVICE-2001", Level.INFO,
            "Presigned upload URL issued; the file is scanned once the caller has uploaded it"),
    UPLOAD_STREAMED("FILE_UPLOAD_SERVICE-2002", Level.INFO,
            "File streamed to quarantine; GuardDuty scans it now"),
    UPLOAD_REJECTED("FILE_UPLOAD_SERVICE-2003", Level.INFO,
            "Upload refused before storing anything; the caller got a 4xx saying why"),
    FILE_SUBMITTED_FOR_SCAN("FILE_UPLOAD_SERVICE-2004", Level.INFO,
            "File landed in quarantine and GuardDuty picked it up for scanning"),
    FILE_AVAILABLE("FILE_UPLOAD_SERVICE-2005", Level.INFO,
            "File scanned clean and moved to the clean bucket; callers can use it"),
    FILE_INFECTED("FILE_UPLOAD_SERVICE-2006", Level.WARN,
            "GuardDuty found a threat in an upload; it stays in quarantine and is never served"),
    FILE_NOT_SCANNED("FILE_UPLOAD_SERVICE-2007", Level.WARN,
            "GuardDuty could not scan an upload; it stays in quarantine and is never served"),
    FILE_REJECTED_AFTER_SCAN("FILE_UPLOAD_SERVICE-2008", Level.WARN,
            "File scanned clean but refused: its content is not the declared type, or it changed after the scan"),
    SCAN_OVERDUE("FILE_UPLOAD_SERVICE-2009", Level.WARN,
            "No scan result for a file within the expected time; it stays SCANNING until one arrives"),
    PROMOTION_RESUMED("FILE_UPLOAD_SERVICE-2010", Level.WARN,
            "A promotion left unfinished by a crash or a lost event is being finished from the file's record"),
    SCAN_RESULT_RECOVERED("FILE_UPLOAD_SERVICE-2011", Level.WARN,
            "A scan result whose event never arrived was read from the object's GuardDuty tag and applied"),
    UPLOAD_STORED_UNRECORDED("FILE_UPLOAD_SERVICE-2012", Level.WARN,
            "A streamed file was stored but its status couldn't be updated; S3's event updates it, the caller got 202"),
    UPLOAD_REPLAYED("FILE_UPLOAD_SERVICE-2013", Level.INFO,
            "A retried upload with the same Idempotency-Key was answered with the file its first attempt created"),
    EXTRACTION_COMPLETED("FILE_UPLOAD_SERVICE-2014", Level.INFO,
            "The model read the policy in a file; the result is stored for internal readers"),
    EXTRACTION_FAILED("FILE_UPLOAD_SERVICE-2015", Level.WARN,
            "Extraction gave up on a file; the file itself is unaffected and stays available"),

    DEPENDENCY_UNAVAILABLE("FILE_UPLOAD_SERVICE-3001", Level.WARN,
            "An AWS call failed; the caller got HTTP 503 and may retry"),
    SCAN_EVENT_FAILED("FILE_UPLOAD_SERVICE-3002", Level.WARN,
            "Handling a scan event failed; SQS delivers it again after the visibility timeout"),
    SCAN_EVENT_UNRECOGNISED("FILE_UPLOAD_SERVICE-3003", Level.WARN,
            "A message on the scan queue is not an event this service handles; it is deleted"),
    SCAN_EVENT_UNKNOWN_FILE("FILE_UPLOAD_SERVICE-3004", Level.WARN,
            "A scan event names an object with no file record; it is deleted"),
    SCAN_QUEUE_UNREACHABLE("FILE_UPLOAD_SERVICE-3005", Level.WARN,
            "Receiving from the scan queue failed; retrying with backoff, results wait in the queue"),
    SCAN_MONITOR_FAILED("FILE_UPLOAD_SERVICE-3006", Level.WARN,
            "Counting scans in progress failed; the gauge keeps its last value and the next run retries"),
    RECOVERY_FAILED("FILE_UPLOAD_SERVICE-3007", Level.WARN,
            "Finishing a file left in flight failed; the next recovery run tries again"),
    EXTRACTION_RETRYING("FILE_UPLOAD_SERVICE-3008", Level.WARN,
            "A model call failed temporarily; the extraction is retried after a wait"),
    EXTRACTION_WORKER_FAILED("FILE_UPLOAD_SERVICE-3009", Level.WARN,
            "An extraction worker couldn't reach DynamoDB or S3; it retries, and claimed work is taken over when its lease ends"),

    UNEXPECTED_ERROR("FILE_UPLOAD_SERVICE-9001", Level.ERROR,
            "Unexpected failure while handling a request; the caller got HTTP 500");

    private final String code;
    private final Level level;
    private final String summary;
    // built once: every log line of this event uses them
    private final String message;
    private final String reference;

    LogEvent(String code, Level level, String summary) {
        this.code = code;
        this.level = level;
        this.summary = summary;
        this.message = "[" + code + "] " + summary;
        this.reference = "docs/runbook.md#" + code.toLowerCase(Locale.ROOT);
    }

    public String code() {
        return code;
    }

    public Level level() {
        return level;
    }

    public String summary() {
        return summary;
    }

    /** The log message: {@code [CODE] summary}. */
    public String message() {
        return message;
    }

    /** Link to this event's SOP: the anchor with its code in the runbook. */
    public String reference() {
        return reference;
    }

    /** Starts a log line for this event: {@code LogEvent.X.at(log).with("order_id", id).cause(e).log()}. */
    public EventLog at(Logger logger) {
        return new EventLog(logger, this);
    }
}
