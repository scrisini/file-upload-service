#!/usr/bin/env bash
#
# Local development helper for file-upload-service.
#
#   scripts/dev.sh check            verify prerequisites
#   scripts/dev.sh infra            start dependencies (docker-compose.yml) and wait until healthy
#   scripts/dev.sh run [args...]    check, start dependencies, then run the app in the foreground
#   scripts/dev.sh docker [args...] the same, with the app in Docker: builds its image (as production
#                                   does) and runs it with a read-only filesystem
#   scripts/dev.sh tracing          start Jaeger, to see traces at http://localhost:16686 (README "Tracing")
#   scripts/dev.sh idea [--force]   write the IntelliJ IDEA run configuration for debugging against the local
#                                   emulators and simulators (README "Debugging from the IDE")
#   scripts/dev.sh status           show dependency and app health
#   scripts/dev.sh test             run the test suite
#   scripts/dev.sh stop             stop dependencies, keeping their data
#   scripts/dev.sh reset [--yes]    stop dependencies and delete all of their data
#   scripts/dev.sh hooks            install the git hooks in .githooks (commit messages, plaintext secrets)
#
# Extra arguments to `run` and `docker` are passed to Spring Boot, e.g.
#   scripts/dev.sh run --logging.level.com.crisinis.fileuploadservice=DEBUG
#
# The app listens on SERVER_PORT (default 8080). If that port is taken, `run` and `docker` ask for
# another one, and remember it for `status` and scripts/try-upload.sh.
#
# Environment variables are read by the app as usual (see README "Configuration"). With
# FILE_UPLOAD_SERVICE_PROXY_HOST set (plus FILE_UPLOAD_SERVICE_PROXY_TYPE and _PORT), `check` tests the proxy and
# reaches external hosts through it, as the app will. If config/secrets/local.enc.env exists, `run`
# decrypts it with SOPS into the app's environment.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

DEFAULT_APP_PORT=8080
# the port chosen by the last `run` or `docker`, for `status` and try-upload.sh (build/ isn't committed)
PORT_FILE="build/dev-port"
APP_PORT="${SERVER_PORT:-$DEFAULT_APP_PORT}"
APP_IMAGE="file-upload-service:dev"
APP_CONTAINER="file-upload-service-app"
MIN_JAVA=17
PROXY_TYPE="${FILE_UPLOAD_SERVICE_PROXY_TYPE:-HTTP}"
PROXY_HOST="${FILE_UPLOAD_SERVICE_PROXY_HOST:-}"
PROXY_PORT="${FILE_UPLOAD_SERVICE_PROXY_PORT:-8888}"
LOCAL_SECRETS="config/secrets/local.enc.env"

# External endpoints the service calls, as "Name|URL", checked by `check` (advisory only).
EXTERNAL_CHECKS=(
    # "Payments API|https://api.example.com/health"
)

