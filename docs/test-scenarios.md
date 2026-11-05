# Test scenarios

Every behaviour of file-upload-service a tester should cover, written so each row can become a
test case. Each scenario has an id, the steps (using the [recipes](#recipes) below), and the
expected result: HTTP status and body, the file's status, what's in S3, and the log codes
([runbook](runbook.md)) and metrics it produces.

**Where it runs:** **L** runs on a laptop against the local emulators (`scripts/dev.sh run` or
`docker`); **A** needs real AWS (GuardDuty, S3's signature checks, IAM). Most scenarios are L. Run
the A ones in a test account before going live.

**Priority:** **P1** must pass to release (data safety, security, the core flows); **P2** should
pass; **P3** is edge or diagnostic.

The automated suite (`./gradlew test`) covers much of this at unit level: the right column of each
area names the test classes, so testers can focus on the end-to-end paths and the A scenarios.

## Contents

1. [Setup](#setup) and [recipes](#recipes)
2. [Presigned uploads (PS)](#presigned-uploads-ps)
3. [Streamed uploads (ST)](#streamed-uploads-st)
4. [File types and content checks (FT)](#file-types-and-content-checks-ft)
5. [Scanning outcomes (SC)](#scanning-outcomes-sc)
6. [Status, download and scan count (API)](#status-download-and-scan-count-api)
7. [Idempotent retries (ID)](#idempotent-retries-id)
8. [Event handling: duplicates, order, garbage (EV)](#event-handling-duplicates-order-garbage-ev)
9. [Crash safety (CR)](#crash-safety-cr)
10. [Configuration and production mode (CF)](#configuration-and-production-mode-cf)
11. [Security (SE)](#security-se)
12. [Observability (OB)](#observability-ob)
13. [Non-functional: no filesystem, memory, load, shutdown (NF)](#non-functional-no-filesystem-memory-load-shutdown-nf)
14. [AWS-only scenarios (AW)](#aws-only-scenarios-aw)
15. [Policy extraction (EX)](#policy-extraction-ex)
16. [Graceful shutdown (GS)](#graceful-shutdown-gs)
17. [Integration tests against real AWS (IT)](#integration-tests-against-real-aws-it)

## Setup

```bash
scripts/dev.sh run          # app on :8080 (asks for another port if taken), emulators, GuardDuty simulator
scripts/dev.sh docker       # the same with the app in Docker, read-only filesystem (NF scenarios)
scripts/dev.sh status       # health and scans in progress
```

| Setting for a scenario | How |
|---|---|
| No simulator (inject events yourself: SC, EV, CR) | `FILE_UPLOAD_SERVICE_SCAN_SIMULATOR=false scripts/dev.sh run` |
| Slower simulated scans (to act while `SCANNING`) | `FILE_UPLOAD_SERVICE_SCAN_SIMULATOR_DELAY=30s` |
| Overdue and recovery in seconds, not minutes | `FILE_UPLOAD_SERVICE_SCAN_OVERDUE_AFTER=10s FILE_UPLOAD_SERVICE_SCAN_RECOVERY_INTERVAL=5s FILE_UPLOAD_SERVICE_SCAN_MONITOR_INTERVAL=5s` |
| Promotions resumed sooner | `FILE_UPLOAD_SERVICE_SCAN_RESUME_PROMOTION_AFTER=10s` |
| A smaller size limit or fewer types | `FILE_UPLOAD_SERVICE_MAX_SIZE=1MB`, `FILE_UPLOAD_SERVICE_ALLOWED_TYPES=PNG,PDF` |
| One stream at a time (capacity) | `FILE_UPLOAD_SERVICE_MAX_CONCURRENT_STREAMS=1` |
| Extraction retries in seconds | `FILE_UPLOAD_SERVICE_EXTRACTION_RETRY_BACKOFF=2s` |
| Slow model calls (to act mid-call) | `FILE_UPLOAD_SERVICE_EXTRACTION_SIMULATOR_DELAY=20s FILE_UPLOAD_SERVICE_EXTRACTION_TIMEOUT=20s FILE_UPLOAD_SERVICE_EXTRACTION_LEASE=35s` |
| Extraction off | `FILE_UPLOAD_SERVICE_EXTRACTION_MODEL_ID= scripts/dev.sh run` |

Logs print to the terminal; every line of interest carries `[FILE_UPLOAD_SERVICE-nnnn]` and
`file-upload-service.file_id`. Metrics: `curl localhost:8080/actuator/metrics/<name>`.

**To take a dependency away, pause it, don't stop it:** `docker compose pause dynamodb` (or `sqs`,
`s3`), then `unpause`. DynamoDB Local and ElasticMQ keep everything in memory, so stopping them
loses the table and the queued events, and the test then measures the emulator, not the service.
`scripts/dev.sh reset` (or `docker compose restart dynamodb` and `scripts/dev.sh infra`) gives a
clean slate between runs.

## Recipes

Short names used in the steps. `$B` is `http://localhost:8080`.

| Recipe | Command |
|---|---|
| **upload-presigned** *file* | `scripts/try-upload.sh presigned <file>`: asks for a URL, PUTs to S3, follows the status to the end |
| **upload-stream** *file* | `scripts/try-upload.sh stream <file>` |
| **replay** | prefix either with `IDEMPOTENCY_KEY=<key printed by the first run>`; `IDEMPOTENCY_KEY=` sends no key |
| **ask-url** *json* | `curl -i -X POST $B/api/v1/uploads -H 'Content-Type: application/json' -d '<json>'` |
| **put** *url file type* | `curl -i -X PUT --upload-file <file> -H 'Content-Type: <type>' -H 'If-None-Match: *' '<url>'` |
| **stream** *file type* | `curl -i -X POST "$B/api/v1/files?fileName=<name>" -H 'Content-Type: <type>' --data-binary @<file>` |
| **status** *id* | `curl -s $B/api/v1/files/<id>` |
| **scans** | `curl -s $B/api/v1/scans` |
| **extract** | prefix an upload with `EXTRACT=INSURANCE_POLICY`: opts in, then follows the extraction to its end and prints it |
| **extraction** *id* | `curl -s $B/internal/v1/files/<id>/extraction` (internal endpoint) |
| **event** | `scripts/send-scan-event.sh created <id>`, `result <id> <STATUS> [reason]`, `tag <id> <STATUS>`, `raw '<body>'` (simulator off) |
| **kill** | `kill -9 $(pgrep -f FileUploadServiceApplication)`: a crash, no shutdown hooks (`docker kill file-upload-service-app` in Docker) |
| **sigterm** | `kill -TERM $(pgrep -f FileUploadServiceApplication)`: a graceful stop, as a deploy does (`scripts/dev.sh stop` for the Docker app) |
| **list** *bucket* | `curl -s 'localhost:9099/<bucket>?list-type=2' \| grep -o '<Key>[^<]*'`: `file-upload-quarantine`, `file-upload-clean` |
| **record** *id* | DynamoDB item: see `crash` helpers in [Crash safety](#crash-safety-cr) |

**Test files.** Make one of each type: JPEG, PNG, GIF, WebP, HEIC, TIFF, PDF (real files from a
phone, a browser and a PDF tool are best). Also:

| File | How to make it |
|---|---|
| *eicar.png* (simulated malware) | a PNG with the [EICAR test string](https://www.eicar.org/download-anti-malware-testfile/) appended: `cp a.png eicar.png; printf '%s' 'X5O!P%@AP[4\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*' >> eicar.png` |
| *fake.png* (a PDF named .png) | `cp a.pdf fake.png` |
| *exe.png* (an executable named .png) | `printf 'MZ\x90\x00 not an image' > exe.png` |
| *big.png* (20 MB) | `{ cat a.png; head -c 20971520 /dev/urandom; } > big.png` |
| *limit.png* (exactly 25 MB) and *over.png* (25 MB + 1 byte) | as *big.png*, sized to `26214400` and `26214401` bytes |
| *throttle.png*, *garbage.png*, *refuse.png* (Bedrock simulator markers) | `cp a.png throttle.png; printf BEDROCK-THROTTLE >> throttle.png` (likewise `BEDROCK-GARBAGE`, `BEDROCK-REFUSE`) |
| *photo4mb.png* (over Bedrock's image limit) | as *big.png*, 4,000,000 bytes |
| *locked.pdf* (password-protected) | any PDF tool's "encrypt with password" (GuardDuty reports UNSUPPORTED; AW only) |

## Presigned uploads (PS)

Unit tests: `UploadServiceTests`, `FileStorageTests`, `FileControllerTests`.

| Id | P | Where | Scenario | Steps | Expected |
|---|---|---|---|---|---|
| PS-01 | P1 | L | Happy path, PNG | upload-presigned *a.png* | 201 with `file.status` `AWAITING_UPLOAD`, `upload.method` `PUT`, `upload.url`, `upload.headers` = `Content-Type` and `If-None-Match: *`, `expiresAt` 15 min ahead, `Location: /api/v1/files/<id>`. PUT 200. Status goes `AWAITING_UPLOAD` → `SCANNING` → `AVAILABLE`. Logs 2001, 2004, 2005 |
| PS-02 | P1 | L | Each accepted type | PS-01 for JPEG, GIF, WebP, HEIC, TIFF, PDF | Each `AVAILABLE`; `contentType` canonical (`image/jpeg`, `image/heic`, `application/pdf`…) |
| PS-03 | P2 | L | Media type aliases | ask-url with `image/jpg`, `image/heif`, `IMAGE/PNG; charset=binary` | 201; `contentType` `image/jpeg`, `image/heic`, `image/png` |
| PS-04 | P1 | L | Unsupported declared type | ask-url `{"fileName":"a.svg","contentType":"image/svg+xml","size":10}` | 415, `reason` `UNSUPPORTED_TYPE`, `detail` lists the accepted types. No record. Log 2003 |
| PS-05 | P2 | L | Missing size | ask-url without `size` | 400 `size is required…` |
| PS-06 | P2 | L | Size 0 and negative | ask-url with `size` 0 and -1 | 400, `reason` `INVALID` |
| PS-07 | P1 | L | Size at the limit | ask-url with `size` 26214400 | 201 |
| PS-08 | P1 | L | Size over the limit | ask-url with `size` 26214401 | 413, `reason` `TOO_LARGE` |
| PS-09 | P2 | L | Malformed JSON body | ask-url with `{bad` | 400 problem with `requestId` |
| PS-10 | P1 | L | Second PUT to the same URL | ask-url; put twice | First 200, second **412**; one object in quarantine |
| PS-11 | P1 | L | Content not the declared type | ask-url as PNG with *fake.png*'s size; put *fake.png* as `image/png` | PUT 200 (S3 can't see content). After the scan: `REJECTED`, `rejectedBecause` `CONTENT_MISMATCH: declared PNG, content is PDF`; object stays in quarantine, nothing in clean. Log 2008 |
| PS-12 | P2 | L | Abandoned URL | ask-url, never PUT | Status stays `AWAITING_UPLOAD`; not counted in `scans`. (TTL removes the record 15 days after the URL expires: verify `expiresAt` in the record) |
| PS-13 | P1 | A | PUT with another content type or length | ask-url as PNG 176 bytes; PUT with `Content-Type: image/jpeg`, or a different file | **403** `SignatureDoesNotMatch` from S3; nothing stored. *Locally the emulator doesn't check signatures* |
| PS-14 | P1 | A | PUT without `If-None-Match` | put without that header | 403 from S3 |
| PS-15 | P2 | A | Expired URL | `FILE_UPLOAD_SERVICE_URL_TTL=1m`; ask-url; wait 2 min; put | 403 `Request has expired` |
| PS-16 | P2 | A | Browser upload (CORS) | from a front-end origin listed in the bucket's CORS, `fetch(url, {method:'PUT', headers, body: file})` | 200; from an unlisted origin the browser blocks it |
| PS-17 | P3 | L | File names | ask-url with `fileName` `../../etc/passwd`, `C:\x\y.png`, `"a\u0000b.png"`, 300 characters, Unicode `日本.png`, missing | Stored names `passwd`, `y.png`, `ab.png`, first 255 characters, `日本.png`, `upload` |

## Streamed uploads (ST)

Unit tests: `UploadServiceTests`, `FileControllerTests`.

| Id | P | Where | Scenario | Steps | Expected |
|---|---|---|---|---|---|
| ST-01 | P1 | L | Happy path, PDF | upload-stream *a.pdf* | **202**, `status` `SCANNING`, `submittedForScanAt` set, `Location`, `Idempotent-Replayed: false`. Then `AVAILABLE`. Logs 2002, 2005 |
| ST-02 | P1 | L | Each accepted type | ST-01 for every type | Each `AVAILABLE` |
| ST-03 | P1 | L | Executable named .png | stream *exe.png* `image/png` | **415** at once, `reason` `CONTENT_MISMATCH`, `the content is not image/png`. No record, nothing in S3. Log 2003 |
| ST-04 | P1 | L | PDF declared as PNG | stream *fake.png* `image/png` | 415 `the content is not image/png (it looks like application/pdf)` |
| ST-05 | P1 | L | Unsupported type | stream an SVG as `image/svg+xml` | 415 `UNSUPPORTED_TYPE` |
| ST-06 | P1 | L | Multipart form | `curl -F file=@a.png $B/api/v1/files` | 415; the body isn't parsed (no temp files) |
| ST-07 | P1 | L | No Content-Length (chunked) | `curl -H 'Transfer-Encoding: chunked' -H 'Content-Type: image/png' --data-binary @a.png $B/api/v1/files` | **411** `LENGTH_REQUIRED` |
| ST-08 | P1 | L | Over the limit | stream *over.png* | **413** `TOO_LARGE`, before the body is read |
| ST-09 | P1 | L | At the limit | stream *limit.png* | 202 |
| ST-10 | P2 | L | Body shorter than Content-Length | send a `Content-Length` larger than the body (e.g. a small script with a raw socket, or abort curl partway with `--max-time`) | 400 `INCOMPLETE` (or the connection closes); no object in quarantine |
| ST-11 | P1 | L | Capacity | `FILE_UPLOAD_SERVICE_MAX_CONCURRENT_STREAMS=1`; start a slow stream (`curl --limit-rate 100K` of *big.png*); stream another | Second: **503**, `Retry-After: 1`. Gauge `uploads.streaming` 1 while the first runs, 0 after |
| ST-12 | P2 | L | Large file | stream *big.png* | 202 in well under a second locally; heap stays flat (NF-02) |
| ST-13 | P3 | L | No `fileName` | stream without `?fileName=` | Stored as `upload` |

## File types and content checks (FT)

Unit tests: `FileTypeTests`.

| Id | P | Where | Scenario | Steps | Expected |
|---|---|---|---|---|---|
| FT-01 | P1 | L | Every type's signature | stream one real file of each type | All 202 |
| FT-02 | P2 | L | HEIC variants | stream HEIC files from an iPhone (brands `heic`, `mif1`) | 202 |
| FT-03 | P2 | L | Look-alikes refused | stream as `image/webp` a RIFF WAV file; as `image/heic` an AVIF file | 415 `CONTENT_MISMATCH` |
| FT-04 | P2 | L | Declared type outside `allowed-types` | `FILE_UPLOAD_SERVICE_ALLOWED_TYPES=PDF`; stream a PNG | 415 `UNSUPPORTED_TYPE` |
| FT-05 | P3 | L | Tiny files | stream a 1-byte and a 5-byte file as PNG | 415 `CONTENT_MISMATCH` (too short to be a PNG) |

## Scanning outcomes (SC)

Unit tests: `ScanOutcomesTests`, `ScanEventParserTests`. With the simulator on, only clean and
EICAR happen; run the others with the simulator off and **event**.

| Id | P | Where | Scenario | Steps | Expected |
|---|---|---|---|---|---|
| SC-01 | P1 | L | Clean | upload *a.png* (either style) | `AVAILABLE`, `scanResult` `NO_THREATS_FOUND`; object moved from quarantine to clean; `contentUrl` set. Times in order: `createdAt` ≤ `submittedForScanAt` ≤ `scanCompletedAt` ≤ `availableAt` |
| SC-02 | P1 | L | Malware | upload *eicar.png* | `INFECTED`, `scanResult` `THREATS_FOUND`; object stays in quarantine; nothing in clean; no `contentUrl`; **no threat name in the response**. Log 2006 (WARN) with `threat` |
| SC-03 | P1 | L | Unsupported (password-protected) | simulator off; stream a PDF; event `result <id> UNSUPPORTED PASSWORD_PROTECTED` | `NOT_SCANNED`; stays in quarantine. Log 2007 with `status_reasons` |
| SC-04 | P1 | L | Access denied | as SC-03 with `ACCESS_DENIED UNAUTHORIZED_TO_GET_OBJECT` | `NOT_SCANNED`; log 2007 (`ACCESS_DENIED`, an alert: GuardDuty can't read the bucket) |
| SC-05 | P2 | L | Scan failed | as SC-03 with `FAILED` | `NOT_SCANNED` |
| SC-06 | P1 | L | Overdue scan | simulator off, overdue 10 s; stream a file; send nothing | After 10 s: still `SCANNING`; log 2009 **once** (even with several instances); counter `scan.overdue` +1 |
| SC-07 | P2 | L | Scan time | SC-01 | Timer `scan.duration` (tag `result`) and `files.time-to-available` (tag `method`) count it |
| SC-08 | P1 | A | Real GuardDuty, EICAR | upload the real EICAR file (as a `.pdf`? GuardDuty scans any content; use the presigned style with a matching declared type, or stream a PDF with the string appended) | `INFECTED` within GuardDuty's scan time; object tagged `GuardDutyMalwareScanStatus=THREATS_FOUND` |
| SC-09 | P2 | A | Real GuardDuty, password-protected PDF | upload *locked.pdf* | `NOT_SCANNED`, reason `PASSWORD_PROTECTED` |

## Status, download and scan count (API)

Unit tests: `FileControllerTests`, `ScanMonitorTests`, `ApiDocumentationTests`.

| Id | P | Where | Scenario | Steps | Expected |
|---|---|---|---|---|---|
| API-01 | P1 | L | Status fields per state | status at each step of PS-01 | Only reached steps present; `uploadMethod` `presigned-url`/`stream`; `rejectedBecause` only when `REJECTED`; `contentUrl` only when `AVAILABLE` |
| API-02 | P1 | L | Unknown id | status of a random UUID | 404 problem with `requestId` |
| API-03 | P2 | L | Malformed and upper-case ids | status of `not-an-id` and of a real id in upper case | 404 (never reaches DynamoDB) |
| API-04 | P1 | L | Download an available file | `curl -i $B/api/v1/files/<id>/content`; follow `Location` | **303** to a link valid 5 min; downloaded bytes identical to the upload; `Content-Disposition: attachment; filename*=UTF-8''<name>` |
| API-05 | P1 | L | Download a file not available | content of a `SCANNING`, `INFECTED` or `REJECTED` file | **409** |
| API-06 | P2 | A | Download link expiry | API-04, wait 6 min, open the link | 403 from S3 |
| API-07 | P1 | L | Scans in progress | simulator delay 30 s; upload 3 files; scans | `inProgress` 3, `oldestSubmittedAt` = the first file's landing time; gauge `scan.in-progress` 3 within 15 s; after the scans: 0 and `null` |
| API-08 | P2 | L | Scan count across instances | run two instances (two ports) on the same emulators; upload to one; scans on the other | Same count on both (it's read from DynamoDB) |
| API-09 | P2 | L | Error format | any 4xx/5xx | `application/problem+json` with `type`, `title`, `status`, `detail`, `instance`, `requestId`; upload refusals add `reason` |
| API-10 | P3 | L | Wrong method | `curl -X DELETE $B/api/v1/scans` | 405 problem with `requestId` |
| API-11 | P2 | L | OpenAPI and Swagger UI | open `$B/swagger-ui.html`; `curl $B/v3/api-docs` | UI loads; spec lists the five `/api/v1` operations, no `/actuator`; "Try it out" works for both upload styles |

## Idempotent retries (ID)

Unit tests: `IdempotentUploadTests`, `IdempotencyKeyTests`. A client sends
`Idempotency-Key: <uuid>` and repeats it when retrying; the answer to a retry carries
`Idempotent-Replayed: true`.

| Id | P | Where | Scenario | Steps | Expected |
|---|---|---|---|---|---|
| ID-01 | P1 | L | Stream retried after success | upload-stream *a.png*; **replay** it | Same id, 202, `Idempotent-Replayed: true`, current status; nothing stored twice (one object in clean). Log 2013 |
| ID-02 | P1 | L | Presigned retried before the PUT | ask-url with a key twice | Same id; second `Idempotent-Replayed: true` with a fresh URL; PUT with either works once |
| ID-03 | P1 | L | Presigned retried after the PUT | ID-02, put, ask again | Same id, status `SCANNING`/`AVAILABLE`, **no `upload`** in the body |
| ID-04 | P1 | L | Concurrent duplicates | send 5 identical streams with one key at once (`for n in 1 2 3 4 5; do curl … & done`) | All 202 with the same id; exactly one `Idempotent-Replayed: false`; one object |
| ID-05 | P1 | L | Key reused for another file | upload with key K; upload another file (other name, size or type, or the other style) with K | **422** `reason` `IDEMPOTENCY_KEY_REUSED`, naming the first file |
| ID-06 | P2 | L | Invalid key | key `has space`, `a;b`, 129 characters | 400 naming `Idempotency-Key` |
| ID-07 | P1 | L | Retry after a crash mid-stream | CR-05 with a key, then the same upload with the same key after restart | 202, `Idempotent-Replayed: false` (this attempt stored it), **same id** as the first attempt; `AVAILABLE` |
| ID-08 | P2 | L | No key | upload the same file twice without a key | Two files, two ids (the old behaviour) |
| ID-09 | P2 | L | Replay of a refused upload | upload *exe.png* with a key; replay | 415 both times (refusals aren't remembered; nothing was created) |

## Event handling: duplicates, order, garbage (EV)

Simulator off; drive with **event**. Unit tests: `ScanEventConsumerTests`, `ScanEventParserTests`, `FileRepositoryTests`.

| Id | P | Where | Scenario | Steps | Expected |
|---|---|---|---|---|---|
| EV-01 | P1 | L | Result before Object Created | stream a file; event `result <id> NO_THREATS_FOUND`; then event `created <id>` | `AVAILABLE`; `submittedForScanAt` keeps its first value |
| EV-02 | P1 | L | Same result twice | after SC-01, event `result <id> NO_THREATS_FOUND` again | No change; no second 2005 |
| EV-03 | P1 | L | Contradicting late result | after SC-01, event `result <id> THREATS_FOUND` | No change: a final status never changes |
| EV-04 | P2 | L | Object Created twice | event `created <id>` twice | One 2004; bytes counted once |
| EV-05 | P2 | L | Not JSON | event `raw 'not json'` | Deleted; log 3003; counter `events{outcome=unrecognised}` |
| EV-06 | P2 | L | Unknown detail type | event `raw '{"detail-type":"Object Deleted"}'` | Log 3003 |
| EV-07 | P2 | L | Unknown scan status | event `result <id> SOMETHING_NEW` | Log 3003; file unchanged |
| EV-08 | P2 | L | Object of another bucket or prefix | event `raw` with bucket `other-bucket` | Log 3004; deleted |
| EV-09 | P2 | L | Unknown file id | event `created 00000000-0000-4000-8000-000000000000` | Log 3004 |
| EV-10 | P2 | L | Queue unreachable | `docker compose pause sqs` for 30 s, then `unpause` | Log 3005 with growing `retry_in` (1, 2, 4… s, at most 30); health `scanQueue` DOWN; uploads still accepted; after restart events are handled |
| EV-11 | P2 | L | Dependency down while handling | simulator delay 10 s; upload; `docker compose pause dynamodb` before the result; `unpause` after 2 min | Log 3002 with growing `receive_count`; file finishes once DynamoDB is back (or by recovery, CR-03) |
| EV-12 | P3 | L | Dead-letter queue | EV-11 held longer than 5 deliveries | Message in `file-upload-scan-events-dlq`; file still finished by recovery (CR-03/CR-04) |

## Crash safety (CR)

The process is killed with `kill -9` (no shutdown hooks: a JVM crash or an out-of-memory kill) at
each point a file's state changes. After restart, every file must end in the right status, with
nothing stored twice, nothing promoted twice, and no object without a record. README "Crash
safety" explains why each case recovers. Unit tests: `InFlightRecoveryTests`, `ScanOutcomesTests`,
`UploadServiceTests`, `IdempotentUploadTests`.

To create stranded states directly, without timing a kill, write the record and the object
yourself (simulator off):

```bash
ddb() { curl -s -X POST localhost:8000 -H 'Content-Type: application/x-amz-json-1.0' \
  -H "X-Amz-Target: DynamoDB_20120810.$1" \
  -H 'Authorization: AWS4-HMAC-SHA256 Credential=l/20260101/us-east-1/dynamodb/aws4_request, SignedHeaders=host, Signature=l' \
  --data "$2"; }
id=$(uuidgen | tr '[:upper:]' '[:lower:]'); old=$(( $(date +%s) * 1000 - 600000 ))   # 10 minutes ago
curl -s -X PUT --upload-file a.png -H 'Content-Type: image/png' localhost:9099/file-upload-quarantine/uploads/$id
etag=$(curl -sI localhost:9099/file-upload-quarantine/uploads/$id | awk -F'"' 'tolower($0) ~ /^etag/ {print $2}')
# a promotion whose process died (use status/stage SCANNING and no scannedETag for a stuck scan)
ddb PutItem "{\"TableName\":\"file-upload-service-files\",\"Item\":{\"id\":{\"S\":\"$id\"},\"fileName\":{\"S\":\"a.png\"},\
\"fileType\":{\"S\":\"PNG\"},\"size\":{\"N\":\"$(wc -c < a.png)\"},\"method\":{\"S\":\"PRESIGNED_URL\"},\"status\":{\"S\":\"PROMOTING\"},\
\"createdAt\":{\"N\":\"$old\"},\"submittedAt\":{\"N\":\"$old\"},\"scannedAt\":{\"N\":\"$old\"},\"scanResult\":{\"S\":\"NO_THREATS_FOUND\"},\
\"stage\":{\"S\":\"PROMOTING\"},\"stageSince\":{\"N\":\"$old\"},\"scannedETag\":{\"S\":\"$etag\"}}}"
```

| Id | P | Where | Scenario | Steps | Expected |
|---|---|---|---|---|---|
| CR-01 | P1 | L | Killed while files wait for their scan | simulator delay 30 s; upload 2 presigned + 1 streamed; when `SCANNING`, **kill**; restart (normal delay) | All `AVAILABLE`; `submittedForScanAt` unchanged from before the kill; one object each in clean |
| CR-02 | P1 | L | Killed holding events | as CR-01 but kill just as results arrive (delay 3 s, kill at ~3 s) | Within ~60 s of restart (SQS visibility timeout), all files final; no duplicates |
| CR-03 | P1 | L | Promotion interrupted, event gone | simulator off, recovery 5 s; create a stranded `PROMOTING` file (above); start the app | Within a recovery run: `AVAILABLE`; object moved to clean. Logs 2010, 2005; counter `recovery{action=promotion_resumed}` |
| CR-04 | P1 | L | Result event lost, GuardDuty tag present | simulator off, overdue 10 s, recovery 5 s; stream a file; event `tag <id> NO_THREATS_FOUND` (no result event) | After ~10–15 s: `AVAILABLE`. Logs 2011, 2005; counter `recovery{action=result_from_tag}`. Repeat with `THREATS_FOUND`: `INFECTED`, stays in quarantine |
| CR-05 | P1 | L | Killed during a streamed upload | stream *big.png* with `curl --limit-rate 2M`; kill after 3 s | Client: connection error, no id. No object in quarantine (no partial file). Record `AWAITING_UPLOAD` (find it with a DynamoDB `Scan` on `method = STREAM`), not counted as scanning |
| CR-06 | P1 | L | Stored, but its status couldn't be written | (unit-tested: `aStoredFileIsAcceptedEvenIfItsStatusCantBeUpdated`) end-to-end: stream *big.png* at `--limit-rate 2M` and `docker compose pause dynamodb` ~1 s before it ends; `unpause` after 30 s | 202 with id and `AWAITING_UPLOAD`; log 2012; S3's event makes it `SCANNING` once DynamoDB is back |
| CR-07 | P1 | L/A | Service down longer than a URL's lifetime | `FILE_UPLOAD_SERVICE_URL_TTL=1m`; ask-url; stop the app; put; wait 5 min; start the app | The file is handled: `AVAILABLE`. The record's `expiresAt` is ≥ 15 days after the URL's expiry. *DynamoDB Local doesn't run TTL: the deletion itself can only be tested in AWS (stop the service for more than 2 hours)* |
| CR-08 | P2 | L | Killed during promotion, real timing | simulator delay 3 s; upload 10 files; kill ~3.2 s later; restart | Every file `AVAILABLE` (some via redelivery, some via 2010); none in both buckets; none twice in clean |
| CR-09 | P2 | L | Graceful stop (SIGTERM) mid-flow | as CR-01 with `kill` (no `-9`) | Stops within ~15 s; in-flight events finish or return to the queue; same end state as CR-01 |
| CR-10 | P2 | L | Crash loop | kill the app 3 times within a minute while files are in flight | All files final after the last restart; dead-letter queue empty or its messages harmless to redrive |
| CR-11 | P1 | L | Two instances racing on recovery | two instances, recovery 5 s; CR-03 | One promotion; the second instance changes nothing; at most one 2005 |

## Configuration and production mode (CF)

Unit tests: `ProductionSafeguardsTests`, `FileUploadServicePropertiesTests`, `ConfigCheckTests`.

| Id | P | Where | Scenario | Steps | Expected |
|---|---|---|---|---|---|
| CF-01 | P1 | L | Production mode refuses unsafe settings | `FILE_UPLOAD_SERVICE_MODE=production ./gradlew bootRun`; then again with `FILE_UPLOAD_SERVICE_S3_ENDPOINT=http://localhost:9099 FILE_UPLOAD_SERVICE_SCAN_SIMULATOR=true` | Startup fails with 1005 naming each setting to change (never its value): SSM path, Vault address, scan queue URL, API docs on, text logs; the second run also emulator endpoints and the simulator |
| CF-02 | P1 | L | Invalid settings fail fast | start with each of: `FILE_UPLOAD_SERVICE_MAX_SIZE=6GB`, `_URL_TTL=8d`, `_QUARANTINE_PREFIX=uploads` (no slash), `_CLEAN_BUCKET=file-upload-quarantine` + `_CLEAN_PREFIX=uploads/x/`, `_SCAN_QUEUE_URL=not-a-url` | Startup fails naming the setting and the rule |
| CF-03 | P2 | L | No queue | `FILE_UPLOAD_SERVICE_SCAN_QUEUE_URL= ./gradlew bootRun` (emulators up) | Starts; log 1011; uploads stay `AWAITING_UPLOAD`; health `scanQueue` UNKNOWN |
| CF-04 | P2 | L | Restart check | `curl -X POST $B/actuator/configcheck` | `restartable: true` |
| CF-05 | P3 | L | Same bucket, separate folders | `_CLEAN_BUCKET=file-upload-quarantine _CLEAN_PREFIX=files/` | Clean files land under `files/` of the quarantine bucket |

## Security (SE)

| Id | P | Where | Scenario | Steps | Expected |
|---|---|---|---|---|---|
| SE-01 | P1 | L | No unscanned or infected file is ever served | try `contentUrl`/`/content` for `SCANNING`, `INFECTED`, `NOT_SCANNED`, `REJECTED` files | 409 every time; nothing for them in the clean bucket |
| SE-02 | P1 | L | The served bytes are the scanned bytes | PS-10 (no overwrite) + promotion copies only the scanned ETag (CR-03 uses it) | Clean object's ETag = quarantine object's ETag at scan time |
| SE-03 | P1 | L | Disguised content refused | ST-03, ST-04, PS-11, FT-03 | Refused or `REJECTED`; never `AVAILABLE` |
| SE-04 | P1 | L | Threat details not exposed | SC-02 | Response has no threat name or GuardDuty reasons |
| SE-05 | P2 | L | Path traversal in names | PS-17 | Only the last path segment kept; S3 keys are ids, never names |
| SE-06 | P1 | L | Errors leak no internals | `docker compose pause s3`; stream a file (fails after the 10 s AWS timeout); `unpause` | 503, `Retry-After: 5`, generic `detail`; the log 3001 has the AWS error, the response doesn't |
| SE-07 | P1 | A | Only `/api/` is public | through the ALB: `/actuator/health`, `/v3/api-docs`, `/swagger-ui.html` | 404 from the load balancer |
| SE-08 | P1 | A | API docs off in production | production pods: `/v3/api-docs` inside the VPC | 404 |
| SE-09 | P2 | A | Buckets private | anonymous GET of a quarantine and a clean object URL | 403 |
| SE-10 | P1 | L/A | **No authentication yet** | any request without credentials | Accepted. Record as a known gap until authentication is built |

## Observability (OB)

| Id | P | Where | Scenario | Steps | Expected |
|---|---|---|---|---|---|
| OB-01 | P1 | L | Request id | send `X-Request-Id: ticket-1`; and none | Echoed `ticket-1`; else a generated UUID; in error bodies as `requestId`; on every log line of the request |
| OB-02 | P1 | L | Every operational line is coded | run PS-01, ST-03, SC-02, CR-03 | Each step's log has `event.code` and `event.reference`; `docs/runbook.md` has a section per code |
| OB-03 | P2 | L | JSON logs | `FILE_UPLOAD_SERVICE_LOG_FORMAT=ecs scripts/dev.sh run` | Every stdout line is one JSON object (ECS fields) |
| OB-04 | P2 | L | Health per dependency | `curl $B/actuator/health`; pause each emulator in turn | `s3`, `dynamoDb`, `scanQueue` go DOWN; `/actuator/health/readiness` stays UP |
| OB-05 | P2 | L | Metrics | after a mixed run | `uploads{method,outcome}`, `uploads.bytes`, `scan.duration`, `files.time-to-available`, `events.wait`, `events{outcome}`, `scan.in-progress`, `scan.overdue`, `recovery{action}`, `aws.requests{service,operation,outcome}` all present (README "Metrics") |

## Non-functional: no filesystem, memory, load, shutdown (NF)

| Id | P | Where | Scenario | Steps | Expected |
|---|---|---|---|---|---|
| NF-01 | P1 | L | No filesystem needed | `scripts/dev.sh docker` (read-only root, 16 MB in-memory `/tmp`); run PS-01, ST-01, ST-12, SC-02 | All pass; no errors writing files |
| NF-02 | P1 | L | Memory flat with file size | stream *big.png* 5 times; `curl $B/actuator/metrics/jvm.memory.used?tag=area:heap` before and after | No growth proportional to file size (~50 MB heap locally) |
| NF-03 | P2 | L | Concurrent streams | 20 parallel streams of *big.png* | All 202 (or 503 beyond `max-concurrent-streams`); no errors; memory bounded |
| NF-04 | P2 | L | Load | `node scripts/load-http.mjs 50 60 $B/api/v1/scans` | No errors; record p50/p99 and CPU for README "Capacity" |
| NF-05 | P2 | L | Graceful shutdown with a slow stream | start a 20 MB stream at 1 MB/s; `kill` (SIGTERM) | The stream finishes if within 30 s, else is cut; app exits within ~35 s; state consistent (CR-05 if cut) |
| NF-06 | P1 | A | Rolling deploy under load | NF-04 traffic while `kubectl rollout restart` | No client errors; uploads in flight finish or are retried |
| NF-07 | P2 | A | Keep-alive vs ALB | long-running test through the ALB | No 502s (Tomcat keep-alive 130 s > ALB idle 120 s) |

## AWS-only scenarios (AW)

Run in a test account with `deploy/aws/` applied. Pick these up alongside the A rows above.

| Id | P | Scenario | Steps | Expected |
|---|---|---|---|---|
| AW-01 | P1 | End to end with real GuardDuty | `FILE_UPLOAD_SERVICE_URL=<test host> scripts/try-upload.sh presigned a.png` and `stream a.pdf` | `AVAILABLE` within GuardDuty's scan time (seconds to minutes); objects tagged `GuardDutyMalwareScanStatus` |
| AW-02 | P1 | Malware with real GuardDuty | SC-08 | `INFECTED`; security alert raised from log 2006 |
| AW-03 | P1 | Protection plan health | GuardDuty console, or the "Resource Status" events | `ACTIVE`, tagging on |
| AW-04 | P1 | IAM least privilege | remove one permission at a time from the service role (`s3:GetObjectTagging`, `sqs:DeleteMessage`, `dynamodb:Query`) | The matching feature fails with a coded log (3001/3002/3006/3007), nothing else breaks |
| AW-05 | P2 | GuardDuty can't read | remove GuardDuty's role permission on the bucket | `NOT_SCANNED` with `ACCESS_DENIED` (2007): alert |
| AW-06 | P2 | KMS-encrypted bucket | SSE-KMS on quarantine; run AW-01 | `AVAILABLE` (the roles have `kms:Decrypt`) |
| AW-07 | P2 | Lifecycle | check the quarantine bucket's rule | Infected and unscanned objects expire after 30 days |
| AW-08 | P2 | Records expire | after PS-12 in AWS | DynamoDB TTL deletes the record ~15 days after the URL expired |
| AW-09 | P3 | Large file through the ALB | stream a 25 MB file through the ingress | 202 (no body-size limit in front of the service below `max-size`) |

## Policy extraction (EX)

Opted-in uploads are read by the model once AVAILABLE; results are internal. Locally the Bedrock
simulator answers (a policy with number `SIM-…`); markers make it fail. Unit tests (Bedrock SDK
mocked): `BedrockPolicyModelTests`, `PolicyParserTests`, `ExtractionWorkerTests`,
`ExtractionResultsTests`, `SimulatedBedrockTests`, `InternalExtractionControllerTests`.

| Id | P | Where | Scenario | Steps | Expected |
|---|---|---|---|---|---|
| EX-01 | P1 | L | Presigned image, opted in | **extract** upload-presigned *a.png* | File `AVAILABLE`; extraction `PENDING` → `COMPLETED` within seconds, `attempts` 1, `modelId`, `policy` with every field `{value, confidence}`; money as `{amountMinor, currency, scale}`. Logs 2014 (with `fields_found`, tokens) |
| EX-02 | P1 | L | Streamed PDF, opted in | **extract** upload-stream *a.pdf* (also `?extract=insurance-policy`, lower case) | `COMPLETED` |
| EX-03 | P1 | L | Each extractable type | EX-01 for JPEG, GIF, WebP | `COMPLETED` |
| EX-04 | P1 | L | Type Bedrock can't read | stream a HEIC or TIFF with `extract` | **422** `EXTRACTION_NOT_SUPPORTED` naming the type; nothing stored |
| EX-05 | P1 | L | Over Bedrock's size | ask-url `{"…","size":4000000,"extract":"INSURANCE_POLICY"}` for PNG; and a PDF over 4,500,000 | 422 naming the limit; at the limit (3,750,000 / 4,500,000) 201 |
| EX-06 | P2 | L | Unknown kind | `extract=CAR` | 400 listing `INSURANCE_POLICY` |
| EX-07 | P1 | L | Extraction off | no model configured; upload with `extract` | 422 `extraction isn't available`; log 1014 at startup; uploads without `extract` unaffected |
| EX-08 | P1 | L | Infected file | **extract** upload *eicar.png* | File `INFECTED`; extraction `NOT_EXTRACTED`; the model is never called (no 2014/3008) |
| EX-09 | P2 | L | Before the file is available | simulator scan delay 30 s; **extraction** while `SCANNING` | `AWAITING_FILE` |
| EX-10 | P1 | L | Not opted in | **extraction** of a file uploaded without `extract` | 404 |
| EX-11 | P1 | L | Temporary failures, then give up | retries 2 s; **extract** upload-stream *throttle.png* | Two 3008 (`MODEL_THROTTLED`, `retry_at` 2 s then 4 s later), then `FAILED`, `attempts` 3, `failure` `MODEL_THROTTLED`; 2015 |
| EX-12 | P1 | L | Garbled answer | as EX-11 with *garbage.png* | `FAILED` after 3 attempts, `ANSWER_NOT_JSON` |
| EX-13 | P1 | L | Permanent failure | as EX-11 with *refuse.png* | `FAILED` at once, `attempts` 1, `MODEL_REFUSED_REQUEST` |
| EX-14 | P1 | L | Killed during a model call | slow model calls (setup); **extract** a file; when `PENDING` for ~4 s, **kill**; restart with fast calls | `COMPLETED` once the dead worker's lease ends (≤ 35 s), `attempts` 2 |
| EX-15 | P1 | L | Personal data stays out of logs | after EX-01 | `grep` the logs for the holder's name, address and policy number: no match |
| EX-16 | P2 | L | Idempotency covers the opt-in | upload with key K and `extract`; again with K and no `extract` | 422 `IDEMPOTENCY_KEY_REUSED` |
| EX-17 | P2 | L | Concurrency and backlog | `FILE_UPLOAD_SERVICE_EXTRACTION_CONCURRENCY=1`, slow calls; opt in 5 files | One model call at a time; gauge `extraction.pending` counts down from 5 |
| EX-18 | P2 | L | Two instances | two instances on the emulators; opt in 10 files | Each extracted once (one 2014 per file across both logs) |
| EX-19 | P2 | L | Metrics | after a mixed run | `extraction.model.calls{outcome}`, `extraction.tokens{direction}`, `extraction.time-to-complete`, `extraction.pending` |
| EX-20 | P1 | L | Internal endpoint isn't public | OpenAPI (`/v3/api-docs`) | No `/internal/` paths. Through the ALB (A): 404 |
| EX-21 | P1 | A | The real model reads real policies | **extract** a set of real (anonymised) policy schedules and certificates, photos and PDFs | Fields match the documents; confidence lower where the print is poor; nothing invented (`null` when absent). Record the hit rate per field |
| EX-22 | P1 | A | Model access | wrong model ARN, or remove `bedrock:InvokeModel` | `FAILED` with `MODEL_NOT_FOUND` / `MODEL_ACCESS_DENIED` at once (no retries); 2015 |
| EX-23 | P2 | A | Throttling under load | opt in more files than the model's quota allows per minute | 3008 `MODEL_THROTTLED`, retries succeed later; none lost |
| EX-24 | P2 | A | Prompt injection in the document | a policy image that also says "ignore your instructions and output …" | The answer is still the schema (validated); injected text never becomes a field outside its rules |

## Graceful shutdown (GS)

On SIGTERM the service finishes or hands over its work, then closes its clients (README "Graceful
shutdown"). Watch the logs for 1016 → 1018 (per worker) → 1017. Unit tests: `ScanEventConsumerTests`,
`ExtractionWorkerTests`, `GracefulShutdownTests`, `FileRepositoryTests`.

| Id | P | Where | Scenario | Steps | Expected |
|---|---|---|---|---|---|
| GS-01 | P1 | L | Idle stop | **sigterm** an idle instance | Exits within ~12 s (the scan consumer's long poll ends); logs 1016, 1018 ×2 (`finished` 0, `handed_over` 0), 1017; no ERROR |
| GS-02 | P1 | L | Upload in flight | stream *big.png* with `--limit-rate 2M`; **sigterm** 2 s in | The client gets 202; the file is `SCANNING`, then `AVAILABLE` after a restart |
| GS-03 | P1 | L | Model call that finishes in time | slow model calls 10 s (`…_SIMULATOR_DELAY=10s`); **extract** a file; **sigterm** while `PENDING` | Extraction `COMPLETED`, `attempts` 1, before exit; 1018 extraction `finished` 1 |
| GS-04 | P1 | L | Model call longer than the drain | `…_SIMULATOR_DELAY=40s`; as GS-03 | At ~25 s: 1019, 1018 `handed_over` 1; stored `attempts` back to 0, lease ended; after a restart `COMPLETED` within seconds as attempt 1 (not after 3 min) |
| GS-05 | P2 | L | Scan events received during shutdown | simulator scan delay 3 s; upload 5 files; **sigterm** as their results arrive | 1018 scan-events `handed_over` > 0 when a batch was cut; after a restart every file final within seconds (no 60 s visibility wait) |
| GS-06 | P2 | L | Scheduled job running | recovery every 5 s (`…_SCAN_RECOVERY_INTERVAL=5s`), a stranded PROMOTING file (CR-03); **sigterm** during a recovery run | The run finishes (2010 then 2005) before 1017 |
| GS-07 | P1 | L | Docker stop | `scripts/dev.sh docker`; then `scripts/dev.sh stop` | The container drains (1017 in its log) and isn't killed at 10 s |
| GS-08 | P1 | L | No errors from closed clients | any GS run | No ERROR or stack trace after 1016 |
| GS-09 | P1 | A | Rolling deploy | `kubectl rollout restart` under load with extractions running | No client errors; 1017 in each old pod's log within ~45 s of SIGTERM; extractions handed over complete on new pods |

## Integration tests against real AWS (IT)

`./gradlew integrationTest` (README "Testing against real AWS"). Run in a test account with the
resources from `deploy/aws/`; never part of the normal build.

| Id | P | Where | Scenario | Steps | Expected |
|---|---|---|---|---|---|
| IT-01 | P1 | L | Not configured | `./gradlew integrationTest` with no `FILE_UPLOAD_SERVICE_IT_*` | Stops before running, listing the settings |
| IT-02 | P1 | A | Bedrock reads a policy | `…IT_REGION`, `…IT_BEDROCK_MODEL_ID`; `--tests '*Bedrock*'` | `BedrockPolicyExtractionIT` passes: policy number, holder, dates, premium, sum insured, excess as drawn |
| IT-03 | P1 | A | S3 enforces presigned URLs | `…IT_QUARANTINE_BUCKET`, `…IT_CLEAN_BUCKET`; `--tests '*S3*'` | 403 for another type, length or no `If-None-Match`; 200 as signed; 412 twice; promotion only for the scanned ETag |
| IT-04 | P1 | A | DynamoDB transitions | `…IT_TABLE`; `--tests '*FileRepository*'` | Every transition, the in-flight index and the extraction lease behave as specified |
| IT-05 | P2 | A | GuardDuty scans | `…IT_GUARDDUTY=true` with the buckets; `--tests '*GuardDuty*'` | Clean file tagged `NO_THREATS_FOUND`, EICAR `THREATS_FOUND`, within 5 minutes |
| IT-06 | P2 | A | A setting missing for one test | run all with only the Bedrock settings | The S3 and DynamoDB tests **fail** naming the missing setting (not skipped) |

