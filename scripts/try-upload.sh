#!/usr/bin/env bash
#
# Uploads a file to a running file-upload-service in either style, then follows it until its scan
# is done, printing each status change. For demos and for checking a deployment end to end.
#
#   scripts/try-upload.sh presigned <file> [content-type]
#   scripts/try-upload.sh stream    <file> [content-type]
#
# The content type defaults to what `file --mime-type` says. FILE_UPLOAD_SERVICE_URL picks the
# instance; by default it's the port `scripts/dev.sh run` or `docker` last chose, else 8080. Needs
# curl; jq makes the output easier to read.
#
# Each upload carries an Idempotency-Key, printed so it can be replayed: run again with
# IDEMPOTENCY_KEY=<key> to retry the same upload (the answer says Idempotent-Replayed: true), or
# IDEMPOTENCY_KEY= (empty) to send none.

set -euo pipefail

PORT_FILE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/build/dev-port"
default_port="${SERVER_PORT:-$( [ -f "$PORT_FILE" ] && cat "$PORT_FILE" || echo 8080)}"
BASE="${FILE_UPLOAD_SERVICE_URL:-http://localhost:$default_port}"

die() { printf 'error: %s\n' "$*" >&2; exit 1; }
show() { if command -v jq >/dev/null 2>&1; then jq .; else cat; echo; fi; }
field() {
    # the value of a top-level or nested JSON string field, without jq: field <json> <name>
    printf '%s' "$1" | sed -nE "s/.*\"$2\":\"([^\"]*)\".*/\\1/p"
}

[ "$#" -ge 2 ] || die "usage: $0 presigned|stream <file> [content-type]"
headers="$(mktemp)"
trap 'rm -f "$headers"' EXIT
replayed() {
    if grep -qi '^Idempotent-Replayed: true' "$headers"; then
        printf '==> Idempotent-Replayed: true (the file an earlier attempt with this key created)\n'
    fi
}
style="$1" file="$2"
[ -f "$file" ] || die "no such file: $file"
type="${3:-$(file --brief --mime-type "$file")}"
size="$(wc -c < "$file" | tr -d ' ')"
name="$(basename "$file")"
if [ -z "${IDEMPOTENCY_KEY+set}" ]; then
    IDEMPOTENCY_KEY="$(uuidgen | tr '[:upper:]' '[:lower:]')"
fi
# never empty, so it expands safely under `set -u` in bash 3.2
key_header=(-H "Accept: application/json, application/problem+json")
if [ -n "$IDEMPOTENCY_KEY" ]; then
    key_header=(-H "Idempotency-Key: $IDEMPOTENCY_KEY")
    printf '==> Idempotency-Key: %s (IDEMPOTENCY_KEY=%s replays this upload)\n' "$IDEMPOTENCY_KEY" "$IDEMPOTENCY_KEY"
fi

case "$style" in
    presigned)
        printf '==> POST /api/v1/uploads (%s, %s bytes)\n' "$type" "$size"
        response="$(curl -sS -D "$headers" -X POST "$BASE/api/v1/uploads" -H 'Content-Type: application/json' \
            "${key_header[@]}" \
            -d "{\"fileName\":\"$name\",\"contentType\":\"$type\",\"size\":$size}")"
        printf '%s' "$response" | show
        replayed
        id="$(field "$response" id)"
        url="$(field "$response" url)"
        if [ -n "$url" ]; then
            printf '==> PUT the bytes straight to S3 (the service never sees them)\n'
            curl -sS -o /dev/null -w '    S3 answered HTTP %{http_code}\n' -X PUT --upload-file "$file" \
                -H "Content-Type: $type" -H 'If-None-Match: *' "$url"
        else
            printf '==> no upload URL: the bytes already landed with an earlier attempt\n'
        fi
        ;;
    stream)
        printf '==> POST /api/v1/files (%s, %s bytes, through the service)\n' "$type" "$size"
        response="$(curl -sS -D "$headers" -X POST "$BASE/api/v1/files?fileName=$name" -H "Content-Type: $type" \
            "${key_header[@]}" --data-binary "@$file")"
        printf '%s' "$response" | show
        replayed
        id="$(field "$response" id)"
        ;;
    *) die "style must be presigned or stream, not $style" ;;
esac
[ -n "$id" ] || die "the upload was refused"

printf '==> GET /api/v1/files/%s until the scan is done\n' "$id"
last=""
for _ in $(seq 1 60); do
    current="$(curl -sS "$BASE/api/v1/files/$id")"
    status="$(field "$current" status)"
    if [ "$status" != "$last" ]; then
        printf '    %s  %s\n' "$(date +%H:%M:%S)" "$status"
        last="$status"
    fi
    case "$status" in
        AVAILABLE|INFECTED|REJECTED|NOT_SCANNED) printf '%s' "$current" | show; exit 0 ;;
    esac
    sleep 1
done
die "still $last after 60 s"
