#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  PISCES_RELEASE_EVIDENCE_MANIFEST_FILE=target/pisces-runtime-release-evidence-archive/<release>/manifest.json \
  PISCES_POST_RELEASE_METRICS_FILE=docs/operations/runtime-plane-post-release-slo-sample.json \
  scripts/runtime-plane-post-release-slo-review.sh

Environment:
  PISCES_RELEASE_EVIDENCE_MANIFEST_FILE       Required release evidence manifest.
  PISCES_POST_RELEASE_METRICS_FILE            Required post-release metrics snapshot JSON.
  PISCES_REDIS_FAULT_RECORD_FILE              Optional local Redis fault drill record for cache-error attribution.
  PISCES_POST_RELEASE_SLO_OUTPUT_FILE         JSON review output. Default: target/pisces-runtime-post-release-slo-review/summary.json.
  PISCES_POST_RELEASE_MAX_ASSIGNMENT_ERROR_RATE   Default: 0
  PISCES_POST_RELEASE_MAX_CACHE_ERROR_DELTA        Default: 0
  PISCES_POST_RELEASE_MAX_BROADCAST_ERROR_DELTA    Default: 0
  PISCES_POST_RELEASE_MAX_BROADCAST_INVALID_DELTA  Default: 0
  PISCES_POST_RELEASE_MAX_LISTENER_ERROR_DELTA     Default: 0
  PISCES_POST_RELEASE_MAX_SDK_FAILURE_DELTA        Default: 0
  PISCES_POST_RELEASE_MAX_SDK_STALE_FALLBACK_DELTA Default: 0
  PISCES_POST_RELEASE_MAX_P95_MS                   Optional absolute P95 threshold.
  PISCES_POST_RELEASE_MAX_P99_MS                   Optional absolute P99 threshold.
  PISCES_POST_RELEASE_MAX_P95_BASELINE_RATIO       Default: 1.2 when baseline exists.
  PISCES_POST_RELEASE_MAX_P99_BASELINE_RATIO       Default: 1.2 when baseline exists.
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

  PISCES_RELEASE_EVIDENCE_MANIFEST_FILE="${PISCES_RELEASE_EVIDENCE_MANIFEST_FILE:-}"
  PISCES_POST_RELEASE_METRICS_FILE="${PISCES_POST_RELEASE_METRICS_FILE:-}"
  PISCES_POST_RELEASE_SLO_OUTPUT_FILE="${PISCES_POST_RELEASE_SLO_OUTPUT_FILE:-target/pisces-runtime-post-release-slo-review/summary.json}"

  [[ -n "$PISCES_RELEASE_EVIDENCE_MANIFEST_FILE" ]] || die "PISCES_RELEASE_EVIDENCE_MANIFEST_FILE is required"
  [[ -n "$PISCES_POST_RELEASE_METRICS_FILE" ]] || die "PISCES_POST_RELEASE_METRICS_FILE is required"
  [[ -f "$PISCES_RELEASE_EVIDENCE_MANIFEST_FILE" ]] || die "Release evidence manifest not found: $PISCES_RELEASE_EVIDENCE_MANIFEST_FILE"
  [[ -f "$PISCES_POST_RELEASE_METRICS_FILE" ]] || die "Post-release metrics file not found: $PISCES_POST_RELEASE_METRICS_FILE"
  if [[ -n "${PISCES_REDIS_FAULT_RECORD_FILE:-}" && ! -f "$PISCES_REDIS_FAULT_RECORD_FILE" ]]; then
    die "Redis fault record not found: $PISCES_REDIS_FAULT_RECORD_FILE"
  fi

  export PISCES_RELEASE_EVIDENCE_MANIFEST_FILE
  export PISCES_POST_RELEASE_METRICS_FILE
  export PISCES_REDIS_FAULT_RECORD_FILE="${PISCES_REDIS_FAULT_RECORD_FILE:-}"
  export PISCES_POST_RELEASE_MAX_ASSIGNMENT_ERROR_RATE="${PISCES_POST_RELEASE_MAX_ASSIGNMENT_ERROR_RATE:-0}"
  export PISCES_POST_RELEASE_MAX_CACHE_ERROR_DELTA="${PISCES_POST_RELEASE_MAX_CACHE_ERROR_DELTA:-0}"
  export PISCES_POST_RELEASE_MAX_BROADCAST_ERROR_DELTA="${PISCES_POST_RELEASE_MAX_BROADCAST_ERROR_DELTA:-0}"
  export PISCES_POST_RELEASE_MAX_BROADCAST_INVALID_DELTA="${PISCES_POST_RELEASE_MAX_BROADCAST_INVALID_DELTA:-0}"
  export PISCES_POST_RELEASE_MAX_LISTENER_ERROR_DELTA="${PISCES_POST_RELEASE_MAX_LISTENER_ERROR_DELTA:-0}"
  export PISCES_POST_RELEASE_MAX_SDK_FAILURE_DELTA="${PISCES_POST_RELEASE_MAX_SDK_FAILURE_DELTA:-0}"
  export PISCES_POST_RELEASE_MAX_SDK_STALE_FALLBACK_DELTA="${PISCES_POST_RELEASE_MAX_SDK_STALE_FALLBACK_DELTA:-0}"
  export PISCES_POST_RELEASE_MAX_P95_MS="${PISCES_POST_RELEASE_MAX_P95_MS:-}"
  export PISCES_POST_RELEASE_MAX_P99_MS="${PISCES_POST_RELEASE_MAX_P99_MS:-}"
  export PISCES_POST_RELEASE_MAX_P95_BASELINE_RATIO="${PISCES_POST_RELEASE_MAX_P95_BASELINE_RATIO:-1.2}"
  export PISCES_POST_RELEASE_MAX_P99_BASELINE_RATIO="${PISCES_POST_RELEASE_MAX_P99_BASELINE_RATIO:-1.2}"

  local output_file
  output_file="$(resolve_output_file "$PISCES_POST_RELEASE_SLO_OUTPUT_FILE")"
  mkdir -p "$(dirname "$output_file")"

  python3 - "$output_file" <<'PY'
