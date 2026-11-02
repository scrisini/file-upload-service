# file-upload-service runbook

Standard operating procedures for every event file-upload-service logs with a code. Each log line for
a catalogued event carries:

| Field | Example | Meaning |
|---|---|---|
| `event.code` | `FILE_UPLOAD_SERVICE-9001` | Stable id of the event: the section of this runbook to follow |
| `event.reference` | `docs/runbook.md#file_upload_service-9001` | Link to that section |
| `http.request.id` | `3f9c…` | The request that caused it; also returned in the `X-Request-Id` header and as `requestId` in error responses |
| `file-upload-service.*` | `file-upload-service.path` | Details of the event |
| `error.type`, `error.message`, `error.stack_trace` | | The exception, for errors |

Production logs are JSON in the Elastic Common Schema (`FILE_UPLOAD_SERVICE_LOG_FORMAT=ecs`). Log lines
without an `event.code` come from libraries (Spring, drivers); treat ERROR ones as
[FILE_UPLOAD_SERVICE-9001](#file_upload_service-9001) and escalate.

## Finding the log lines

Start from what you were given: a request id from a caller's error response, or a code from an
alert. Spring's ECS output may nest dotted fields (`event.code` as `"event":{"code":…}`);
Elasticsearch, Splunk and New Relic all accept the dotted name in queries.

| Tool | All lines for one request | All lines for one code |
|---|---|---|
| Kibana (KQL) | `http.request.id : "3f9c…"` | `event.code : "FILE_UPLOAD_SERVICE-9001"` |
| Splunk (SPL) | `index=<index> "http.request.id"="3f9c…"` | `index=<index> "event.code"="FILE_UPLOAD_SERVICE-9001"` |
| New Relic (NRQL) | ``SELECT * FROM Log WHERE `http.request.id` = '3f9c…'`` | ``SELECT * FROM Log WHERE `event.code` = 'FILE_UPLOAD_SERVICE-9001'`` |
| Plain text logs | `grep 3f9c…` | `grep FILE_UPLOAD_SERVICE-9001` |

Suggested alerts:

- any **ERROR**;
- the `file-upload-service.config.restartable` gauge at 0 (the stored configuration would stop the next restart:
  fix it before deploying);
- p99 of `http.server.requests`, and of any queue-wait timer, above its normal level for 5 minutes
  (the service is falling behind: see README "Capacity" and add instances or cores);
- any [FILE_UPLOAD_SERVICE-2006](#file_upload_service-2006) (a threat was uploaded): to the security team;
- `file-upload-service.scan.in-progress` growing for 15 minutes, any increase of `file-upload-service.scan.overdue`, or p95 of
  `file-upload-service.scan.duration` above 2 minutes (GuardDuty is slow or results aren't arriving:
  [2009](#file_upload_service-2009));
- p95 of `file-upload-service.events.wait` above 30 s (the queue is backing up: [3005](#file_upload_service-3005));
- any message in the scan queue's dead-letter queue ([3002](#file_upload_service-3002));
- [FILE_UPLOAD_SERVICE-2007](#file_upload_service-2007) with `ACCESS_DENIED` (GuardDuty can't read the bucket: every upload is affected).

## When to escalate to developers

Send the developers, in one message: the `event.code`, the `http.request.id`, the time range, and
the full log lines for that request id (including `error.stack_trace`).

---

## Lifecycle (1xxx)

<a id="file_upload_service-1001"></a>
### FILE_UPLOAD_SERVICE-1001: Service started

- **Level:** INFO. Normal at startup.
- **Means:** the instance is ready and accepting requests on `file-upload-service.port`, in `file-upload-service.mode`
  (`development` or `production`).
- **Check:** after a deployment, one line per instance. A missing line means that instance didn't
  start: look for its last log lines. In production, `file-upload-service.mode` must be `production`.

<a id="file_upload_service-1002"></a>
### FILE_UPLOAD_SERVICE-1002: Configuration loaded from AWS SSM Parameter Store

- **Level:** INFO. Normal at startup when `FILE_UPLOAD_SERVICE_SSM_PATH` is set.
- **Means:** `file-upload-service.keys` parameters were read from `file-upload-service.path`. Values are never logged.
- **Check:** `file-upload-service.keys` should match the parameters you stored. A missing one falls back to the
  environment or `application.yml`.

<a id="file_upload_service-1003"></a>
### FILE_UPLOAD_SERVICE-1003: Secrets loaded from HashiCorp Vault

- **Level:** INFO. Normal at startup when `FILE_UPLOAD_SERVICE_VAULT_ADDRESS` is set.
- **Means:** `file-upload-service.keys` keys were read from the secret `file-upload-service.path`. Values are never logged.
- **Check:** `file-upload-service.keys` should match the keys you stored.

<a id="file_upload_service-1004"></a>
### FILE_UPLOAD_SERVICE-1004: Could not load configuration or secrets

- **Level:** ERROR. The instance stops during startup, before logging is set up, so this appears in
  the startup error (`Application run failed`) in plain text rather than as a JSON line.
- **Means:** `FILE_UPLOAD_SERVICE_SSM_PATH` or `FILE_UPLOAD_SERVICE_VAULT_ADDRESS` is set, but reading failed. The message names
  the store, the path and the reason (`HTTP 403: permission denied`, an unknown host, a timeout);
  never a token or a value. The instance doesn't fall back to defaults.
- **Check:**
  - **Parameter Store:** the instance's IAM role allows `ssm:GetParametersByPath` on the path (and
    `kms:Decrypt` for SecureString), and the SSM VPC endpoint is reachable.
  - **Vault:** the address is reachable and its certificate trusted (`FILE_UPLOAD_SERVICE_VAULT_CA_CERT`); the
    login works (`FILE_UPLOAD_SERVICE_VAULT_AUTH` and its role, token or secret id); the policy allows `read` on
    `<kv-mount>/data/<path>`.

<a id="file_upload_service-1005"></a>
### FILE_UPLOAD_SERVICE-1005: Production mode refused unsafe settings

- **Level:** ERROR. The instance stops during startup.
- **Means:** `file-upload-service.mode` is `production` (`FILE_UPLOAD_SERVICE_MODE`), and `file-upload-service.violations` lists settings that are
  unsafe there, each naming what to change.
- **Check:** fix each listed setting in Parameter Store, Vault or the deployment's environment,
  then start again. To run without these rules (a test environment), set `FILE_UPLOAD_SERVICE_MODE=development`.

<a id="file_upload_service-1006"></a>
### FILE_UPLOAD_SERVICE-1006: The stored configuration would stop a restart

- **Level:** ERROR. The instance keeps running on the configuration it started with; the next
  restart (a deploy, a crash, a node replacement) would fail.
- **Means:** the configuration check re-read Parameter Store and Vault and found `file-upload-service.problems`:
  a store that can no longer be read (`HTTP 403`, an expired credential), a value that doesn't
  validate, or a production-mode violation ([FILE_UPLOAD_SERVICE-1005](#file_upload_service-1005)). Repeats at every check until fixed.
- **Check:** fix what each problem names, then confirm with
  `curl -X POST <instance>:8080/actuator/configcheck` (inside the network): `"restartable": true`.
  Don't restart or deploy until then. If every instance reports it, the change was made in the
  store; if one does, look at that instance's credentials (IAM role, Vault login).

<a id="file_upload_service-1007"></a>
### FILE_UPLOAD_SERVICE-1007: The stored configuration is good again

- **Level:** INFO. Follows a [FILE_UPLOAD_SERVICE-1006](#file_upload_service-1006) once its problems are fixed.
- **Means:** a restart would succeed again.
- **Check:** nothing.

<a id="file_upload_service-1008"></a>
### FILE_UPLOAD_SERVICE-1008: Parameters or secrets changed since startup

- **Level:** INFO. Expected after someone changes a parameter or rotates a secret.
- **Means:** `file-upload-service.keys` names the keys added, removed or changed in Parameter Store or Vault since
  this instance started, never their values. Running instances keep the values they started with;
  each picks up the change when it restarts. Logged once per set of changes.
- **Check:** if the change was meant to apply now, restart the instances one at a time (a rolling
  deploy). If nobody expected it, find out who changed the store.



<a id="file_upload_service-1010"></a>
### FILE_UPLOAD_SERVICE-1010: Receiving scan events

- **Level:** INFO. Normal at startup, once per instance.
- **Means:** the instance receives S3's "Object Created" and GuardDuty's scan results from the SQS
  queue `file-upload-service.queue_url`, on `file-upload-service.consumers` threads. All instances share the queue.

<a id="file_upload_service-1011"></a>
### FILE_UPLOAD_SERVICE-1011: No scan queue configured

- **Level:** WARN. Expected only on a laptop without dependencies, and in tests.
- **Means:** `FILE_UPLOAD_SERVICE_SCAN_QUEUE_URL` is blank. Uploads are accepted and stored in
  quarantine, but the service never learns that they landed or how their scan went: every file
  stays `AWAITING_UPLOAD` and nothing is promoted. Production mode refuses to start like this
  ([1005](#file_upload_service-1005)).
- **Check:** set the queue URL (docs/deployment.md "AWS resources").

<a id="file_upload_service-1012"></a>
### FILE_UPLOAD_SERVICE-1012: The GuardDuty simulator is on

- **Level:** WARN. Expected with `scripts/dev.sh run`; never anywhere else.
- **Means:** scan results are invented by the service itself (README "Running locally"): a file
  containing the EICAR test string is "infected", anything else "clean". No file is really scanned.
  Production mode refuses to start with it on ([1005](#file_upload_service-1005)).
- **Check:** outside a laptop, set `FILE_UPLOAD_SERVICE_SCAN_SIMULATOR=false` at once.

## Uploads and scans (2xxx)

A file moves `AWAITING_UPLOAD` → `SCANNING` → `PROMOTING` → `AVAILABLE`, or ends `INFECTED`,
`NOT_SCANNED` or `REJECTED` (README "File lifecycle"). Every line carries `file-upload-service.file_id`: search for
it to see one file's whole history. `GET /api/v1/files/<id>` shows its status and times.

<a id="file_upload_service-2001"></a>
### FILE_UPLOAD_SERVICE-2001: Presigned upload URL issued

- **Level:** INFO. One per presigned upload requested.
- **Means:** a caller asked to upload a `file-upload-service.file_type` of `file-upload-service.size` bytes and got a URL that works
  until `file-upload-service.expires_at`. The file is `AWAITING_UPLOAD` until the bytes land
  ([2004](#file_upload_service-2004)). A URL never used leaves no object; DynamoDB's TTL removes the record
  an hour after the URL expires.
- **Check:** a caller reporting a 403 from S3 on the PUT sent different headers from those returned
  (each is signed), or the URL expired. A 412 means it PUT twice: the first upload counts.

<a id="file_upload_service-2002"></a>
### FILE_UPLOAD_SERVICE-2002: File streamed to quarantine

- **Level:** INFO. One per streamed upload.
- **Means:** the bytes passed through the service to the quarantine bucket; the file is `SCANNING`.

<a id="file_upload_service-2003"></a>
### FILE_UPLOAD_SERVICE-2003: Upload refused

- **Level:** INFO. A caller error, not a fault.
- **Means:** `file-upload-service.reason` says why: `UNSUPPORTED_TYPE` (415), `CONTENT_MISMATCH` (415: the
  bytes aren't the declared type), `TOO_LARGE` (413), `LENGTH_REQUIRED` (411), `INCOMPLETE` (400: the
  body ended early or the connection dropped), `INVALID` (400). `file-upload-service.detail` is what the caller was
  told. Nothing was stored.
- **Check:** many from one front-end after a release point at that front-end. Many `CONTENT_MISMATCH`
  from one caller may be someone probing; tell the security team.

<a id="file_upload_service-2004"></a>
### FILE_UPLOAD_SERVICE-2004: File landed in quarantine; GuardDuty picked it up

- **Level:** INFO. One per upload.
- **Means:** S3 stored the object at `file-upload-service.submitted_at`. GuardDuty starts scanning every new
  object in the protected bucket when it lands and publishes no event of its own until it finishes,
  so this time is when the scan started. The file is `SCANNING` and counted in
  `file-upload-service.scan.in-progress`.

<a id="file_upload_service-2005"></a>
### FILE_UPLOAD_SERVICE-2005: File available

- **Level:** INFO. One per clean file.
- **Means:** GuardDuty found no threat, the content matched its declared type, and the file was
  copied to the clean bucket and removed from quarantine. `file-upload-service.scan_ms` is GuardDuty's time,
  `file-upload-service.total_ms` the time from landing to available.

<a id="file_upload_service-2006"></a>
### FILE_UPLOAD_SERVICE-2006: GuardDuty found a threat

- **Level:** WARN. Security-relevant: alert the security team on every one.
- **Means:** `file-upload-service.threat` names what GuardDuty found (one threat per file). The file is
  `INFECTED`, stays in the quarantine bucket, is never served, and is deleted by the bucket's
  lifecycle rule after 30 days. The caller sees status `INFECTED` and nothing more.
- **Check:** with the security team: who uploaded it (the request id of the upload,
  [2001](#file_upload_service-2001) or [2002](#file_upload_service-2002), has the caller), whether others came from the same source,
  and the GuardDuty finding if GuardDuty is enabled in the account. Keep the object while they need
  it (copy it out of reach of the lifecycle rule).

<a id="file_upload_service-2007"></a>
### FILE_UPLOAD_SERVICE-2007: GuardDuty could not scan a file

- **Level:** WARN.
- **Means:** `file-upload-service.scan_result` is `UNSUPPORTED` (password-protected, an encrypted or
  very compressed PDF, over GuardDuty's limits), `ACCESS_DENIED` or `FAILED`; `file-upload-service.status_reasons`
  gives GuardDuty's reasons. The file is `NOT_SCANNED`, stays in quarantine and is never served:
  unscanned is treated as unsafe.
- **Check:** `ACCESS_DENIED` is a configuration fault, not the file's: GuardDuty's IAM role can't
  read the bucket or its KMS key (`UNAUTHORIZED_TO_GET_OBJECT`, `UNAUTHORIZED_TO_ASSUME_ROLE`). It
  affects every upload: fix the role (docs/deployment.md "AWS resources"). `FAILED` repeatedly:
  GuardDuty's service health. `UNSUPPORTED`: tell the caller to upload the file without a password.

<a id="file_upload_service-2008"></a>
### FILE_UPLOAD_SERVICE-2008: Scanned clean but refused

- **Level:** WARN.
- **Means:** `file-upload-service.reason` is `CONTENT_MISMATCH` (a presigned upload whose bytes aren't the type
  the caller declared: the service checks content only after the scan for this style),
  `CHANGED_AFTER_SCAN` (the object in quarantine isn't the version GuardDuty scanned) or
  `MISSING_FROM_QUARANTINE`. The file is `REJECTED` and stays in quarantine.
- **Check:** `CONTENT_MISMATCH` from one caller repeatedly may be probing: tell the security team.
  `CHANGED_AFTER_SCAN` should be impossible (uploads can't replace an object): find who wrote to the
  quarantine bucket.

<a id="file_upload_service-2009"></a>
### FILE_UPLOAD_SERVICE-2009: Scan overdue

- **Level:** WARN. Reported once per file, by one instance.
- **Means:** the file landed more than `file-upload-service.overdue_after` ago and GuardDuty hasn't reported.
  It stays `SCANNING`; a late result is still applied.
- **Check:** many at once: GuardDuty's protection plan status for the bucket (console, or the
  "GuardDuty Malware Protection Resource Status" events), the EventBridge rule for scan results, and
  the scan queue's dead-letter queue. One alone: GuardDuty may still be scanning a large file.

## Dependencies (3xxx)

<a id="file_upload_service-3001"></a>
### FILE_UPLOAD_SERVICE-3001: An AWS call failed during a request

- **Level:** WARN. Callers get 503 with `Retry-After` and retry.
- **Means:** `file-upload-service.dependency` (`s3`, `dynamodb`) failed `file-upload-service.operation` after the SDK's own
  retries, or timed out. `error.*` has AWS's message.
- **Check:** `GET /actuator/health` inside the VPC (`s3`, `dynamoDb`); the AWS Health Dashboard;
  the VPC endpoints; the pod's IAM role (an `AccessDenied` in `error.message`). Escalate if it lasts
  more than a few minutes.

<a id="file_upload_service-3002"></a>
### FILE_UPLOAD_SERVICE-3002: Handling a scan event failed

- **Level:** WARN. Recovers by itself.
- **Means:** a dependency failed while acting on `file-upload-service.event`. The message stays on the queue and
  SQS delivers it again after the visibility timeout; `file-upload-service.receive_count` counts attempts.
  After the queue's `maxReceiveCount` it moves to the dead-letter queue.
- **Check:** if it repeats, follow [3001](#file_upload_service-3001). Messages in the dead-letter queue are files
  stuck `SCANNING` or `PROMOTING`: once the cause is fixed, redrive them to the queue (SQS console,
  "Start DLQ redrive"); handling them again is safe.

<a id="file_upload_service-3003"></a>
### FILE_UPLOAD_SERVICE-3003: Unrecognised message on the scan queue

- **Level:** WARN.
- **Means:** a message that isn't S3's "Object Created" or GuardDuty's scan result (`file-upload-service.why`). It
  is deleted.
- **Check:** the EventBridge rules' event patterns: only those two detail types, for the quarantine
  bucket, should reach the queue.

<a id="file_upload_service-3004"></a>
### FILE_UPLOAD_SERVICE-3004: Scan event for an unknown file

- **Level:** WARN.
- **Means:** the event names an object outside the quarantine prefix, not named by a file id, or
  whose record no longer exists. It is deleted.
- **Check:** someone writing to the quarantine bucket outside this service, or the EventBridge rule
  matching another bucket or prefix.

<a id="file_upload_service-3005"></a>
### FILE_UPLOAD_SERVICE-3005: Can't receive from the scan queue

- **Level:** WARN. Recovers by itself.
- **Means:** receiving from SQS failed; the consumer retries after `file-upload-service.retry_in`, backing off to
  30 s. Events wait in the queue meanwhile, so files stay `SCANNING` longer but nothing is lost
  (unless it lasts longer than the queue's retention, 4 days by default).
- **Check:** `GET /actuator/health` (`scanQueue`); the SQS VPC endpoint; the pod's IAM role
  (`sqs:ReceiveMessage`, `sqs:DeleteMessage`, and `kms:Decrypt` if the queue is encrypted).

<a id="file_upload_service-3006"></a>
### FILE_UPLOAD_SERVICE-3006: Counting scans in progress failed

- **Level:** WARN. Recovers by itself.
- **Means:** DynamoDB failed `file-upload-service.operation` in the scan monitor. `file-upload-service.scan.in-progress` keeps
  its last value, and overdue scans wait for the next run.
- **Check:** as [3001](#file_upload_service-3001), for DynamoDB.

## Unexpected failures (9xxx)

<a id="file_upload_service-9001"></a>
### FILE_UPLOAD_SERVICE-9001: Unexpected failure while handling a request

- **Level:** ERROR.
- **Means:** a request (`file-upload-service.method`, `file-upload-service.path`) failed in a
  way the service has no rule for. The caller got HTTP 500 with a `requestId`. `error.*` says what
  failed.
- **Check:** `error.type` and `error.message`. A connection or timeout error points at a
  dependency: check `GET /actuator/health`.
- **Escalate:** always, with the full log lines for the request id.