if [ -t 1 ]; then
    RED=$'\033[31m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; BOLD=$'\033[1m'; RESET=$'\033[0m'
else
    RED=""; GREEN=""; YELLOW=""; BOLD=""; RESET=""
fi

ok()   { printf '  %s✔%s %s\n' "$GREEN" "$RESET" "$*"; }
warn() { printf '  %s!%s %s\n' "$YELLOW" "$RESET" "$*"; }
fail() { printf '  %s✘%s %s\n' "$RED" "$RESET" "$*"; }
die()  { printf '%serror:%s %s\n' "$RED" "$RESET" "$*" >&2; exit 1; }
step() { printf '%s==>%s %s\n' "$BOLD" "$RESET" "$*"; }

has_compose() { [ -f docker-compose.yml ] || [ -f compose.yml ]; }

listening() { lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1; }

java_major() {
    local java_cmd="java"
    [ -n "${JAVA_HOME:-}" ] && java_cmd="$JAVA_HOME/bin/java"
    command -v "$java_cmd" >/dev/null 2>&1 || return 1
    # first line looks like: openjdk version "21.0.5" ... or java version "1.8.0_402"
    "$java_cmd" -version 2>&1 | awk -F'"' '/version/ { split($2, v, "."); print (v[1] == "1" ? v[2] : v[1]); exit }'
}

# HTTP status of a GET to an external URL, through the outbound proxy when one is configured
external_status() {
    if [ -z "$PROXY_HOST" ]; then
        curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$1" || true
    elif [ "$PROXY_TYPE" = "SOCKS" ]; then
        curl -s -o /dev/null -w '%{http_code}' --max-time 8 --socks5-hostname "$PROXY_HOST:$PROXY_PORT" "$1" || true
    else
        curl -s -o /dev/null -w '%{http_code}' --max-time 8 --proxy "http://$PROXY_HOST:$PROXY_PORT" "$1" || true
    fi
}

cmd_check() {
    step "Checking prerequisites"
    local missing=0

    local major
    if major="$(java_major)" && [ -n "$major" ]; then
        if [ "$major" -ge "$MIN_JAVA" ]; then
            ok "Java $major (runs Gradle; the build's JDK 25 toolchain is downloaded if absent)"
        else
            fail "Java $major found; Gradle needs Java $MIN_JAVA or newer"
            missing=1
        fi
    else
        fail "Java not found; install a JDK (e.g. https://adoptium.net) or set JAVA_HOME"
        missing=1
    fi

    if command -v curl >/dev/null 2>&1; then ok "curl"; else fail "curl not found"; missing=1; fi

    if has_compose; then
        if ! command -v docker >/dev/null 2>&1; then
            fail "Docker not found; install Docker Desktop (or Docker Engine) to run the dependencies"
            missing=1
        elif ! docker info >/dev/null 2>&1; then
            fail "Docker is installed but not running; start Docker Desktop"
            missing=1
        elif ! docker compose version >/dev/null 2>&1; then
            fail "Docker Compose v2 not found ('docker compose'); update Docker"
            missing=1
        else
            ok "Docker $(docker version --format '{{.Server.Version}}' 2>/dev/null), Compose $(docker compose version --short 2>/dev/null)"
        fi
    fi

    if listening "$APP_PORT"; then
        warn "port $APP_PORT is in use; 'run' and 'docker' will ask for another (or set SERVER_PORT)"
    else
        ok "port $APP_PORT available for the app"
    fi

    if [ -n "$PROXY_HOST" ]; then
        local code
        code="$(external_status https://www.google.com/generate_204)"
        if [ "$code" != "000" ]; then
            ok "$PROXY_TYPE proxy $PROXY_HOST:$PROXY_PORT relays to the internet"
        else
            fail "$PROXY_TYPE proxy $PROXY_HOST:$PROXY_PORT is not reachable or refuses connections"
            missing=1
        fi
    fi

    local entry name url code
    for entry in ${EXTERNAL_CHECKS[@]+"${EXTERNAL_CHECKS[@]}"}; do
        name="${entry%%|*}"; url="${entry#*|}"
        code="$(external_status "$url")"
        case "$code" in
            2??|3??) ok "$name reachable" ;;
            *) warn "$name not reachable (HTTP ${code:-none})" ;;
        esac
    done

    if [ -f "$LOCAL_SECRETS" ]; then
        if ! command -v sops >/dev/null 2>&1; then
            fail "$LOCAL_SECRETS exists but sops is not installed ('brew install sops age')"
            missing=1
        elif sops -d "$LOCAL_SECRETS" >/dev/null 2>&1; then
            ok "SOPS decrypts $LOCAL_SECRETS (run passes it to the app's environment)"
        else
            fail "sops cannot decrypt $LOCAL_SECRETS: is your age key in ~/.config/sops/age/keys.txt (or SOPS_AGE_KEY_FILE) and listed in .sops.yaml?"
            missing=1
        fi
    fi

    if [ "$(git config --get core.hooksPath || true)" = ".githooks" ]; then
        ok "git hooks installed (commit messages, plaintext secrets)"
    else
        warn "git hooks not installed; run 'scripts/dev.sh hooks'"
    fi

    if [ "$missing" -ne 0 ]; then
        die "missing prerequisites, see above"
    fi
}

cmd_infra() {
    if ! has_compose; then
        step "No docker-compose.yml; no dependencies to start"
        return
    fi
    step "Starting dependencies"
    docker compose up -d --wait
    ok "dependencies healthy"
    create_local_table
}

# Local AWS emulators (docker-compose.yml). Each can be overridden from the environment.
LOCAL_AWS_REGION="us-east-1"
LOCAL_S3="http://localhost:9099"
LOCAL_SQS="http://localhost:9324"
LOCAL_DYNAMODB="http://localhost:8000"
LOCAL_QUEUE_URL="$LOCAL_SQS/000000000000/file-upload-scan-events"
LOCAL_TABLE_DEFINITION="deploy/aws/dynamodb-table.json"

# A call to DynamoDB Local's JSON API: it needs a SigV4-shaped Authorization header but doesn't check it
dynamodb_local() {
    curl -s --max-time 5 -X POST "$LOCAL_DYNAMODB" \
        -H "Content-Type: application/x-amz-json-1.0" \
        -H "X-Amz-Target: DynamoDB_20120810.$1" \
        -H "Authorization: AWS4-HMAC-SHA256 Credential=local/20260101/$LOCAL_AWS_REGION/dynamodb/aws4_request, SignedHeaders=host, Signature=local" \
        --data "$2"
}

