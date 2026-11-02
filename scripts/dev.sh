#!/usr/bin/env bash
#
# Local development helper for file-upload-service.
#
#   scripts/dev.sh check            verify prerequisites
#   scripts/dev.sh infra            start dependencies (docker-compose.yml) and wait until healthy
#   scripts/dev.sh run [args...]    check, start dependencies, then run the app in the foreground
#   scripts/dev.sh status           show dependency and app health
#   scripts/dev.sh test             run the test suite
#   scripts/dev.sh stop             stop dependencies, keeping their data
#   scripts/dev.sh reset [--yes]    stop dependencies and delete all of their data
#   scripts/dev.sh hooks            install the git hooks in .githooks (commit messages, plaintext secrets)
#
# Extra arguments to `run` are passed to Spring Boot, e.g.
#   scripts/dev.sh run --logging.level.com.crisinis.fileuploadservice=DEBUG
#
# Environment variables are read by the app as usual (see README "Configuration"). With
# FILE_UPLOAD_SERVICE_PROXY_HOST set (plus FILE_UPLOAD_SERVICE_PROXY_TYPE and _PORT), `check` tests the proxy and
# reaches external hosts through it, as the app will. If config/secrets/local.enc.env exists, `run`
# decrypts it with SOPS into the app's environment.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

APP_PORT="${SERVER_PORT:-8080}"
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
        fail "port $APP_PORT is in use; stop that process or set SERVER_PORT (is the app already running?)"
        missing=1
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
    # the emulators accept any credentials; these keep the SDK from looking for real ones
    export AWS_ACCESS_KEY_ID="${LOCAL_AWS_ACCESS_KEY_ID:-local}"
    export AWS_SECRET_ACCESS_KEY="${LOCAL_AWS_SECRET_ACCESS_KEY:-local}"
    unset AWS_SESSION_TOKEN AWS_PROFILE
    ok "AWS services: local emulators; GuardDuty: simulated (EICAR test string = infected)"
}

cmd_run() {
    cmd_check
    cmd_infra
    use_local_aws
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

cmd_status() {
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

cmd_stop() {
    has_compose || { step "No docker-compose.yml; nothing to stop"; return; }
    step "Stopping dependencies (data is kept)"
    docker compose stop
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
    docker compose down --volumes
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
    sed -n '3,20p' "$0" | sed 's/^# \{0,1\}//'
}

command="${1:-help}"
[ "$#" -gt 0 ] && shift
case "$command" in
    check)  cmd_check ;;
    infra)  cmd_infra ;;
    run)    cmd_run "$@" ;;
    status) cmd_status ;;
    stop)   cmd_stop ;;
    reset)  cmd_reset "$@" ;;
    hooks)  cmd_hooks ;;
    test)   cmd_test ;;
    help|-h|--help) usage ;;
    *) usage; die "unknown command '$command'" ;;
esac
