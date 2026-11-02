#!/usr/bin/env bash
#
# Puts S3- and GuardDuty-shaped events on the LOCAL scan queue (ElasticMQ from docker-compose.yml),
# to test what the GuardDuty simulator never does: unscannable files, duplicate and reordered
# events, events for unknown objects. Run the app with the simulator off so it doesn't send its own:
#
#   FILE_UPLOAD_SERVICE_SCAN_SIMULATOR=false scripts/dev.sh run
#
#   scripts/send-scan-event.sh created <file-id>                     S3 "Object Created"
#   scripts/send-scan-event.sh result  <file-id> <status> [reason]   GuardDuty "Object Scan Result"
#        status: NO_THREATS_FOUND | THREATS_FOUND | UNSUPPORTED | ACCESS_DENIED | FAILED
#        reason: the threat's name for THREATS_FOUND, else a status reason (PASSWORD_PROTECTED, ...)
#   scripts/send-scan-event.sh tag     <file-id> <status>            tag the object as GuardDuty does,
#                                                                    without an event (lost-event tests)
#   scripts/send-scan-event.sh raw     '<message body>'              any message, e.g. not JSON
#
# The object's ETag and size are read from the local S3 when it's there. Local queue only.

set -euo pipefail

S3="${LOCAL_S3:-http://localhost:9099}"
QUEUE_URL="${LOCAL_QUEUE_URL:-http://localhost:9324/000000000000/file-upload-scan-events}"
BUCKET="${FILE_UPLOAD_SERVICE_QUARANTINE_BUCKET:-file-upload-quarantine}"
PREFIX="${FILE_UPLOAD_SERVICE_QUARANTINE_PREFIX:-uploads/}"

die() { printf 'error: %s\n' "$*" >&2; exit 1; }
case "$QUEUE_URL" in http://localhost:*|http://127.0.0.1:*) ;; *) die "local queues only, not $QUEUE_URL" ;; esac

now() { date -u +%Y-%m-%dT%H:%M:%SZ; }

# ETag (without quotes) and size of the object in quarantine, or empty values
object_head() {
    curl -s -I "$S3/$BUCKET/$PREFIX$1" | tr -d '\r' | awk -F': ' '
        tolower($1) == "etag" { gsub(/"/, "", $2); etag = $2 }
        tolower($1) == "content-length" { size = $2 }
        END { print etag " " size }'
}

send() {
    curl -s -o /dev/null -w '  queued: HTTP %{http_code}\n' -X POST "$QUEUE_URL" \
        --data-urlencode "Action=SendMessage" --data-urlencode "MessageBody=$1"
}

[ "$#" -ge 1 ] || { sed -n '3,22p' "$0" | sed 's/^# \{0,1\}//'; exit 1; }
kind="$1"; shift
case "$kind" in
    created)
        [ "$#" -eq 1 ] || die "usage: $0 created <file-id>"
        read -r etag size <<< "$(object_head "$1")"
        send "{\"detail-type\":\"Object Created\",\"source\":\"aws.s3\",\"time\":\"$(now)\",\"detail\":{\"bucket\":{\"name\":\"$BUCKET\"},\"object\":{\"key\":\"$PREFIX$1\",\"size\":${size:-0},\"etag\":\"$etag\"},\"reason\":\"PutObject\"}}"
        ;;
    result)
        [ "$#" -ge 2 ] || die "usage: $0 result <file-id> <status> [reason]"
        id="$1" status="$2" reason="${3:-}"
        read -r etag _ <<< "$(object_head "$id")"
        case "$status" in
            NO_THREATS_FOUND|THREATS_FOUND) scan_status=COMPLETED ;;
            UNSUPPORTED|ACCESS_DENIED) scan_status=SKIPPED ;;
            FAILED) scan_status=FAILED ;;
            *) scan_status=COMPLETED ;;  # unknown statuses too: the service must refuse them
        esac
        threats=null reasons=null
        if [ "$status" = THREATS_FOUND ]; then
            threats="[{\"name\":\"${reason:-EICAR-Test-File (not a virus)}\",\"source\":\"AMAZON\"}]"
        elif [ -n "$reason" ]; then
            reasons="[\"$reason\"]"
        fi
        send "{\"detail-type\":\"GuardDuty Malware Protection Object Scan Result\",\"source\":\"aws.guardduty\",\"time\":\"$(now)\",\"detail\":{\"scanStatus\":\"$scan_status\",\"resourceType\":\"S3_OBJECT\",\"s3ObjectDetails\":{\"bucketName\":\"$BUCKET\",\"objectKey\":\"$PREFIX$id\",\"eTag\":\"$etag\",\"s3Throttled\":false},\"scanResultDetails\":{\"scanResultStatus\":\"$status\",\"threats\":$threats,\"statusReasons\":$reasons}}}"
        ;;
    tag)
        [ "$#" -eq 2 ] || die "usage: $0 tag <file-id> <status>"
        curl -s -o /dev/null -w '  tagged: HTTP %{http_code}\n' -X PUT "$S3/$BUCKET/$PREFIX$1?tagging" \
            -H 'Content-Type: application/xml' \
            --data "<Tagging><TagSet><Tag><Key>GuardDutyMalwareScanStatus</Key><Value>$2</Value></Tag></TagSet></Tagging>"
        ;;
    raw)
        [ "$#" -eq 1 ] || die "usage: $0 raw '<message body>'"
        send "$1"
        ;;
    *) die "unknown kind '$kind': created, result, tag or raw" ;;
esac