create_local_table() {
    local response
    response="$(dynamodb_local CreateTable "@$LOCAL_TABLE_DEFINITION")"
    case "$response" in
        *TableDescription*) ok "DynamoDB table created from $LOCAL_TABLE_DEFINITION" ;;
        *ResourceInUseException*) ok "DynamoDB table exists" ;;
        *) die "could not create the DynamoDB table: $response" ;;
    esac
}

# Points the app at the emulators, with the GuardDuty simulator standing in for GuardDuty
use_local_aws() {
    export FILE_UPLOAD_SERVICE_AWS_REGION="${FILE_UPLOAD_SERVICE_AWS_REGION:-$LOCAL_AWS_REGION}"
    export FILE_UPLOAD_SERVICE_S3_ENDPOINT="${FILE_UPLOAD_SERVICE_S3_ENDPOINT:-$LOCAL_S3}"
    export FILE_UPLOAD_SERVICE_SQS_ENDPOINT="${FILE_UPLOAD_SERVICE_SQS_ENDPOINT:-$LOCAL_SQS}"
    export FILE_UPLOAD_SERVICE_DYNAMODB_ENDPOINT="${FILE_UPLOAD_SERVICE_DYNAMODB_ENDPOINT:-$LOCAL_DYNAMODB}"
    export FILE_UPLOAD_SERVICE_SCAN_QUEUE_URL="${FILE_UPLOAD_SERVICE_SCAN_QUEUE_URL:-$LOCAL_QUEUE_URL}"
    export FILE_UPLOAD_SERVICE_SCAN_SIMULATOR="${FILE_UPLOAD_SERVICE_SCAN_SIMULATOR:-true}"
    # policy extraction against the Bedrock simulator (no AWS); a real model id with the simulator off
    # would call Bedrock with your own credentials
    export FILE_UPLOAD_SERVICE_EXTRACTION_MODEL_ID="${FILE_UPLOAD_SERVICE_EXTRACTION_MODEL_ID:-simulated-insurance-policy-model}"
    export FILE_UPLOAD_SERVICE_EXTRACTION_SIMULATOR="${FILE_UPLOAD_SERVICE_EXTRACTION_SIMULATOR:-true}"
    # the emulators accept any credentials; these keep the SDK from looking for real ones
    export AWS_ACCESS_KEY_ID="${LOCAL_AWS_ACCESS_KEY_ID:-local}"
    export AWS_SECRET_ACCESS_KEY="${LOCAL_AWS_SECRET_ACCESS_KEY:-local}"
    unset AWS_SESSION_TOKEN AWS_PROFILE
    ok "AWS services: local emulators; GuardDuty: simulated (EICAR test string = infected); Bedrock: simulated"
}

# Jaeger (docker-compose.yml profile "tracing"): receives the app's traces over OTLP, shows them in a UI
cmd_tracing() {
    has_compose || die "docker-compose.yml is missing"
    step "Starting Jaeger"
    docker compose --profile tracing up -d --wait jaeger
    ok "Jaeger UI http://localhost:16686; the app exports traces to it when started with 'run'"
}

# Traces: every request is sampled on a laptop, and exported when Jaeger is listening for them
use_local_tracing() {
    export FILE_UPLOAD_SERVICE_TRACING_SAMPLING="${FILE_UPLOAD_SERVICE_TRACING_SAMPLING:-1.0}"
    if [ -z "${FILE_UPLOAD_SERVICE_TRACING_EXPORT:-}" ]; then
        if curl -s -o /dev/null --max-time 2 -X POST -H 'Content-Type: application/json' -d '{}' \
            http://localhost:4318/v1/traces; then
            export FILE_UPLOAD_SERVICE_TRACING_EXPORT=true
            ok "Traces: exported to Jaeger, http://localhost:16686"
        else
            ok "Traces: not exported (start Jaeger with 'scripts/dev.sh tracing'); trace ids are still in the logs"
        fi
    fi
}

# The settings `run` gives the app, as an IntelliJ IDEA "Application" run configuration, so a
# developer can debug from the IDE with the same emulators and simulators. The file is in .idea/
# (git-ignored): each developer creates their own.
IDEA_CONFIG=".idea/runConfigurations/file_upload_service_local.xml"
IDEA_MODULE="file-upload-service.main"