import json
import os
import re
import sys
from datetime import datetime, timezone

output_file = sys.argv[1]


def read_json(path):
    try:
        with open(path, encoding="utf-8") as source:
            return json.load(source)
    except Exception as exc:
        raise SystemExit(f"Invalid JSON file {path}: {exc}") from exc


def as_number(value, field):
    if value is None or value == "":
        return None
    try:
        return float(value)
    except (TypeError, ValueError) as exc:
        raise SystemExit(f"{field} must be numeric: {value}") from exc


def required_number(payload, path):
    value = get_path(payload, path)
    number = as_number(value, path)
    if number is None:
        raise SystemExit(f"Missing numeric metric: {path}")
    return number


def get_path(payload, path, default=None):
    value = payload
    for part in path.split("."):
        if not isinstance(value, dict) or part not in value:
            return default
        value = value[part]
    return value


def add_gate(gates, name, actual, threshold, unit="", severity="FAIL"):
    passed = actual <= threshold
    gates.append({
        "name": name,
        "status": "PASS" if passed else severity,
        "actual": actual,
        "threshold": threshold,
        "unit": unit,
    })


def redis_fault_drill_passed(path):
    if not path:
        return False
    try:
        with open(path, encoding="utf-8") as source:
            text = source.read()
    except OSError:
        return False
    for phase in ("baseline", "during-fault", "recovery"):
        match = re.search(rf"^{re.escape(phase)}\s*:\s*(\S+)", text, re.IGNORECASE | re.MULTILINE)
        if not match or match.group(1).upper() != "PASS":
            return False
    return True


manifest = read_json(os.environ["PISCES_RELEASE_EVIDENCE_MANIFEST_FILE"])
metrics = read_json(os.environ["PISCES_POST_RELEASE_METRICS_FILE"])
gates = []

if manifest.get("manifestType") != "pisces-runtime-plane-release-evidence":
    raise SystemExit("Release evidence manifest type is invalid")

release_package = manifest.get("releasePackage") or {}
if release_package.get("status") != "PASS":
    gates.append({
        "name": "release_package_status",
        "status": "FAIL",
        "actual": release_package.get("status"),
        "threshold": "PASS",
        "unit": "",
    })
else:
    gates.append({
        "name": "release_package_status",
        "status": "PASS",
        "actual": "PASS",
        "threshold": "PASS",
        "unit": "",
    })

assignment_error_rate = required_number(metrics, "assignment.errorRate")
add_gate(
    gates,
    "assignment_error_rate",
    assignment_error_rate,
    as_number(os.environ["PISCES_POST_RELEASE_MAX_ASSIGNMENT_ERROR_RATE"], "PISCES_POST_RELEASE_MAX_ASSIGNMENT_ERROR_RATE"),
    "ratio",
)

post_p95_ms = required_number(metrics, "assignment.p95Ms")
post_p99_ms = required_number(metrics, "assignment.p99Ms")
absolute_p95 = as_number(os.environ["PISCES_POST_RELEASE_MAX_P95_MS"], "PISCES_POST_RELEASE_MAX_P95_MS")
absolute_p99 = as_number(os.environ["PISCES_POST_RELEASE_MAX_P99_MS"], "PISCES_POST_RELEASE_MAX_P99_MS")
capacity = manifest.get("capacityBaseline") or {}
baseline_p95 = as_number(capacity.get("maxP95Ms"), "capacityBaseline.maxP95Ms")
baseline_p99 = as_number(capacity.get("maxP99Ms"), "capacityBaseline.maxP99Ms")

if absolute_p95 is not None:
    add_gate(gates, "assignment_p95_absolute", post_p95_ms, absolute_p95, "ms")
elif baseline_p95 is not None:
    add_gate(
        gates,
        "assignment_p95_baseline_ratio",
        post_p95_ms,
        baseline_p95 * as_number(os.environ["PISCES_POST_RELEASE_MAX_P95_BASELINE_RATIO"], "PISCES_POST_RELEASE_MAX_P95_BASELINE_RATIO"),
        "ms",
    )
