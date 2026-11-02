#!/usr/bin/env bash
#
# Uploads a file to a running file-upload-service in either style, then follows it until its scan
# is done, printing each status change. For demos and for checking a deployment end to end.
#
#   scripts/try-upload.sh presigned <file> [content-type]
#   scripts/try-upload.sh stream    <file> [content-type]
#
# The content type defaults to what `file --mime-type` says. FILE_UPLOAD_SERVICE_URL picks the
# instance (default http://localhost:8080). Needs curl; jq makes the output easier to read.

set -euo pipefail

BASE="${FILE_UPLOAD_SERVICE_URL:-http://localhost:${SERVER_PORT:-8080}}"

die() { printf 'error: %s\n' "$*" >&2; exit 1; }
show() { if command -v jq >/dev/null 2>&1; then jq .; else cat; echo; fi; }
field() {
    # the value of a top-level or nested JSON string field, without jq: field <json> <name>
    printf '%s' "$1" | sed -nE "s/.*\"$2\":\"([^\"]*)\".*/\\1/p"
}

[ "$#" -ge 2 ] || die "usage: $0 presigned|stream <file> [content-type]"
style="$1" file="$2"
[ -f "$file" ] || die "no such file: $file"
type="${3:-$(file --brief --mime-type "$file")}"
size="$(wc -c < "$file" | tr -d ' ')"
name="$(basename "$file")"

case "$style" in
    presigned)
        printf '==> POST /api/v1/uploads (%s, %s bytes)\n' "$type" "$size"
        response="$(curl -sS -X POST "$BASE/api/v1/uploads" -H 'Content-Type: application/json' \
            -d "{\"fileName\":\"$name\",\"contentType\":\"$type\",\"size\":$size}")"
        printf '%s' "$response" | show
        url="$(field "$response" url)"
        [ -n "$url" ] || die "no upload URL in the response"
        id="$(field "$response" id)"
        printf '==> PUT the bytes straight to S3 (the service never sees them)\n'
        curl -sS -o /dev/null -w '    S3 answered HTTP %{http_code}\n' -X PUT --upload-file "$file" \
            -H "Content-Type: $type" -H 'If-None-Match: *' "$url"
        ;;
    stream)
        printf '==> POST /api/v1/files (%s, %s bytes, through the service)\n' "$type" "$size"
        response="$(curl -sS -X POST "$BASE/api/v1/files?fileName=$name" -H "Content-Type: $type" \
            --data-binary "@$file")"
        printf '%s' "$response" | show
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
