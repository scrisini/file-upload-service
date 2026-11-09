#!/usr/bin/env bash
#
# Checks that @Observed and tracing are collecting, on a running file-upload-service:
#
#   1. streams a tiny PNG with a W3C `traceparent` header whose trace id this script chose, so the
#      trace can be looked up by id (the service continues an incoming trace, and honours its
#      "sampled" flag whatever the sampling probability is);
#   2. reads the three @Observed timers from /actuator/metrics and /actuator/prometheus;
#   3. asks Jaeger for that trace and checks for the spans of the flow.
#
#   scripts/check-tracing.sh
#
# FILE_UPLOAD_SERVICE_URL picks the instance (default: the port `scripts/dev.sh run` last chose, else
# 8080); JAEGER_URL picks Jaeger (default http://localhost:16686). Start Jaeger first with
# `scripts/dev.sh tracing`, and the app after it with `scripts/dev.sh run` (or from the IDE with
# FILE_UPLOAD_SERVICE_TRACING_EXPORT=true). Without Jaeger, steps 1 and 2 still run. Needs curl.
# Exit status: 0 all checks passed, 1 one failed, 2 the trace check was skipped (no Jaeger).

set -euo pipefail

PORT_FILE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/build/dev-port"
default_port="${SERVER_PORT:-$( [ -f "$PORT_FILE" ] && cat "$PORT_FILE" || echo 8080)}"
BASE="${FILE_UPLOAD_SERVICE_URL:-http://localhost:$default_port}"
JAEGER="${JAEGER_URL:-http://localhost:16686}"

failed=0
pass() { printf '  PASS  %s\n' "$*"; }
fail() { printf '  FAIL  %s\n' "$*"; failed=1; }
# hex <bytes>: that many random bytes as lower-case hex
hex() { od -An -N"$1" -tx1 /dev/urandom | tr -d ' \n'; }

curl -sf --max-time 5 "$BASE/actuator/health/liveness" >/dev/null || { echo "error: no service at $BASE" >&2; exit 1; }

trace_id="$(hex 16)"
span_id="$(hex 8)"
key="$(hex 16)"

echo "==> 1. Streamed upload with trace id $trace_id"
file="$(mktemp)"
trap 'rm -f "$file"' EXIT
printf '\x89PNG\r\n\x1a\n\0\0\0\rIHDR0123456789abcdef' > "$file"
status="$(curl -sS -o /dev/null -D - -X POST "$BASE/api/v1/files?fileName=trace-check.png" \
    -H 'Content-Type: image/png' -H "Idempotency-Key: $key" \
    -H "traceparent: 00-$trace_id-$span_id-01" --data-binary "@$file" | head -1 | tr -d '\r')"
case "$status" in
    *" 202"*|*" 201"*|*" 200"*) pass "upload accepted ($status)" ;;
    *) fail "upload not accepted: $status"; exit 1 ;;
esac

echo "==> 2. Timers from @Observed"
for name in file-upload-service.upload.stream file-upload-service.file.create file-upload-service.quarantine.store; do
    count="$(curl -sf "$BASE/actuator/metrics/$name" | grep -o '"statistic":"COUNT","value":[0-9.]*' | head -1 | sed 's/.*://')" || count=""
    if [ -n "$count" ] && [ "${count%%.*}" -ge 1 ]; then
        pass "$name: count $count"
    else
        fail "$name: no count (is management.observations.annotations.enabled on?)"
    fi
done
prom="$(curl -sf "$BASE/actuator/prometheus" || true)"
if printf '%s' "$prom" | grep -q '^file_upload_service_upload_stream_seconds_count'; then
    pass "/actuator/prometheus has file_upload_service_upload_stream_seconds_count"
else
    fail "/actuator/prometheus has no file_upload_service_upload_stream series"
fi

echo "==> 3. The trace in Jaeger ($JAEGER)"
if ! curl -sf --max-time 3 "$JAEGER/api/v3/services" >/dev/null; then
    echo "  SKIP  Jaeger isn't reachable: scripts/dev.sh tracing, then restart the app"
    [ "$failed" -eq 0 ] && exit 2 || exit 1
fi
trace=""
for _ in 1 2 3 4 5 6 7 8 9 10; do    # spans are exported in batches, a few seconds after the call
    trace="$(curl -sf "$JAEGER/api/v3/traces/$trace_id" || true)"
    printf '%s' "$trace" | grep -q 'upload-service#stream' && break
    sleep 1
done
for span in 'upload-service#stream' 'file-repository#createIfAbsent' 'file-storage#store'; do
    if printf '%s' "$trace" | grep -q "$span"; then
        pass "span $span"
    else
        fail "span $span not found in trace $trace_id (is FILE_UPLOAD_SERVICE_TRACING_EXPORT=true?)"
    fi
done
echo "    open: $JAEGER/trace/$trace_id"

[ "$failed" -eq 0 ] && echo "==> all checks passed" || echo "==> some checks failed"
exit "$failed"
