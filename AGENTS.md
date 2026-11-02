# AGENTS.md

Guidance for AI coding agents (and humans) working in this repository. `README.md` is the
user-facing documentation: API, configuration, running locally. These rules apply to every change.

## Project

Accepts image and PDF uploads from front-ends, has GuardDuty Malware Protection for S3 scan them
in a quarantine bucket, and copies clean files to a clean bucket. Two upload styles on purpose
(`docs/upload-styles.md`, for the team to compare): presigned S3 PUT URLs and streaming through the
service. Flow: bytes land in quarantine → S3 "Object Created" and GuardDuty "Object Scan Result"
reach the service via EventBridge → SQS → `ScanOutcomes` moves the file's status in DynamoDB and
promotes clean files. README "File lifecycle" has the states.

- **Stateless, no filesystem** (a requirement): state only in DynamoDB, S3 and SQS; never write
  files, never parse multipart (it spools to disk), never buffer a whole upload in memory.
- **GuardDuty has no scan-started event or in-progress count**: "picked up" is S3's Object Created
  time, and the count comes from the sparse `scanning` index. Don't promise more than that.
- **Every status change is a conditional write** that only moves forward; events are at-least-once
  and unordered. Keep new transitions idempotent and test redelivery.
- **Only scanned bytes are promoted**: quarantine writes carry `If-None-Match: *`, promotion copies
  with `CopySourceIfMatch` on the scanned ETag. Don't remove either.
- Local runs use free emulators (S3Mock, ElasticMQ, DynamoDB Local) and the in-service
  `GuardDutySimulator`; LocalStack needs a paid token and MinIO no longer publishes images.
- Not built yet: authentication (every endpoint is open). REST only, no gRPC. Deployed on EKS.

<!-- Describe the main flows, external systems it talks to, and anything surprising. Keep it short;
     update it when the architecture changes. -->

- Java 25 (Gradle toolchain, downloaded by the Foojay resolver if missing),
  Spring Boot 4.1.2, Gradle wrapper with Kotlin DSL, Lombok
- Jackson 3 (`tools.jackson.*`); annotations still come from `com.fasterxml.jackson.annotation`
- Spring Boot Actuator and Micrometer for health and metrics

## Commands

```bash
./gradlew test                 # all tests (offline); must pass before committing
./gradlew compileJava          # fast compile check
scripts/dev.sh check           # verify local prerequisites
scripts/dev.sh run [args...]   # start dependencies, then the app on :8080; args go to Spring Boot
scripts/dev.sh status          # dependency and app health
scripts/dev.sh hooks           # install the git hooks: commit messages, plaintext secrets (once per clone)
scripts/test-commit-msg.sh     # test the hook after changing it or .commitlintrc.json
scripts/check-secrets.sh --all # scan tracked files for plaintext secrets (the hook scans staged files)
scripts/test-check-secrets.sh  # test the secrets check after changing it
```

Never run `scripts/dev.sh reset` unless asked: it deletes local data.

## Layout

```
com.crisinis.fileuploadservice
├── config/       @ConfigurationProperties records (FileUploadServiceProperties), beans, Clock, lifecycle logging,
│                 ProductionSafeguards (what production mode refuses)
├── configdata/   startup loaders for AWS SSM Parameter Store and Vault (spring.factories), and
│                 ConfigCheck: re-reads them and checks a restart would succeed (/actuator/configcheck)
├── logging/      LogEvent catalogue, EventLog, LogFields, RequestIds: logs for production support
├── model/        immutable records
├── service/      business logic
├── storage/      S3: presigned URLs, streaming PUTs, promotion (FileStorage)
├── persistence/  all database access (one repository class per store): FileRepository (DynamoDB)
├── events/       the SQS consumer, EventBridge event parsing, the local GuardDuty simulator
├── client/       calls to external systems (each honours the outbound proxy); none yet
└── web/          REST controllers, ApiExceptionHandler (RFC 9457 problem details), RequestIdFilter
```

## Service standards

**Build and dependencies**
- Gradle wrapper with Kotlin DSL; the Java toolchain is the current LTS; the Foojay resolver is in
  `settings.gradle.kts`.