cmd_idea() {
    local force=false
    case "${1:-}" in
        "") ;;
        --force) force=true ;;
        *) die "usage: scripts/dev.sh idea [--force]" ;;
    esac
    if [ -f "$IDEA_CONFIG" ] && [ "$force" = false ]; then
        die "$IDEA_CONFIG exists; 'scripts/dev.sh idea --force' replaces it"
    fi
    local names=(FILE_UPLOAD_SERVICE_AWS_REGION FILE_UPLOAD_SERVICE_S3_ENDPOINT FILE_UPLOAD_SERVICE_SQS_ENDPOINT
        FILE_UPLOAD_SERVICE_DYNAMODB_ENDPOINT FILE_UPLOAD_SERVICE_SCAN_QUEUE_URL FILE_UPLOAD_SERVICE_SCAN_SIMULATOR
        FILE_UPLOAD_SERVICE_EXTRACTION_MODEL_ID FILE_UPLOAD_SERVICE_EXTRACTION_SIMULATOR
        FILE_UPLOAD_SERVICE_TRACING_SAMPLING FILE_UPLOAD_SERVICE_TRACING_EXPORT AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY)
    local name
    # the defaults `run` uses, not whatever this shell happens to have set
    for name in "${names[@]}"; do unset "$name"; done
    use_local_aws
    export FILE_UPLOAD_SERVICE_TRACING_SAMPLING=1.0
    export FILE_UPLOAD_SERVICE_TRACING_EXPORT=true
    mkdir -p "$(dirname "$IDEA_CONFIG")"
    {
        echo '<component name="ProjectRunConfigurationManager">'
        echo '  <configuration default="false" name="file-upload-service (local)" type="Application" factoryName="Application">'
        echo '    <option name="MAIN_CLASS_NAME" value="com.crisinis.fileuploadservice.FileUploadServiceApplication" />'
        echo "    <module name=\"$IDEA_MODULE\" />"
        echo '    <option name="PROGRAM_PARAMETERS" value="--file-upload-service.instance=ide" />'
        echo '    <envs>'
        for name in "${names[@]}"; do
            echo "      <env name=\"$name\" value=\"${!name}\" />"
        done
        echo '    </envs>'
        echo '    <method v="2" />'
        echo '  </configuration>'
        echo '</component>'
    } > "$IDEA_CONFIG"
    ok "wrote $IDEA_CONFIG: run 'file-upload-service (local)' from IDEA's run dropdown (reload the project if it isn't listed)"
    if ! grep -qs "$IDEA_MODULE" .idea/modules.xml; then
        warn "module $IDEA_MODULE isn't in .idea/modules.xml: open the project in IDEA as a Gradle project first, then run this again with --force"
    fi
    ok "start the dependencies first: scripts/dev.sh infra (and 'scripts/dev.sh tracing' for Jaeger)"
}

# A port number a user may pick: 1024-65535
valid_port() {
    case "$1" in
        ''|*[!0-9]*) return 1 ;;
    esac
    [ "$1" -ge 1024 ] && [ "$1" -le 65535 ]
}

# Settles APP_PORT on a free port, asking for another while it's taken, then exports it as
# SERVER_PORT (the app and docker-compose.yml read it) and remembers it in $PORT_FILE.
choose_app_port() {
    local answer
    while listening "$APP_PORT"; do
        if [ ! -t 0 ]; then
            die "port $APP_PORT is in use and there's no terminal to ask for another; set SERVER_PORT to a free port"
        fi
        warn "port $APP_PORT is in use: $(lsof -nP -iTCP:"$APP_PORT" -sTCP:LISTEN 2>/dev/null | awk 'NR == 2 { print $1 " (pid " $2 ")" }')"
        printf '  Port for file-upload-service instead (1024-65535, Enter for %s): ' "$((APP_PORT + 1))"
        read -r answer || die "no port given"
        answer="${answer:-$((APP_PORT + 1))}"
        if valid_port "$answer"; then
            APP_PORT="$answer"
        else
            warn "'$answer' is not a port between 1024 and 65535"
        fi
    done
    export SERVER_PORT="$APP_PORT"
    mkdir -p "$(dirname "$PORT_FILE")"
    printf '%s\n' "$APP_PORT" > "$PORT_FILE"
    ok "the app will listen on port $APP_PORT"
}

# The port the app was last started on, unless SERVER_PORT says otherwise
remembered_port() {
    if [ -z "${SERVER_PORT:-}" ] && [ -f "$PORT_FILE" ]; then
        APP_PORT="$(cat "$PORT_FILE")"
    fi
}

