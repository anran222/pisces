#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  PISCES_EXPERIMENT_ID=exp_price_001 scripts/event-pipeline-replay-audit.sh

Environment:
  PISCES_API_BASE_URL                         Service base URL. Default: http://localhost:9990/api
  PISCES_EXPERIMENT_ID                        Required experiment ID.
  PISCES_ANALYSIS_API_KEY                     Analysis or management scope API key. Default: ops-key
  PISCES_EVENT_REPLAY_OUTPUT_FILE             JSON report output. Default: target/pisces-event-pipeline-replay-audit/summary.json.
  PISCES_EVENT_REPLAY_EXECUTE                 Call /analysis/experiment/{id}/events/replay. Default: false.
  PISCES_EVENT_REPLAY_RETRY_DEAD_FIRST        Call /event-pipeline/dead/retry before replay. Default: false.
  PISCES_EVENT_REPLAY_REPAIR_MATERIALIZATION  Call /events/replay/materialization/repair. Default: false.
  PISCES_EVENT_REPLAY_OPERATOR                Operator query value. Default: event-replay-audit.
  PISCES_EVENT_REPLAY_FETCH_STATISTICS        Fetch /statistics before and after replay. Default: true.
  PISCES_EVENT_REPLAY_FETCH_PLAN              Fetch read-only /events/replay/plan before replay. Default: true.
  PISCES_EVENT_REPLAY_START_TIME              Optional replay scope start time, passed as request startTime.
  PISCES_EVENT_REPLAY_END_TIME                Optional replay scope end time, passed as request endTime.
  PISCES_EVENT_REPLAY_EVENT_TYPES             Optional comma-separated event types for replay scope.
  PISCES_EVENT_REPLAY_INCLUDE_EVENTS          Optional includeEvents boolean for replay scope.
  PISCES_EVENT_REPLAY_INCLUDE_EXPOSURES       Optional includeExposures boolean for replay scope.
  PISCES_EVENT_REPLAY_SEGMENT_COUNT           Optional time segment count for replay plan. Default: unset.
  PISCES_EVENT_REPLAY_REPAIR_SEGMENT_INDEX    Optional 0-based segment index repaired when repair is enabled. Default: unset.
  PISCES_EVENT_REPLAY_MAX_AFFECTED_PLAN       Optional max affected facts in replay plan. Default: unset.
  PISCES_EVENT_REPLAY_MAX_UNMATERIALIZED_PLAN Optional max unmaterialized facts in replay plan. Default: unset.
  PISCES_EVENT_REPLAY_REQUIRE_HEALTHY_BEFORE  Require pipeline healthy before replay. Default: false.
  PISCES_EVENT_REPLAY_REQUIRE_HEALTHY_AFTER   Require pipeline healthy after audit. Default: true.
  PISCES_EVENT_REPLAY_MAX_UNFINISHED_AFTER    Max unfinished inbox records after audit. Default: 0.
  PISCES_EVENT_REPLAY_MAX_RETRY_AFTER         Max retry inbox records after audit. Default: 0.
  PISCES_EVENT_REPLAY_MAX_DEAD_AFTER          Max dead inbox records after audit. Default: 0.
  PISCES_EVENT_REPLAY_MAX_REJECTED_AFTER      Max rejected inbox records after audit. Default: 0.
  PISCES_EVENT_REPLAY_MAX_PENDING_SECONDS     Max oldest unfinished pending seconds after audit. Default: 300.
  PISCES_EVENT_REPLAY_MIN_REBUILT_FACTS       Min event+exposure facts rebuilt when replay executes. Default: 0.
  PISCES_EVENT_REPLAY_TIMEOUT_SECONDS         HTTP timeout seconds. Default: 10.
  PISCES_EVENT_REPLAY_JOB_TIMEOUT_SECONDS     Replay job terminal wait seconds. Default: 300.
  PISCES_EVENT_REPLAY_JOB_POLL_INTERVAL_SECONDS Replay job polling interval seconds. Default: 2.
USAGE
}

log() {
  printf '[%s] %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*" >&2
}

die() {
  log "ERROR: $*"
  exit 1
}