- Spring Boot's latest **stable** release (never milestones or RCs); let its BOM manage versions.
  Anything outside the BOM gets an explicit version, checked against Maven Central first.
- Before relying on what a third-party library can do (proxy support, an API overload, a
  protocol), verify it in the library's source or bytecode rather than assuming. Work around a
  library bug with a small, documented subclass or patch plus a guard test that fails once
  upstream is fixed.

**REST API**
- Versioned resource paths: `/api/v1/...`.
- Errors are RFC 9457 `ProblemDetail`s from `ApiExceptionHandler`: 400 for invalid input (with a
  message saying what's wrong), 404 for unknown resources, 503 when an upstream dependency is
  unavailable. Services throw domain exceptions; controllers don't build error responses.
- Validate input at the boundary, and never interpolate unvalidated input into queries or commands.

**Logging** (for production support and log processors)
- Lombok `@Slf4j` on the class; never declare `LoggerFactory` loggers by hand.
- **Every operational log line is a catalogued event:**
  `LogEvent.X.at(log).with("order_id", id).cause(e).log()`. Don't call `log.info/warn/error`
  directly. A new situation gets a new `LogEvent` constant, with a stable code
  (`FILE_UPLOAD_SERVICE-nnnn`, never reused or renumbered), a fixed level and a summary saying what
  happened, what it means for callers and what happens next. It also gets a section in
  `docs/runbook.md` with the same anchor. `LogEventCatalogueTests` fails the build if either is
  missing.
- Code ranges: 1xxx lifecycle, 2xxx requests and business outcomes, 3xxx dependencies, 9xxx
  unexpected failures.
- Levels: INFO for lifecycle and business outcomes, WARN for problems the service recovers from or
  that callers retry, ERROR for things a person must act on. Alert rules key on codes, so keep a
  code's level stable.
- Details are `file-upload-service.*` fields (snake_case) holding identifiers and reasons:
  never whole requests, personal data, secrets or tokens.
- Field names follow the Elastic Common Schema (`event.code`, `event.reference`,
  `http.request.id`, `error.*`); constants are in `LogFields`.
- Production logs are ECS JSON from Spring Boot's structured logging
  (`FILE_UPLOAD_SERVICE_LOG_FORMAT=ecs`), one object per line. Nothing else may write to stdout in that
  mode; `StructuredLoggingTests` checks.
- Every request gets an id (`RequestIdFilter`): returned in `X-Request-Id`, and as `requestId` in
  error bodies. Work handed to other threads must carry it:
  `try (RequestIds.Scope s = RequestIds.bind(requestId)) { ... }`.

**Configuration and secrets** (README "Configuration and secrets")
- Every setting is a field on a `@ConfigurationProperties` record under `file-upload-service.*`
  with a `@DefaultValue`, **validated in the record's compact constructor**, never only in the bean
  that uses it. Bad config then fails fast at startup, and `ConfigCheck`, which finds a stored
  value that would stop a restart by binding the records, sees it too. Bind every new record in
  `ConfigCheck.validate`.
- Document each setting in `application.yml` (with an `FILE_UPLOAD_SERVICE_…` environment placeholder
  where operators will set it) and in the README configuration table.
- Deployed settings come from **AWS SSM Parameter Store**, secrets from **HashiCorp Vault**, both
  read once at startup by `configdata/` (precedence: command line > environment > Vault >
  Parameter Store > `application.yml`). Once configured they **fail closed**: a store that can't
  be read stops startup (FILE_UPLOAD_SERVICE-1004). They are reached directly on the private network (VPC
  endpoint), not through the outbound proxy.
- **Secrets never appear in plaintext** in git, logs or error messages: not in `application.yml`,
  tests, fixtures or docs. A new secret is a property Vault supplies when deployed, with at most a
  local development default. Secrets that must be in the repository are SOPS-encrypted under
  `config/secrets/`. `scripts/check-secrets.sh` (the pre-commit hook and the `secrets` workflow)
  enforces this; never `git commit --no-verify` past it.
- **Production mode** (`file-upload-service.mode=production`, `FILE_UPLOAD_SERVICE_MODE`) refuses to start with settings
  that are unsafe in production (FILE_UPLOAD_SERVICE-1005). A new setting whose default is only safe locally (a
  development credential, a wildcard that opens access, a debugging switch) gets a rule in
  `ProductionSafeguards.violations` that names the setting to change, never its value.
- **The configuration check** (`ConfigCheck`) re-reads the stores every 5 minutes and on demand
  (`POST /actuator/configcheck`) and reports, without touching readiness, whether a restart would
  succeed (FILE_UPLOAD_SERVICE-1006). Keep health checks for the load balancer independent of it and of
  dependencies.

**Deployment** (`docs/deployment.md`, files in `deploy/`)
- Three or more instances, one per availability zone, behind an AWS ALB; any instance serves any
  request, so state that must survive an instance lives in a dependency, never in memory only.
- Timeouts agree with the load balancer: ALB idle timeout (120 s) < `server.tomcat.keep-alive-timeout`
  (130 s); change both together. The ALB health check is `/actuator/health/readiness`.
- Graceful shutdown: the load balancer stops routing first (the EKS `preStop`
  sleep of 20 s), then SIGTERM; each shutdown phase is limited to 30 s.
- Long-lived connections (SSE, WebSockets) send a heartbeat more often than the shortest idle
  timeout on the path (30 s), and are closed first on shutdown, with a signal to reconnect
  elsewhere, before dependencies are released.
- `deploy/` holds examples with `CHANGE` placeholders, never real account ids, hosts or secrets.

**Metrics and health**
- Micrometer. REST calls are timed by `http.server.requests` with p50/p95/p99 configured.
- Every call to a dependency (database, external API, broker) gets a timer tagged with
  `outcome=success|error`, plus counters for what it moves (rows, points, messages).
- Meter names are `file-upload-service.<area>.<thing>`; each meter keeps the same tag keys.
  Document meters in the README metrics table.
- An Actuator `HealthIndicator` for each dependency.
- **Latency of queued or asynchronous work** (messages, streams, scheduled batches): stamp each
  item with `System.nanoTime()` when it arrives and keep the stamp on it. Time it to where it
  leaves (what callers experience), and separately the time it waited inside, which shows a growing
  queue as growing delay. Give these timers p50/p95/p99, and a suggested alert in the runbook.
- **Capacity** (README "Capacity"): measured cost per unit of work (cores, heap, egress per 1,000
  requests or events/s) and sizing rules that survive losing an availability zone. Measure with
  `scripts/load-http.mjs` (or a load script for the service's own protocol) and say where and how
  it was measured. A change to a hot path measures again and updates the numbers in the same pull
  request.

**Outbound network (security requirement)**
- Every connection to an external host goes through `file-upload-service.outbound-proxy` when it
  is set, which is usually tinyproxy (HTTP `CONNECT`), or SOCKS5 where a client only supports that.
  Internal dependencies on the same network (the database) are not proxied; say so in the README.
- **Fail closed:** if a client can't honour the proxy, refuse to start rather than connect
  directly; retries go through the proxy, never around it.
- Test each client against a fake proxy server in the test suite: it must reach the proxy, and
  fail when the proxy refuses.
- Document the destination hosts and ports the proxy must allow.

**Design and code structure** (full rules and reasons: `docs/design-principles.md`)
- A type earns its place by holding an invariant, owning a lifecycle or resource, hiding a
  boundary, or naming a domain concept. Being a noun isn't enough.
  - Records for data, immutable, with behaviour that derives new values on the record.
  - Interfaces only at real seams: two or more implementations, or a port tests substitute.
    No `Impl` twins.
  - Compound map keys are private nested records. Enums for closed sets.
  - No `Helper`/`Util`/`Manager` grab bags.
  - Composition over inheritance, except framework hooks.
- Each method has one purpose, nameable without "and". It either decides (pure) or acts (I/O,
  state). A coordinator only calls its steps in order. Guard clauses first; one screen at most;
  four or five parameters at most, or a missing record.
- Expected absence is `Optional`; exceptions are for failures the caller can't continue from.
- Record decisions, patterns (used and deliberately not used) and hot paths in README "Design",
  in the same pull request as the change.

**Performance: JIT and GC**
- Know the hot paths: per request, per message, per message × subscriber. Write them in README
  "Design".
- On hot paths:
  - Keep methods small so they inline (HotSpot: under 35 bytes always, hot ones up to 325).
  - Keep call sites monomorphic: final classes, records, enums, private or static methods.
  - Use loops rather than streams.
  - No exceptions for routine outcomes.
  - Build constants, patterns, messages and lookup indexes once, not per call.
- Short-lived objects are cheap; don't trade clarity for them. What costs is allocation
  multiplied by fan-out, boxing in hot collections, and memory that grows with traffic. Bound
  every queue, buffer and cache; keep the latest value rather than a backlog per client.
- Register meters once and keep the reference; no `builder().register()` per event.
- Default G1 and ergonomics; set the memory limit in deployment, and tune only with a recording.
- Claim performance only with a measurement (JFR: `jfr view allocation-by-class`, `hot-methods`;
  JMH for micro-benchmarks). A `perf` commit carries before-and-after numbers.

**Numbers**
- Money, prices and quantities are never `double` or `float`. Use a fixed-point decimal type whose
  scale and rounding are configured in one place and fixed at startup. The owner controls
  precision. `BigDecimal` is only allowed at system boundaries.
- Don't claim something is faster without measuring it.

**Time, concurrency and resilience**
- Inject `java.time.Clock`; never call `Instant.now()` in services or controllers.
- Timeouts on all I/O; retries with backoff for connections to external systems; virtual threads
  for blocking I/O.
- Lifecycle beans (`SmartLifecycle`) that recover state before traffic starts and flush it on
  shutdown, when the service holds state in memory.

**Tests**
- JUnit 5, AssertJ, Mockito. Tests run offline: no network, no database, no Docker.
- Controllers: `@WebMvcTest` with `@MockitoBean`. Time: a controllable `Clock`. Async: Awaitility,
  never `Thread.sleep` in assertions. Reactive timing: `TestScheduler`.
- Every behaviour change comes with tests. Code that integrates with real systems is also verified
  by running the app against them, and the result is reported honestly.

**Documentation and local development**
- `README.md` covers: what the service does, with a **Features** table at the top (one row per
  feature, kept current in the same pull request) and a diagram; **Running locally** (prerequisites
  table, quick start, `scripts/dev.sh` commands, running without the script, troubleshooting);
  the REST API; configuration; metrics; data schema; layout; **Design** (decisions, patterns,
  hot paths); **Contributing** (commits, hook, branching).
- Dependencies for local development run from `docker-compose.yml` with health checks.
- Shell scripts are bash 3.2 compatible (macOS) and also work under bash 5 (CI), and are
  shellcheck clean.

## Working agreements

- When a request is genuinely ambiguous (which library is meant, what a feature should do), ask.
  Otherwise choose a sensible default and say which one you chose.
- Verify before claiming: build, run the tests, run the app where it matters, and report failures
  as failures.
- One branch per change, cut from `develop` (from `main` if there's no `develop`). Never commit
  directly to `main` or `develop`.
- Commit, push, open pull requests, merge and delete branches only when asked to.
- Stage explicit paths. Never commit IDE files (`.idea/`, files the IDE staged) or edits you didn't
  make; mention them instead.
- Never `git commit --no-verify`. If the hook rejects a message, fix the message.
- Don't change repository settings, rulesets or remote branches unless asked. Scripts that do
  should default to a dry run.
- A developer may be running the app from their IDE. Check with `pgrep -fl <MainClass>`, start
  your own copy with a marker argument (`scripts/dev.sh run --file-upload-service.instance=agent`),
  and stop only that one (`pkill -f 'file-upload-service.instance=agent'`). Never kill processes
  by class name.

## Commits and releases (semantic-release)

semantic-release picks the version and writes release notes from the commits on `main`, so every
commit follows [Conventional Commits](https://www.conventionalcommits.org/). The `release`
workflow (`.github/workflows/release.yml`) runs it on every push to `main`, after the tests pass,
and publishes the tag and GitHub release. The rules are in
`.commitlintrc.json`, enforced by `.githooks/commit-msg` locally and by the `commitlint` workflow
on pull requests. `.releaserc.mjs` maps commit types to releases and writes the release notes: a
line per change, then each released commit's body (trailers left out) under "Details", so write
bodies for readers.

```
<type>(<scope>)!: <subject>          ← ≤ 100 characters (aim for ≤ 72)

<body: why, and anything non-obvious about how; wrapped at 100>

Refs: #123
Co-Authored-By: Name <email>
BREAKING-CHANGE: <what breaks and how to migrate>
```

| Type | Use for | Release |
|---|---|---|
| `feat` | new behaviour for API users or operators | minor |
| `fix` | a bug fix | patch |
| `perf` | faster or leaner, same behaviour | patch |
| `refactor` | restructuring with no behaviour change | patch |
| `revert` | undoing an earlier commit | patch |
| `docs`, `test`, `build`, `ci`, `chore`, `style` | everything else | none |
| any type with `!` or a `BREAKING CHANGE:` footer | incompatible change | **major** |

- **Scopes:** `api`, `uploads`, `storage`, `config`, `logging`, `deploy`, `deps`. Lower-case kebab-case; other scopes only warn.
- **Subject:** an imperative verb, **starting with a lower-case letter** (`add REST endpoint`, not
  `REST endpoint…`), no full stop. Describe the effect for a changelog reader, not the
  implementation.
- **Breaking changes:** use `!` for any incompatible change to the API, configuration keys or data
  schema, and describe it in a breaking-change footer. Spell it `BREAKING-CHANGE: ` or
  `BREAKING CHANGE: ` (upper case, singular, colon, space). semantic-release recognises only those
  two keywords (`.releaserc.mjs`), but matches them in any case, with or without a colon, at the
  start of **any** body line: the plural silently loses the major release, and a prose line
  starting with "breaking change" silently causes one. The hook rejects both.
- **The breaking-change footer goes last.** semantic-release takes everything from the footer to
  the end of the message as the release note, so any line after it (`Co-Authored-By`, `Refs`)
  ends up in the published notes. When the message has trailers, write the hyphenated
  `BREAKING-CHANGE:` as the **last line of the trailer block**, directly after them with no blank
  line (indent continuation lines). The spaced `BREAKING CHANGE:` isn't a valid Git trailer, so
  putting it there would break the trailer block, and GitHub would drop the co-author credit.
- **Write `#N` only for a real reference.** Any `#4` in a message becomes "closes #4" in the
  release notes, and the release bot comments on that issue or PR. To mention a pull request in
  passing, write "PR 4".
- Keep each commit to one logical change.

## Branching and merging

| Branch | Receives | Merge method |
|---|---|---|
| `main` | PRs from `develop` and hotfix branches | **squash**: one commit per PR |
| `develop` | PRs from work branches; back-merges from `main` | **merge commit**: never squash or rebase |
| `feat/…`, `fix/…`, `docs/…`, `ci/…` | your commits | branched from `develop` |

- **Work branch → `develop`: merge commit.** Squashing or rebasing would give the commits new ids
  and make every branch cut from `develop` conflict. Bring the branch up to date with
  `git merge origin/develop` first, and resolve conflicts on the branch.
- **`develop` → `main`: squash.** The **PR title becomes the one commit semantic-release reads**.
  It must be a valid Conventional Commit carrying the **highest** bump in the branch (`feat` if
  any feature, `!` if anything is breaking). The body keeps the commit messages, so
  breaking-change footers still count. Set the squash body to the messages of the commits that
  are **new** in this release. GitHub also lists commits already released by an earlier squash,
  and their footers and `#N` references would land in the notes a second time.
- **Back-merge `main` into `develop` after every squash** (PR, merge commit,
  `chore: back-merge main into develop`). Otherwise the next release PR replays old work and
  conflicts.
- **Hotfix:** branch from `main`, squash into `main`, then back-merge into `develop`.
- `scripts/github-branch-rules.sh --apply --create-develop` enforces this on GitHub with rulesets.
  Rulesets need **GitHub Pro or Team for a private repository** (or a public repository). On a
  free private repository the script fails with HTTP 403 after updating the merge settings and
  before creating `develop`. In that case, create `develop` by hand, record in "Project notes"
  that the rules aren't enforced, and follow them yourself.

## Project notes

<!-- Gotchas specific to this service: library quirks and workarounds, invariants, operational
     notes. Add to this as you learn them. -->