cmd_run() {
    cmd_check
    cmd_infra
    choose_app_port
    use_local_aws
    use_local_tracing
    step "Starting file-upload-service on http://localhost:$APP_PORT (Ctrl-C to stop)"
    local app=(./gradlew bootRun --console=plain)
    if [ "$#" -gt 0 ]; then
        app+=("--args=$*")
    fi
    if [ -f "$LOCAL_SECRETS" ]; then
        ok "secrets from $LOCAL_SECRETS (SOPS), in the app's environment only"
        # sops runs the command through a shell; quote each word so arguments survive intact
        exec sops exec-env "$LOCAL_SECRETS" "$(printf '%q ' "${app[@]}")"
    fi
    exec "${app[@]}"
}

cmd_docker() {
    cmd_check
    has_compose || die "docker-compose.yml is missing"
    command -v docker >/dev/null 2>&1 || die "Docker is required for 'docker'"
    cmd_infra
    choose_app_port
    step "Building the image $APP_IMAGE (Cloud Native Buildpacks, as in production; the first build takes a few minutes)"
    ./gradlew bootBuildImage --imageName="$APP_IMAGE" --console=plain -q
    ok "image $APP_IMAGE built"
    step "Starting file-upload-service in Docker on http://localhost:$APP_PORT (Ctrl-C to stop)"
    ok "AWS services: local emulators; GuardDuty: simulated (EICAR test string = infected); Bedrock: simulated"
    # in the foreground, like `run`; removed when it stops. Arguments go to Spring Boot.
    exec docker compose --profile app run --rm --service-ports --name "$APP_CONTAINER" app "$@"
}

cmd_status() {
    remembered_port
    step "Status"
    if has_compose; then
        docker compose ps --format '  {{.Service}}: {{.Status}}' 2>/dev/null || fail "docker compose not available"
    fi
    local health
    if health="$(curl -fsS --max-time 3 "http://localhost:$APP_PORT/actuator/health" 2>/dev/null)"; then
        # the overall status is the last top-level key: ...,"status":"UP"}
        ok "app health: $(printf '%s' "$health" | sed -E 's/.*"status":"([A-Z_]+)"\}$/\1/')"
        local scans
        if scans="$(curl -fsS --max-time 3 "http://localhost:$APP_PORT/api/v1/scans" 2>/dev/null)"; then
            ok "scans: $scans"
        fi
    else
        fail "app not responding on http://localhost:$APP_PORT"
    fi
}

# The app's container from `docker`: `docker compose stop` passes over containers started with
# `docker compose run`, so it's stopped by name (and removes itself)
stop_app_container() {
    if docker ps -q --filter "name=^${APP_CONTAINER}\$" | grep -q .; then
        # SIGTERM, and time to drain (README "Graceful shutdown"); Docker's default is 10 s
        docker stop --time 45 "$APP_CONTAINER" >/dev/null
        ok "stopped the app container $APP_CONTAINER"
    fi
}

cmd_stop() {
    has_compose || { step "No docker-compose.yml; nothing to stop"; return; }
    step "Stopping the app container, if any, and dependencies (data is kept)"
    stop_app_container
    docker compose --profile app --profile tracing stop
}

cmd_reset() {
    has_compose || { step "No docker-compose.yml; nothing to reset"; return; }
    if [ "${1:-}" != "--yes" ]; then
        printf 'This deletes all local dependency data (Docker volumes). Continue? [y/N] '
        local answer
        read -r answer
        case "$answer" in
            y|Y|yes|YES) ;;
            *) die "aborted" ;;
        esac
    fi
    step "Removing dependency containers and data volumes"
    stop_app_container
    docker compose --profile app down --volumes
}

cmd_hooks() {
    step "Installing git hooks from .githooks"
    chmod +x .githooks/*
    git config core.hooksPath .githooks
    ok "commit messages are now checked against .commitlintrc.json on every commit"
    ok "staged files are checked for plaintext secrets (scripts/check-secrets.sh)"
}

cmd_test() {
    step "Running tests"
    exec ./gradlew test
}

usage() {
    sed -n '3,25p' "$0" | sed 's/^# \{0,1\}//'
}

command="${1:-help}"
[ "$#" -gt 0 ] && shift
case "$command" in
    check)  cmd_check ;;
    infra)  cmd_infra ;;
    run)    cmd_run "$@" ;;
    docker) cmd_docker "$@" ;;
    tracing) cmd_tracing ;;
    idea)   cmd_idea "$@" ;;
    status) cmd_status ;;
    stop)   cmd_stop ;;
    reset)  cmd_reset "$@" ;;
    hooks)  cmd_hooks ;;
    test)   cmd_test ;;
    help|-h|--help) usage ;;
    *) usage; die "unknown command '$command'" ;;
esac