resolve_output_file() {
  case "$1" in
    /*)
      printf '%s' "$1"
      ;;
    *)
      printf '%s/%s' "$(pwd)" "$1"
      ;;
  esac
}

main() {
  if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
    usage
    exit 0
  fi

  command -v python3 >/dev/null 2>&1 || die "Missing command: python3"

  PISCES_API_BASE_URL="${PISCES_API_BASE_URL:-http://localhost:9990/api}"
  PISCES_EXPERIMENT_ID="${PISCES_EXPERIMENT_ID:-}"
  PISCES_ANALYSIS_API_KEY="${PISCES_ANALYSIS_API_KEY:-ops-key}"
  PISCES_EVENT_REPLAY_OUTPUT_FILE="${PISCES_EVENT_REPLAY_OUTPUT_FILE:-target/pisces-event-pipeline-replay-audit/summary.json}"
  PISCES_EVENT_REPLAY_EXECUTE="${PISCES_EVENT_REPLAY_EXECUTE:-false}"
  PISCES_EVENT_REPLAY_RETRY_DEAD_FIRST="${PISCES_EVENT_REPLAY_RETRY_DEAD_FIRST:-false}"
  PISCES_EVENT_REPLAY_REPAIR_MATERIALIZATION="${PISCES_EVENT_REPLAY_REPAIR_MATERIALIZATION:-false}"
  PISCES_EVENT_REPLAY_OPERATOR="${PISCES_EVENT_REPLAY_OPERATOR:-event-replay-audit}"
  PISCES_EVENT_REPLAY_FETCH_STATISTICS="${PISCES_EVENT_REPLAY_FETCH_STATISTICS:-true}"
  PISCES_EVENT_REPLAY_FETCH_PLAN="${PISCES_EVENT_REPLAY_FETCH_PLAN:-true}"
  PISCES_EVENT_REPLAY_START_TIME="${PISCES_EVENT_REPLAY_START_TIME:-}"
  PISCES_EVENT_REPLAY_END_TIME="${PISCES_EVENT_REPLAY_END_TIME:-}"
  PISCES_EVENT_REPLAY_EVENT_TYPES="${PISCES_EVENT_REPLAY_EVENT_TYPES:-}"
  PISCES_EVENT_REPLAY_INCLUDE_EVENTS="${PISCES_EVENT_REPLAY_INCLUDE_EVENTS:-}"
  PISCES_EVENT_REPLAY_INCLUDE_EXPOSURES="${PISCES_EVENT_REPLAY_INCLUDE_EXPOSURES:-}"
  PISCES_EVENT_REPLAY_SEGMENT_COUNT="${PISCES_EVENT_REPLAY_SEGMENT_COUNT:-}"
  PISCES_EVENT_REPLAY_REPAIR_SEGMENT_INDEX="${PISCES_EVENT_REPLAY_REPAIR_SEGMENT_INDEX:-}"
  PISCES_EVENT_REPLAY_MAX_AFFECTED_PLAN="${PISCES_EVENT_REPLAY_MAX_AFFECTED_PLAN:-}"
  PISCES_EVENT_REPLAY_MAX_UNMATERIALIZED_PLAN="${PISCES_EVENT_REPLAY_MAX_UNMATERIALIZED_PLAN:-}"
  PISCES_EVENT_REPLAY_REQUIRE_HEALTHY_BEFORE="${PISCES_EVENT_REPLAY_REQUIRE_HEALTHY_BEFORE:-false}"
  PISCES_EVENT_REPLAY_REQUIRE_HEALTHY_AFTER="${PISCES_EVENT_REPLAY_REQUIRE_HEALTHY_AFTER:-true}"
  PISCES_EVENT_REPLAY_MAX_UNFINISHED_AFTER="${PISCES_EVENT_REPLAY_MAX_UNFINISHED_AFTER:-0}"
  PISCES_EVENT_REPLAY_MAX_RETRY_AFTER="${PISCES_EVENT_REPLAY_MAX_RETRY_AFTER:-0}"
  PISCES_EVENT_REPLAY_MAX_DEAD_AFTER="${PISCES_EVENT_REPLAY_MAX_DEAD_AFTER:-0}"
  PISCES_EVENT_REPLAY_MAX_REJECTED_AFTER="${PISCES_EVENT_REPLAY_MAX_REJECTED_AFTER:-0}"
  PISCES_EVENT_REPLAY_MAX_PENDING_SECONDS="${PISCES_EVENT_REPLAY_MAX_PENDING_SECONDS:-300}"
  PISCES_EVENT_REPLAY_MIN_REBUILT_FACTS="${PISCES_EVENT_REPLAY_MIN_REBUILT_FACTS:-0}"
  PISCES_EVENT_REPLAY_TIMEOUT_SECONDS="${PISCES_EVENT_REPLAY_TIMEOUT_SECONDS:-10}"
  PISCES_EVENT_REPLAY_JOB_TIMEOUT_SECONDS="${PISCES_EVENT_REPLAY_JOB_TIMEOUT_SECONDS:-300}"
  PISCES_EVENT_REPLAY_JOB_POLL_INTERVAL_SECONDS="${PISCES_EVENT_REPLAY_JOB_POLL_INTERVAL_SECONDS:-2}"

  [[ -n "$PISCES_EXPERIMENT_ID" ]] || die "PISCES_EXPERIMENT_ID is required"

  export PISCES_API_BASE_URL
  export PISCES_EXPERIMENT_ID
  export PISCES_ANALYSIS_API_KEY
  export PISCES_EVENT_REPLAY_EXECUTE
  export PISCES_EVENT_REPLAY_RETRY_DEAD_FIRST
  export PISCES_EVENT_REPLAY_REPAIR_MATERIALIZATION
  export PISCES_EVENT_REPLAY_OPERATOR
  export PISCES_EVENT_REPLAY_FETCH_STATISTICS
  export PISCES_EVENT_REPLAY_FETCH_PLAN
  export PISCES_EVENT_REPLAY_START_TIME
  export PISCES_EVENT_REPLAY_END_TIME
  export PISCES_EVENT_REPLAY_EVENT_TYPES
  export PISCES_EVENT_REPLAY_INCLUDE_EVENTS
  export PISCES_EVENT_REPLAY_INCLUDE_EXPOSURES
  export PISCES_EVENT_REPLAY_SEGMENT_COUNT
  export PISCES_EVENT_REPLAY_REPAIR_SEGMENT_INDEX
  export PISCES_EVENT_REPLAY_MAX_AFFECTED_PLAN
  export PISCES_EVENT_REPLAY_MAX_UNMATERIALIZED_PLAN
  export PISCES_EVENT_REPLAY_REQUIRE_HEALTHY_BEFORE
  export PISCES_EVENT_REPLAY_REQUIRE_HEALTHY_AFTER
  export PISCES_EVENT_REPLAY_MAX_UNFINISHED_AFTER
  export PISCES_EVENT_REPLAY_MAX_RETRY_AFTER
  export PISCES_EVENT_REPLAY_MAX_DEAD_AFTER
  export PISCES_EVENT_REPLAY_MAX_REJECTED_AFTER
  export PISCES_EVENT_REPLAY_MAX_PENDING_SECONDS
  export PISCES_EVENT_REPLAY_MIN_REBUILT_FACTS
  export PISCES_EVENT_REPLAY_TIMEOUT_SECONDS
  export PISCES_EVENT_REPLAY_JOB_TIMEOUT_SECONDS
  export PISCES_EVENT_REPLAY_JOB_POLL_INTERVAL_SECONDS

  local output_file
  output_file="$(resolve_output_file "$PISCES_EVENT_REPLAY_OUTPUT_FILE")"
  mkdir -p "$(dirname "$output_file")"

  python3 - "$output_file" <<'PY'
import json
import os
import sys
import time
from datetime import datetime, timezone
from urllib import error, parse, request

output_file = sys.argv[1]


def now_iso():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_bool(value, field):
    normalized = str(value).strip().lower()
    if normalized in {"1", "true", "yes", "y"}:
        return True
    if normalized in {"0", "false", "no", "n"}:
        return False
    raise SystemExit(f"{field} must be boolean: {value}")


def parse_optional_bool(value, field):
    if str(value).strip() == "":
        return None
    return parse_bool(value, field)


def parse_int(value, field, minimum=None):
    try:
        parsed = int(value)
    except ValueError as exc:
        raise SystemExit(f"{field} must be integer: {value}") from exc
    if minimum is not None and parsed < minimum:
        raise SystemExit(f"{field} must be >= {minimum}: {value}")
    return parsed


def parse_optional_int(value, field, minimum=None):
    if str(value).strip() == "":
        return None
    return parse_int(value, field, minimum)


def parse_float(value, field, minimum=None):
    try:
        parsed = float(value)
    except ValueError as exc:
        raise SystemExit(f"{field} must be numeric: {value}") from exc
    if minimum is not None and parsed < minimum:
        raise SystemExit(f"{field} must be >= {minimum}: {value}")
    return parsed


def build_url(base_url, path, query=None):
    url = f"{base_url.rstrip('/')}{path}"
    if query:
        url = f"{url}?{parse.urlencode(query)}"
    return url


def parse_csv(value):
    return [item.strip() for item in str(value).split(",") if item.strip()]


def build_replay_scope_request():
    payload = {}
    start_time = os.environ["PISCES_EVENT_REPLAY_START_TIME"].strip()
    end_time = os.environ["PISCES_EVENT_REPLAY_END_TIME"].strip()
    event_types = parse_csv(os.environ["PISCES_EVENT_REPLAY_EVENT_TYPES"])
    segment_count = parse_optional_int(
        os.environ["PISCES_EVENT_REPLAY_SEGMENT_COUNT"],
        "PISCES_EVENT_REPLAY_SEGMENT_COUNT",
        minimum=1,
    )
    include_events = parse_optional_bool(
        os.environ["PISCES_EVENT_REPLAY_INCLUDE_EVENTS"],
        "PISCES_EVENT_REPLAY_INCLUDE_EVENTS",
    )
    include_exposures = parse_optional_bool(
        os.environ["PISCES_EVENT_REPLAY_INCLUDE_EXPOSURES"],
        "PISCES_EVENT_REPLAY_INCLUDE_EXPOSURES",
    )
    if start_time:
        payload["startTime"] = start_time
    if end_time:
        payload["endTime"] = end_time
    if event_types:
        payload["eventTypes"] = event_types
    if include_events is not None:
        payload["includeEvents"] = include_events
    if include_exposures is not None:
        payload["includeExposures"] = include_exposures
    if segment_count is not None:
        payload["segmentCount"] = segment_count
    return payload


def compact_request_result(result):
    return {
        "method": result["method"],
        "url": result["url"],
        "ok": result["ok"],
        "httpStatus": result["httpStatus"],
        "baseCode": result["baseCode"],
        "baseMessage": result["baseMessage"],
        "error": result["error"],
    }


def request_json(method, url, api_key, timeout_seconds, payload=None):
    headers = {"X-Pisces-Api-Key": api_key}
    body = None
    if payload is not None:
        headers["Content-Type"] = "application/json"
        body = json.dumps(payload).encode("utf-8")
    result = {
        "method": method,
        "url": url,
        "ok": False,
        "httpStatus": None,
        "baseCode": None,
        "baseMessage": None,
        "error": None,
        "data": None,
    }
    req = request.Request(url, data=body, headers=headers, method=method)
    try:
        with request.urlopen(req, timeout=timeout_seconds) as response:
            result["httpStatus"] = response.status
            raw_body = response.read().decode("utf-8")
    except error.HTTPError as exc:
        result["httpStatus"] = exc.code
        raw_body = exc.read().decode("utf-8", errors="replace")
        result["error"] = f"HTTPError: {exc.reason}"
    except Exception as exc:  # urllib raises several transport-level exception classes.
        result["error"] = f"{exc.__class__.__name__}: {exc}"
        return result

    try:
        payload_json = json.loads(raw_body) if raw_body else {}
    except json.JSONDecodeError as exc:
        result["error"] = f"Invalid JSON: {exc}"
        return result

    if not isinstance(payload_json, dict):
        result["error"] = "Base response is not a JSON object"
        return result

    result["baseCode"] = payload_json.get("code")
    result["baseMessage"] = payload_json.get("message")
    result["data"] = payload_json.get("data")
    result["ok"] = (
        result["httpStatus"] is not None
        and 200 <= result["httpStatus"] < 300
        and result["baseCode"] == 200
    )
    if not result["ok"] and result["error"] is None:
        result["error"] = (
            f"Request failed: http={result['httpStatus']} "
            f"code={result['baseCode']} message={result['baseMessage']}"
        )
    return result


def summarize_status(data):
    if not isinstance(data, dict):
        return {
            "experimentId": None,
            "totalCount": None,
            "pendingCount": None,
            "processingCount": None,
            "retryCount": None,
            "doneCount": None,
            "deadCount": None,
            "rejectedCount": None,
            "unfinishedCount": None,
            "maxPendingSeconds": None,
            "healthy": None,
            "status": None,
            "generatedAt": None,
        }
    fields = (
        "experimentId",
        "totalCount",
        "pendingCount",
        "processingCount",
        "retryCount",
        "doneCount",
        "deadCount",
        "rejectedCount",
        "unfinishedCount",
        "maxPendingSeconds",
        "healthy",
        "status",
        "generatedAt",
    )
    return {field: data.get(field) for field in fields}


def summarize_operation(data):
    if not isinstance(data, dict):
        return {
            "experimentId": None,
            "operation": None,
            "operator": None,
            "status": None,
            "affectedCount": None,
            "eventCount": None,
            "exposureCount": None,
            "groupCount": None,
            "mabRewardCount": None,
            "replayJobId": None,
            "replayJobStatus": None,
            "replayMode": None,
            "scopeStartTime": None,
            "scopeEndTime": None,
            "eventTypes": None,
            "includeEvents": None,
            "includeExposures": None,
            "fullDerivedReplay": None,
            "message": None,
            "operatedAt": None,
        }
    fields = (
        "experimentId",
        "operation",
        "operator",
        "status",
        "affectedCount",
        "eventCount",
        "exposureCount",
        "groupCount",
        "mabRewardCount",
        "replayJobId",
        "replayJobStatus",
        "replayMode",
        "scopeStartTime",
        "scopeEndTime",
        "eventTypes",
        "includeEvents",
        "includeExposures",
        "fullDerivedReplay",
        "message",
        "operatedAt",
    )
    return {field: data.get(field) for field in fields}


def summarize_replay_job(data):
    fields = (
        "replayJobId",
        "experimentId",
        "operator",
        "jobStatus",
        "activeKey",
        "replayMode",
        "scopeStartTime",
        "scopeEndTime",
        "eventTypes",
        "includeEvents",
        "includeExposures",
        "fullDerivedReplay",
        "plannedAffectedCount",
        "plannedEventCount",
        "plannedExposureCount",
        "plannedGroupCount",
        "progressPercent",
        "affectedCount",
        "eventCount",
        "exposureCount",
        "groupCount",
        "mabRewardCount",
        "errorMessage",
        "startedAt",
        "finishedAt",
    )
    if not isinstance(data, dict):
        return {field: None for field in fields}
    return {field: data.get(field) for field in fields}


def summarize_statistics(data):
    if not isinstance(data, dict):
        return {
            "experimentId": None,
            "summary": {
                "totalAssignments": None,
                "totalExposures": None,
                "totalEvents": None,
                "totalVisitors": None,
            },
            "analysisReady": None,
            "blockingIssues": None,
        }
    summary = data.get("summary") if isinstance(data.get("summary"), dict) else {}
    quality = data.get("dataQualityCheck") if isinstance(data.get("dataQualityCheck"), dict) else {}
    return {
        "experimentId": data.get("experimentId"),
        "summary": {
            "totalAssignments": summary.get("totalAssignments"),
            "totalExposures": summary.get("totalExposures"),
            "totalEvents": summary.get("totalEvents"),
            "totalVisitors": summary.get("totalVisitors"),
        },
        "analysisReady": quality.get("analysisReady"),
        "blockingIssues": quality.get("blockingIssues"),
    }


def summarize_replay_plan(data):
    if not isinstance(data, dict):
        return {
            "experimentId": None,
            "replayMode": None,
            "fullDerivedReplay": None,
            "eventCount": None,
            "materializedEventCount": None,
            "unmaterializedEventCount": None,
            "exposureCount": None,
            "materializedExposureCount": None,
            "unmaterializedExposureCount": None,
            "affectedCount": None,
            "materializedCount": None,
            "unmaterializedCount": None,
            "groupCount": None,
            "requestedSegmentCount": None,
            "segmentCount": None,
            "segmentRecoverySupported": None,
            "maxSegmentAffectedCount": None,
            "maxSegmentUnmaterializedCount": None,
            "generatedAt": None,
            "groups": [],
            "segments": [],
        }
    group_fields = (
        "groupId",
        "groupName",
        "eventCount",
        "materializedEventCount",
        "unmaterializedEventCount",
        "exposureCount",
        "materializedExposureCount",
        "unmaterializedExposureCount",
        "affectedCount",
        "materializedCount",
        "unmaterializedCount",
    )
    groups = data.get("groups") if isinstance(data.get("groups"), list) else []
    segment_fields = (
        "segmentIndex",
        "segmentKey",
        "startTime",
        "endTime",
        "eventCount",
        "exposureCount",
        "affectedCount",
        "materializedCount",
        "unmaterializedCount",
        "recommendedAction",
    )
    segments = data.get("segments") if isinstance(data.get("segments"), list) else []
    fields = (
        "experimentId",
        "replayMode",
        "fullDerivedReplay",
        "eventCount",
        "materializedEventCount",
        "unmaterializedEventCount",
        "exposureCount",
        "materializedExposureCount",
        "unmaterializedExposureCount",
        "affectedCount",
        "materializedCount",
        "unmaterializedCount",
        "groupCount",
        "requestedSegmentCount",
        "segmentCount",
        "segmentRecoverySupported",
        "maxSegmentAffectedCount",
        "maxSegmentUnmaterializedCount",
        "generatedAt",
    )
    return {
        **{field: data.get(field) for field in fields},
        "groups": [
            {field: group.get(field) for field in group_fields}
            for group in groups
            if isinstance(group, dict)
        ],
        "segments": [
            {field: segment.get(field) for field in segment_fields}
            for segment in segments
            if isinstance(segment, dict)
        ],
    }


def add_gate(gates, name, status, actual=None, threshold=None, details=None):
    gate = {
        "name": name,
        "status": status,
        "actual": actual,
        "threshold": threshold,
    }
    if details:
        gate["details"] = details
    gates.append(gate)


def number_value(value, default=0):
    if value is None:
        return default
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


def fetch_status(base_url, experiment_id, api_key, timeout_seconds):
    encoded = parse.quote(experiment_id, safe="")
    url = build_url(base_url, f"/analysis/experiment/{encoded}/event-pipeline")
    result = request_json("GET", url, api_key, timeout_seconds)
    return result, summarize_status(result["data"]) if result["ok"] else summarize_status(None)


def fetch_statistics(base_url, experiment_id, api_key, timeout_seconds):
    encoded = parse.quote(experiment_id, safe="")
    url = build_url(base_url, f"/analysis/experiment/{encoded}/statistics")
    result = request_json("GET", url, api_key, timeout_seconds)
    return result, summarize_statistics(result["data"]) if result["ok"] else summarize_statistics(None)


def fetch_replay_plan(base_url, experiment_id, api_key, timeout_seconds, replay_scope_request):
    encoded = parse.quote(experiment_id, safe="")
    url = build_url(base_url, f"/analysis/experiment/{encoded}/events/replay/plan")
    result = request_json("POST", url, api_key, timeout_seconds, payload=replay_scope_request)
    return result, summarize_replay_plan(result["data"]) if result["ok"] else summarize_replay_plan(None)


def fetch_replay_job(base_url, experiment_id, replay_job_id, api_key, timeout_seconds):
    encoded_experiment_id = parse.quote(experiment_id, safe="")
    encoded_replay_job_id = parse.quote(replay_job_id, safe="")
    url = build_url(
        base_url,
        f"/analysis/experiment/{encoded_experiment_id}/events/replay/jobs/{encoded_replay_job_id}",
    )
    result = request_json("GET", url, api_key, timeout_seconds)
    return result, summarize_replay_job(result["data"]) if result["ok"] else summarize_replay_job(None)


def wait_for_replay_job(base_url, experiment_id, replay_job_id, api_key, timeout_seconds,
                        job_timeout_seconds, poll_interval_seconds):
    terminal_statuses = {"SUCCEEDED", "FAILED", "CANCELLED"}
    deadline = time.monotonic() + max(0.0, job_timeout_seconds)
    poll_interval = max(0.1, poll_interval_seconds)
    attempts = 0
    last_request = None
    last_job = summarize_replay_job(None)
    poll_summary = {
        "maxProgressPercent": None,
        "maxAffectedCount": None,
        "plannedAffectedCount": None,
    }
    while True:
        attempts += 1
        last_request, last_job = fetch_replay_job(
            base_url,
            experiment_id,
            replay_job_id,
            api_key,
            timeout_seconds,
        )
        if last_request["ok"]:
            progress_percent = number_value(last_job.get("progressPercent"), None)
            affected_count = number_value(last_job.get("affectedCount"), None)
            planned_affected_count = number_value(last_job.get("plannedAffectedCount"), None)
            if progress_percent is not None:
                poll_summary["maxProgressPercent"] = max(
                    progress_percent,
                    poll_summary["maxProgressPercent"]
                    if poll_summary["maxProgressPercent"] is not None else progress_percent,
                )
            if affected_count is not None:
                poll_summary["maxAffectedCount"] = max(
                    affected_count,
                    poll_summary["maxAffectedCount"]
                    if poll_summary["maxAffectedCount"] is not None else affected_count,
                )
            if planned_affected_count is not None:
                poll_summary["plannedAffectedCount"] = planned_affected_count
        if last_request["ok"] and last_job.get("jobStatus") in terminal_statuses:
            return last_request, last_job, attempts, poll_summary
        remaining_seconds = deadline - time.monotonic()
        if remaining_seconds <= 0:
            return last_request, last_job, attempts, poll_summary
        time.sleep(min(poll_interval, remaining_seconds))


def post_operation(base_url, experiment_id, operator, operation_path, api_key, timeout_seconds, payload=None):
    encoded = parse.quote(experiment_id, safe="")
    url = build_url(
        base_url,
        f"/analysis/experiment/{encoded}/{operation_path}",
        {"operator": operator},
    )
    result = request_json("POST", url, api_key, timeout_seconds, payload=payload)
    return result, summarize_operation(result["data"]) if result["ok"] else summarize_operation(None)


base_url = os.environ["PISCES_API_BASE_URL"].rstrip("/")
experiment_id = os.environ["PISCES_EXPERIMENT_ID"]
api_key = os.environ["PISCES_ANALYSIS_API_KEY"]
execute_replay = parse_bool(os.environ["PISCES_EVENT_REPLAY_EXECUTE"], "PISCES_EVENT_REPLAY_EXECUTE")
retry_dead_first = parse_bool(
    os.environ["PISCES_EVENT_REPLAY_RETRY_DEAD_FIRST"],
    "PISCES_EVENT_REPLAY_RETRY_DEAD_FIRST",
)
repair_materialization = parse_bool(
    os.environ["PISCES_EVENT_REPLAY_REPAIR_MATERIALIZATION"],
    "PISCES_EVENT_REPLAY_REPAIR_MATERIALIZATION",
)
repair_segment_index = parse_optional_int(
    os.environ["PISCES_EVENT_REPLAY_REPAIR_SEGMENT_INDEX"],
    "PISCES_EVENT_REPLAY_REPAIR_SEGMENT_INDEX",
    minimum=0,
)
fetch_stats = parse_bool(
    os.environ["PISCES_EVENT_REPLAY_FETCH_STATISTICS"],
    "PISCES_EVENT_REPLAY_FETCH_STATISTICS",
)
fetch_plan = parse_bool(
    os.environ["PISCES_EVENT_REPLAY_FETCH_PLAN"],
    "PISCES_EVENT_REPLAY_FETCH_PLAN",
)
max_unmaterialized_plan = parse_optional_int(
    os.environ["PISCES_EVENT_REPLAY_MAX_UNMATERIALIZED_PLAN"],
    "PISCES_EVENT_REPLAY_MAX_UNMATERIALIZED_PLAN",
    minimum=0,
)
max_affected_plan = parse_optional_int(
    os.environ["PISCES_EVENT_REPLAY_MAX_AFFECTED_PLAN"],
    "PISCES_EVENT_REPLAY_MAX_AFFECTED_PLAN",
    minimum=0,
)
require_healthy_before = parse_bool(
    os.environ["PISCES_EVENT_REPLAY_REQUIRE_HEALTHY_BEFORE"],
    "PISCES_EVENT_REPLAY_REQUIRE_HEALTHY_BEFORE",
)
require_healthy_after = parse_bool(
    os.environ["PISCES_EVENT_REPLAY_REQUIRE_HEALTHY_AFTER"],
    "PISCES_EVENT_REPLAY_REQUIRE_HEALTHY_AFTER",
)
operator = os.environ["PISCES_EVENT_REPLAY_OPERATOR"]
max_unfinished_after = parse_int(
    os.environ["PISCES_EVENT_REPLAY_MAX_UNFINISHED_AFTER"],
    "PISCES_EVENT_REPLAY_MAX_UNFINISHED_AFTER",
    minimum=0,
)
max_retry_after = parse_int(
    os.environ["PISCES_EVENT_REPLAY_MAX_RETRY_AFTER"],
    "PISCES_EVENT_REPLAY_MAX_RETRY_AFTER",
    minimum=0,
)
max_dead_after = parse_int(
    os.environ["PISCES_EVENT_REPLAY_MAX_DEAD_AFTER"],
    "PISCES_EVENT_REPLAY_MAX_DEAD_AFTER",
    minimum=0,
)
max_rejected_after = parse_int(
    os.environ["PISCES_EVENT_REPLAY_MAX_REJECTED_AFTER"],
    "PISCES_EVENT_REPLAY_MAX_REJECTED_AFTER",
    minimum=0,
)
max_pending_seconds = parse_int(
    os.environ["PISCES_EVENT_REPLAY_MAX_PENDING_SECONDS"],
    "PISCES_EVENT_REPLAY_MAX_PENDING_SECONDS",
    minimum=0,
)
min_rebuilt_facts = parse_int(
    os.environ["PISCES_EVENT_REPLAY_MIN_REBUILT_FACTS"],
    "PISCES_EVENT_REPLAY_MIN_REBUILT_FACTS",
    minimum=0,
)
timeout_seconds = parse_float(
    os.environ["PISCES_EVENT_REPLAY_TIMEOUT_SECONDS"],
    "PISCES_EVENT_REPLAY_TIMEOUT_SECONDS",
    minimum=0.1,
)
replay_job_timeout_seconds = parse_float(
    os.environ["PISCES_EVENT_REPLAY_JOB_TIMEOUT_SECONDS"],
    "PISCES_EVENT_REPLAY_JOB_TIMEOUT_SECONDS",
    minimum=0,
)
replay_job_poll_interval_seconds = parse_float(
    os.environ["PISCES_EVENT_REPLAY_JOB_POLL_INTERVAL_SECONDS"],
    "PISCES_EVENT_REPLAY_JOB_POLL_INTERVAL_SECONDS",
    minimum=0,
)
replay_scope_request = build_replay_scope_request()

gates = []

before_status_request, before_status = fetch_status(base_url, experiment_id, api_key, timeout_seconds)
add_gate(
    gates,
    "before_status_request_success",
    "PASS" if before_status_request["ok"] else "FAIL",
    actual=before_status_request["ok"],
    threshold=True,
    details=compact_request_result(before_status_request),
)
if require_healthy_before:
    add_gate(
        gates,
        "before_pipeline_healthy",
        "PASS" if before_status.get("healthy") is True else "FAIL",
        actual=before_status.get("healthy"),
        threshold=True,
    )
else:
    add_gate(
        gates,
        "before_pipeline_healthy",
        "SKIP",
        actual=before_status.get("healthy"),
        threshold=True,
        details={"reason": "PISCES_EVENT_REPLAY_REQUIRE_HEALTHY_BEFORE=false"},
    )

before_statistics_request = None
before_statistics = None
if fetch_stats:
    before_statistics_request, before_statistics = fetch_statistics(base_url, experiment_id, api_key, timeout_seconds)
    add_gate(
        gates,
        "before_statistics_request_success",
        "PASS" if before_statistics_request["ok"] else "FAIL",
        actual=before_statistics_request["ok"],
        threshold=True,
        details=compact_request_result(before_statistics_request),
    )

replay_plan_request = None
replay_plan = None
if fetch_plan:
    replay_plan_request, replay_plan = fetch_replay_plan(
        base_url,
        experiment_id,
        api_key,
        timeout_seconds,
        replay_scope_request,
    )
    add_gate(
        gates,
        "replay_plan_request_success",
        "PASS" if replay_plan_request["ok"] else "FAIL",
        actual=replay_plan_request["ok"],
        threshold=True,
        details=compact_request_result(replay_plan_request),
    )
    actual_affected = number_value(replay_plan.get("affectedCount"))
    if max_affected_plan is not None:
        add_gate(
            gates,
            "replay_plan_affected_count",
            "PASS" if actual_affected <= max_affected_plan else "FAIL",
            actual=actual_affected,
            threshold=f"<= {max_affected_plan}",
        )
    else:
        add_gate(
            gates,
            "replay_plan_affected_count",
            "SKIP",
            actual=actual_affected,
            threshold=None,
            details={"reason": "PISCES_EVENT_REPLAY_MAX_AFFECTED_PLAN is unset"},
        )
    if max_unmaterialized_plan is not None and not repair_materialization:
        actual_unmaterialized = number_value(replay_plan.get("unmaterializedCount"))
        add_gate(
            gates,
            "replay_plan_unmaterialized_count",
            "PASS" if actual_unmaterialized <= max_unmaterialized_plan else "FAIL",
            actual=actual_unmaterialized,
            threshold=f"<= {max_unmaterialized_plan}",
        )
    elif max_unmaterialized_plan is not None:
        add_gate(
            gates,
            "replay_plan_unmaterialized_count",
            "SKIP",
            actual=number_value(replay_plan.get("unmaterializedCount")),
            threshold=f"<= {max_unmaterialized_plan}",
            details={"reason": "PISCES_EVENT_REPLAY_REPAIR_MATERIALIZATION=true; threshold is checked after repair."},
        )
    else:
        add_gate(
            gates,
            "replay_plan_unmaterialized_count",
            "SKIP",
            actual=number_value(replay_plan.get("unmaterializedCount")),
            threshold=None,
            details={"reason": "PISCES_EVENT_REPLAY_MAX_UNMATERIALIZED_PLAN is unset"},
        )
    requested_segment_count = replay_scope_request.get("segmentCount")
    if requested_segment_count and requested_segment_count > 1:
        actual_segment_count = number_value(replay_plan.get("segmentCount"))
        add_gate(
            gates,
            "replay_plan_segments_generated",
            "PASS" if replay_plan.get("segmentRecoverySupported") is True and actual_segment_count > 0 else "FAIL",
            actual={
                "segmentRecoverySupported": replay_plan.get("segmentRecoverySupported"),
                "segmentCount": replay_plan.get("segmentCount"),
                "maxSegmentUnmaterializedCount": replay_plan.get("maxSegmentUnmaterializedCount"),
            },
            threshold={"segmentRecoverySupported": True, "segmentCount": "> 0"},
        )
    else:
        add_gate(
            gates,
            "replay_plan_segments_generated",
            "SKIP",
            actual=replay_plan.get("segmentCount"),
            threshold=None,
            details={"reason": "PISCES_EVENT_REPLAY_SEGMENT_COUNT is unset or <= 1"},
        )
else:
    add_gate(
        gates,
        "replay_plan_request_success",
        "SKIP",
        actual=False,
        threshold=True,
        details={"reason": "PISCES_EVENT_REPLAY_FETCH_PLAN=false"},
    )
    add_gate(
        gates,
        "replay_plan_affected_count",
        "FAIL" if max_affected_plan is not None else "SKIP",
        actual=None,
        threshold=None if max_affected_plan is None else f"<= {max_affected_plan}",
        details={"reason": "PISCES_EVENT_REPLAY_FETCH_PLAN=false"},
    )
    add_gate(
        gates,
        "replay_plan_segments_generated",
        "SKIP",
        actual=None,
        threshold=None,
        details={"reason": "PISCES_EVENT_REPLAY_FETCH_PLAN=false"},
    )

retry_operation_request = None
retry_operation = None
if retry_dead_first:
    retry_operation_request, retry_operation = post_operation(
        base_url,
        experiment_id,
        operator,
        "event-pipeline/dead/retry",
        api_key,
        timeout_seconds,
    )
    add_gate(
        gates,
        "retry_dead_request_success",
        "PASS" if retry_operation_request["ok"] else "FAIL",
        actual=retry_operation_request["ok"],
        threshold=True,
        details=compact_request_result(retry_operation_request),
    )
    add_gate(
        gates,
        "retry_dead_operation_success",
        "PASS" if retry_operation.get("operation") == "RETRY_DEAD" and retry_operation.get("status") == "SUCCESS" else "FAIL",
        actual={"operation": retry_operation.get("operation"), "status": retry_operation.get("status")},
        threshold={"operation": "RETRY_DEAD", "status": "SUCCESS"},
    )

repair_operation_request = None
repair_operation = None
replay_plan_after_repair_request = None
replay_plan_after_repair = None
if repair_materialization:
    repair_operation_path = "events/replay/materialization/repair"
    if repair_segment_index is not None:
        repair_operation_path = f"{repair_operation_path}/segments/{repair_segment_index}"
    repair_operation_request, repair_operation = post_operation(
        base_url,
        experiment_id,
        operator,
        repair_operation_path,
        api_key,
        timeout_seconds,
        payload=replay_scope_request,
    )
    add_gate(
        gates,
        "repair_materialization_request_success",
        "PASS" if repair_operation_request["ok"] else "FAIL",
        actual=repair_operation_request["ok"],
        threshold=True,
        details=compact_request_result(repair_operation_request),
    )
    add_gate(
        gates,
        "repair_materialization_operation_success",
        "PASS" if repair_operation.get("operation") == "REPAIR_MATERIALIZATION"
        and repair_operation.get("status") == "SUCCESS" else "FAIL",
        actual={"operation": repair_operation.get("operation"), "status": repair_operation.get("status")},
        threshold={"operation": "REPAIR_MATERIALIZATION", "status": "SUCCESS"},
        details={"repairSegmentIndex": repair_segment_index},
    )
    if fetch_plan:
        replay_plan_after_repair_request, replay_plan_after_repair = fetch_replay_plan(
            base_url,
            experiment_id,
            api_key,
            timeout_seconds,
            replay_scope_request,
        )
        add_gate(
            gates,
            "post_repair_replay_plan_request_success",
            "PASS" if replay_plan_after_repair_request["ok"] else "FAIL",
            actual=replay_plan_after_repair_request["ok"],
            threshold=True,
            details=compact_request_result(replay_plan_after_repair_request),
        )
        if max_unmaterialized_plan is not None:
            actual_unmaterialized = number_value(replay_plan_after_repair.get("unmaterializedCount"))
            add_gate(
                gates,
                "post_repair_replay_plan_unmaterialized_count",
                "PASS" if actual_unmaterialized <= max_unmaterialized_plan else "FAIL",
                actual=actual_unmaterialized,
                threshold=f"<= {max_unmaterialized_plan}",
            )
else:
    add_gate(
        gates,
        "repair_materialization_execution",
        "SKIP",
        actual=False,
        threshold=True,
        details={"reason": "Set PISCES_EVENT_REPLAY_REPAIR_MATERIALIZATION=true to repair missing materialization ledger facts."},
    )

replay_operation_request = None
replay_operation = None
replay_job_request = None
replay_job = None
replay_job_poll_attempts = 0
replay_job_poll_summary = None
if execute_replay:
    replay_operation_request, replay_operation = post_operation(
        base_url,
        experiment_id,
        operator,
        "events/replay",
        api_key,
        timeout_seconds,
        payload=replay_scope_request,
    )
    add_gate(
        gates,
        "replay_request_success",
        "PASS" if replay_operation_request["ok"] else "FAIL",
        actual=replay_operation_request["ok"],
        threshold=True,
        details=compact_request_result(replay_operation_request),
    )
    add_gate(
        gates,
        "replay_operation_success",
        "PASS" if replay_operation.get("operation") == "REPLAY_DERIVED"
        and replay_operation.get("status") in {"RUNNING", "SUCCESS"} else "FAIL",
        actual={"operation": replay_operation.get("operation"), "status": replay_operation.get("status")},
        threshold={"operation": "REPLAY_DERIVED", "status": "RUNNING|SUCCESS"},
    )
    replay_job_id = replay_operation.get("replayJobId")
    if replay_job_id:
        replay_job_request, replay_job, replay_job_poll_attempts, replay_job_poll_summary = wait_for_replay_job(
            base_url,
            experiment_id,
            replay_job_id,
            api_key,
            timeout_seconds,
            replay_job_timeout_seconds,
            replay_job_poll_interval_seconds,
        )
        add_gate(
            gates,
            "replay_job_request_success",
            "PASS" if replay_job_request["ok"] else "FAIL",
            actual=replay_job_request["ok"],
            threshold=True,
            details={
                **compact_request_result(replay_job_request),
                "pollAttempts": replay_job_poll_attempts,
                "pollSummary": replay_job_poll_summary,
            },
        )
        add_gate(
            gates,
            "replay_job_terminal_success",
            "PASS" if replay_job.get("jobStatus") == "SUCCEEDED" else "FAIL",
            actual=replay_job.get("jobStatus"),
            threshold="SUCCEEDED",
            details={"pollAttempts": replay_job_poll_attempts, "pollSummary": replay_job_poll_summary},
        )
    else:
        add_gate(
            gates,
            "replay_job_terminal_success",
            "SKIP",
            actual=None,
            threshold="SUCCEEDED",
            details={"reason": "Replay operation did not return replayJobId; using operation counters."},
        )
    replay_counter_source = replay_job if replay_job and replay_job.get("jobStatus") == "SUCCEEDED" else replay_operation
    rebuilt_facts = number_value(replay_counter_source.get("eventCount")) + number_value(
        replay_counter_source.get("exposureCount")
    )
    add_gate(
        gates,
        "replay_rebuilt_fact_count",
        "PASS" if rebuilt_facts >= min_rebuilt_facts else "FAIL",
        actual=rebuilt_facts,
        threshold=f">= {min_rebuilt_facts}",
    )
else:
    add_gate(
        gates,
        "replay_execution",
        "SKIP",
        actual=False,
        threshold=True,
        details={"reason": "Set PISCES_EVENT_REPLAY_EXECUTE=true to rebuild derived data."},
    )

after_status_request, after_status = fetch_status(base_url, experiment_id, api_key, timeout_seconds)
add_gate(
    gates,
    "after_status_request_success",
    "PASS" if after_status_request["ok"] else "FAIL",
    actual=after_status_request["ok"],
    threshold=True,
    details=compact_request_result(after_status_request),
)
if require_healthy_after:
    add_gate(
        gates,
        "after_pipeline_healthy",
        "PASS" if after_status.get("healthy") is True else "FAIL",
        actual=after_status.get("healthy"),
        threshold=True,
    )
else:
    add_gate(
        gates,
        "after_pipeline_healthy",
        "SKIP",
        actual=after_status.get("healthy"),
        threshold=True,
        details={"reason": "PISCES_EVENT_REPLAY_REQUIRE_HEALTHY_AFTER=false"},
    )

for field, threshold, gate_name in (
    ("unfinishedCount", max_unfinished_after, "after_unfinished_count"),
    ("retryCount", max_retry_after, "after_retry_count"),
    ("deadCount", max_dead_after, "after_dead_count"),
    ("rejectedCount", max_rejected_after, "after_rejected_count"),
    ("maxPendingSeconds", max_pending_seconds, "after_max_pending_seconds"),
):
    actual = number_value(after_status.get(field))
    add_gate(
        gates,
        gate_name,
        "PASS" if actual <= threshold else "FAIL",
        actual=actual,
        threshold=f"<= {threshold}",
    )

after_statistics_request = None
after_statistics = None
if fetch_stats:
    after_statistics_request, after_statistics = fetch_statistics(base_url, experiment_id, api_key, timeout_seconds)
    add_gate(
        gates,
        "after_statistics_request_success",
        "PASS" if after_statistics_request["ok"] else "FAIL",
        actual=after_statistics_request["ok"],
        threshold=True,
        details=compact_request_result(after_statistics_request),
    )
    if before_statistics_request and before_statistics_request["ok"] and after_statistics_request["ok"]:
        for field in ("totalAssignments", "totalExposures", "totalEvents"):
            before_value = number_value((before_statistics.get("summary") or {}).get(field))
            after_value = number_value((after_statistics.get("summary") or {}).get(field))
            add_gate(
                gates,
                f"statistics_{field}_not_decreased",
                "PASS" if after_value >= before_value else "FAIL",
                actual={"before": before_value, "after": after_value},
                threshold="after >= before",
            )

failed_gates = [gate for gate in gates if gate["status"] == "FAIL"]
status = "FAIL" if failed_gates else "PASS"
summary = {
    "summaryType": "pisces-event-pipeline-replay-audit",
    "summaryVersion": 1,
    "status": status,
    "generatedAt": now_iso(),
    "apiBaseUrl": base_url,
    "experimentId": experiment_id,
    "operator": operator,
    "executeReplay": execute_replay,
    "retryDeadFirst": retry_dead_first,
    "repairMaterialization": repair_materialization,
    "repairSegmentIndex": repair_segment_index,
    "replayScopeRequest": replay_scope_request,
    "thresholds": {
        "maxUnmaterializedPlan": max_unmaterialized_plan,
        "maxUnfinishedAfter": max_unfinished_after,
        "maxRetryAfter": max_retry_after,
        "maxDeadAfter": max_dead_after,
        "maxRejectedAfter": max_rejected_after,
        "maxPendingSeconds": max_pending_seconds,
        "minRebuiltFacts": min_rebuilt_facts,
        "replayJobTimeoutSeconds": replay_job_timeout_seconds,
        "replayJobPollIntervalSeconds": replay_job_poll_interval_seconds,
    },
    "requests": {
        "beforeStatus": compact_request_result(before_status_request),
        "beforeStatistics": compact_request_result(before_statistics_request) if before_statistics_request else None,
        "replayPlan": compact_request_result(replay_plan_request) if replay_plan_request else None,
        "retryDead": compact_request_result(retry_operation_request) if retry_operation_request else None,
        "repairMaterialization": compact_request_result(repair_operation_request)
        if repair_operation_request else None,
        "replayPlanAfterRepair": compact_request_result(replay_plan_after_repair_request)
        if replay_plan_after_repair_request else None,
        "replay": compact_request_result(replay_operation_request) if replay_operation_request else None,
        "replayJob": compact_request_result(replay_job_request) if replay_job_request else None,
        "afterStatus": compact_request_result(after_status_request),
        "afterStatistics": compact_request_result(after_statistics_request) if after_statistics_request else None,
    },
    "beforeStatus": before_status,
    "replayPlan": replay_plan,
    "replayPlanAfterRepair": replay_plan_after_repair,
    "afterStatus": after_status,
    "beforeStatistics": before_statistics,
    "afterStatistics": after_statistics,
    "retryOperation": retry_operation,
    "repairMaterializationOperation": repair_operation,
    "replayOperation": replay_operation,
    "replayJob": replay_job,
    "replayJobPollSummary": replay_job_poll_summary,
    "gates": gates,
}

with open(output_file, "w", encoding="utf-8") as target:
    json.dump(summary, target, ensure_ascii=False, indent=2, sort_keys=True)
    target.write("\n")

print(f"Event pipeline replay audit written: {output_file} status={status}", file=sys.stderr)
if status != "PASS":
    sys.exit(1)
PY
}

main "$@"