else:
    gates.append({
        "name": "assignment_p95_threshold_available",
        "status": "FAIL",
        "actual": "missing",
        "threshold": "absolute threshold or capacity baseline",
        "unit": "",
    })

if absolute_p99 is not None:
    add_gate(gates, "assignment_p99_absolute", post_p99_ms, absolute_p99, "ms")
elif baseline_p99 is not None:
    add_gate(
        gates,
        "assignment_p99_baseline_ratio",
        post_p99_ms,
        baseline_p99 * as_number(os.environ["PISCES_POST_RELEASE_MAX_P99_BASELINE_RATIO"], "PISCES_POST_RELEASE_MAX_P99_BASELINE_RATIO"),
        "ms",
    )
else:
    gates.append({
        "name": "assignment_p99_threshold_available",
        "status": "FAIL",
        "actual": "missing",
        "threshold": "absolute threshold or capacity baseline",
        "unit": "",
    })

cache_error_delta = required_number(metrics, "cache.errorDelta")
cache_error_threshold = as_number(
    os.environ["PISCES_POST_RELEASE_MAX_CACHE_ERROR_DELTA"],
    "PISCES_POST_RELEASE_MAX_CACHE_ERROR_DELTA",
)
if cache_error_delta > cache_error_threshold and redis_fault_drill_passed(os.environ.get("PISCES_REDIS_FAULT_RECORD_FILE")):
    gates.append({
        "name": "cache_error_delta",
        "status": "PASS",
        "actual": cache_error_delta,
        "threshold": "attributed to passing Redis fault drill",
        "unit": "count",
        "reason": "redisFaultRecord baseline/during-fault/recovery gates all passed",
    })
else:
    add_gate(
        gates,
        "cache_error_delta",
        cache_error_delta,
        cache_error_threshold,
        "count",
    )
add_gate(
    gates,
    "broadcast_publish_error_delta",
    required_number(metrics, "broadcast.publishErrorDelta"),
    as_number(os.environ["PISCES_POST_RELEASE_MAX_BROADCAST_ERROR_DELTA"], "PISCES_POST_RELEASE_MAX_BROADCAST_ERROR_DELTA"),
    "count",
)
add_gate(
    gates,
    "broadcast_invalid_delta",
    required_number(metrics, "broadcast.invalidDelta"),
    as_number(os.environ["PISCES_POST_RELEASE_MAX_BROADCAST_INVALID_DELTA"], "PISCES_POST_RELEASE_MAX_BROADCAST_INVALID_DELTA"),
    "count",
)
add_gate(
    gates,
    "broadcast_listener_error_delta",
    required_number(metrics, "broadcast.listenerErrorDelta"),
    as_number(os.environ["PISCES_POST_RELEASE_MAX_LISTENER_ERROR_DELTA"], "PISCES_POST_RELEASE_MAX_LISTENER_ERROR_DELTA"),
    "count",
)
add_gate(
    gates,
    "sdk_request_failure_delta",
    required_number(metrics, "sdk.requestFailureDelta"),
    as_number(os.environ["PISCES_POST_RELEASE_MAX_SDK_FAILURE_DELTA"], "PISCES_POST_RELEASE_MAX_SDK_FAILURE_DELTA"),
    "count",
)
add_gate(
    gates,
    "sdk_stale_fallback_delta",
    required_number(metrics, "sdk.staleFallbackDelta"),
    as_number(os.environ["PISCES_POST_RELEASE_MAX_SDK_STALE_FALLBACK_DELTA"], "PISCES_POST_RELEASE_MAX_SDK_STALE_FALLBACK_DELTA"),
    "count",
)

failed_gates = [gate for gate in gates if gate["status"] == "FAIL"]
warning_gates = [gate for gate in gates if gate["status"] == "WARN"]
status = "FAIL" if failed_gates else "WARN" if warning_gates else "PASS"

summary = {
    "summaryType": "pisces-runtime-plane-post-release-slo-review",
    "summaryVersion": 1,
    "status": status,
    "generatedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "releaseId": manifest.get("releaseId"),
    "environment": manifest.get("environment"),
    "releaseEvidenceManifest": os.environ["PISCES_RELEASE_EVIDENCE_MANIFEST_FILE"],
    "postReleaseMetrics": os.environ["PISCES_POST_RELEASE_METRICS_FILE"],
    "redisFaultRecord": os.environ.get("PISCES_REDIS_FAULT_RECORD_FILE") or None,
    "window": metrics.get("window", {}),
    "baseline": {
        "maxP95Ms": baseline_p95,
        "maxP99Ms": baseline_p99,
    },
    "gates": gates,
}

with open(output_file, "w", encoding="utf-8") as target:
    json.dump(summary, target, ensure_ascii=False, indent=2, sort_keys=True)
    target.write("\n")

if status != "PASS":
    print(f"Post-release SLO review failed: status={status}", file=sys.stderr)
    sys.exit(1)
PY

  log "Post-release SLO review written to ${output_file}"
}

main "$@"
