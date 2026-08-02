#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  PISCES_EXPERIMENT_IDS=exp_a,exp_b scripts/runtime-plane-experiment-impact-sampling.sh

Environment:
  PISCES_INSTANCE_URLS                         Comma separated service base URLs. Default: http://localhost:9990/api
  PISCES_EXPERIMENT_IDS                        Required comma separated experiment IDs.
  PISCES_RUNTIME_API_KEY                       Runtime scope API key. Default: runtime-key
  PISCES_IMPACT_OUTPUT_FILE                    JSON report output. Default: target/pisces-runtime-experiment-impact-sampling/summary.json.
  PISCES_IMPACT_VERSION_WAIT_MILLIS            Runtime version check waitMillis. Default: 0.
  PISCES_IMPACT_EXPECTED_STATUS                Optional expected runtime experiment status.
  PISCES_IMPACT_EXPECTED_CONFIG_VERSIONS       Optional expected versions, e.g. exp_a:12,exp_b:19.
  PISCES_IMPACT_MIN_GROUP_COUNT                Minimum runtime config group count. Default: 1.
  PISCES_IMPACT_TRACE_ENABLED                  Call /traffic/assign/trace with synthetic visitors. Default: false.
  PISCES_IMPACT_VISITOR_COUNT                  Trace visitor count per experiment when trace is enabled. Default: 20.
  PISCES_IMPACT_MIN_TRACE_GROUP_COUNT          Minimum assigned group coverage when trace is enabled. Default: 1.
  PISCES_IMPACT_MAX_ERROR_RATE                 Maximum trace request error rate. Default: 0.
  PISCES_VISITOR_PREFIX                        Synthetic visitor ID prefix. Default: impact-<epoch>.
  PISCES_IMPACT_TIMEOUT_SECONDS                HTTP timeout seconds. Default: 10.
  PISCES_ENVIRONMENT                           Evidence environment. Default: local.
  PISCES_RELEASE_ID                            Optional release ID written to the report.
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

  PISCES_INSTANCE_URLS="${PISCES_INSTANCE_URLS:-http://localhost:9990/api}"
  PISCES_EXPERIMENT_IDS="${PISCES_EXPERIMENT_IDS:-${PISCES_EXPERIMENT_ID:-}}"
  PISCES_RUNTIME_API_KEY="${PISCES_RUNTIME_API_KEY:-runtime-key}"
  PISCES_IMPACT_OUTPUT_FILE="${PISCES_IMPACT_OUTPUT_FILE:-target/pisces-runtime-experiment-impact-sampling/summary.json}"
  PISCES_IMPACT_VERSION_WAIT_MILLIS="${PISCES_IMPACT_VERSION_WAIT_MILLIS:-0}"
  PISCES_IMPACT_EXPECTED_STATUS="${PISCES_IMPACT_EXPECTED_STATUS:-}"
  PISCES_IMPACT_EXPECTED_CONFIG_VERSIONS="${PISCES_IMPACT_EXPECTED_CONFIG_VERSIONS:-}"
  PISCES_IMPACT_MIN_GROUP_COUNT="${PISCES_IMPACT_MIN_GROUP_COUNT:-1}"
  PISCES_IMPACT_TRACE_ENABLED="${PISCES_IMPACT_TRACE_ENABLED:-false}"
  PISCES_IMPACT_VISITOR_COUNT="${PISCES_IMPACT_VISITOR_COUNT:-20}"
  PISCES_IMPACT_MIN_TRACE_GROUP_COUNT="${PISCES_IMPACT_MIN_TRACE_GROUP_COUNT:-1}"
  PISCES_IMPACT_MAX_ERROR_RATE="${PISCES_IMPACT_MAX_ERROR_RATE:-0}"
  PISCES_VISITOR_PREFIX="${PISCES_VISITOR_PREFIX:-impact-$(date +%s)}"
  PISCES_IMPACT_TIMEOUT_SECONDS="${PISCES_IMPACT_TIMEOUT_SECONDS:-10}"
  PISCES_ENVIRONMENT="${PISCES_ENVIRONMENT:-local}"
  PISCES_RELEASE_ID="${PISCES_RELEASE_ID:-}"

  [[ -n "$PISCES_EXPERIMENT_IDS" ]] || die "PISCES_EXPERIMENT_IDS is required"

  export PISCES_INSTANCE_URLS
  export PISCES_EXPERIMENT_IDS
  export PISCES_RUNTIME_API_KEY
  export PISCES_IMPACT_VERSION_WAIT_MILLIS
  export PISCES_IMPACT_EXPECTED_STATUS
  export PISCES_IMPACT_EXPECTED_CONFIG_VERSIONS
  export PISCES_IMPACT_MIN_GROUP_COUNT
  export PISCES_IMPACT_TRACE_ENABLED
  export PISCES_IMPACT_VISITOR_COUNT
  export PISCES_IMPACT_MIN_TRACE_GROUP_COUNT
  export PISCES_IMPACT_MAX_ERROR_RATE
  export PISCES_VISITOR_PREFIX
  export PISCES_IMPACT_TIMEOUT_SECONDS
  export PISCES_ENVIRONMENT
  export PISCES_RELEASE_ID

  local output_file
  output_file="$(resolve_output_file "$PISCES_IMPACT_OUTPUT_FILE")"
  mkdir -p "$(dirname "$output_file")"

  python3 - "$output_file" <<'PY'
import json
import os
import sys
from datetime import datetime, timezone
from urllib import error, parse, request

output_file = sys.argv[1]


def now_iso():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_csv(value, field):
    items = [item.strip() for item in value.split(",") if item.strip()]
    if not items:
        raise SystemExit(f"{field} is empty")
    return items


def parse_bool(value, field):
    normalized = value.strip().lower()
    if normalized in {"1", "true", "yes", "y"}:
        return True
    if normalized in {"0", "false", "no", "n"}:
        return False
    raise SystemExit(f"{field} must be boolean: {value}")


def parse_int(value, field, minimum=None):
    try:
        parsed = int(value)
    except ValueError as exc:
        raise SystemExit(f"{field} must be integer: {value}") from exc
    if minimum is not None and parsed < minimum:
        raise SystemExit(f"{field} must be >= {minimum}: {value}")
    return parsed


def parse_float(value, field, minimum=None):
    try:
        parsed = float(value)
    except ValueError as exc:
        raise SystemExit(f"{field} must be numeric: {value}") from exc
    if minimum is not None and parsed < minimum:
        raise SystemExit(f"{field} must be >= {minimum}: {value}")
    return parsed


def parse_expected_versions(value):
    result = {}
    if not value.strip():
        return result
    for item in value.split(","):
        item = item.strip()
        if not item:
            continue
        separator = ":" if ":" in item else "="
        if separator not in item:
            raise SystemExit(
                "PISCES_IMPACT_EXPECTED_CONFIG_VERSIONS entries must use exp:version"
            )
        experiment_id, version = [part.strip() for part in item.split(separator, 1)]
        if not experiment_id:
            raise SystemExit(
                "PISCES_IMPACT_EXPECTED_CONFIG_VERSIONS contains empty experiment ID"
            )
        result[experiment_id] = parse_int(
            version,
            f"PISCES_IMPACT_EXPECTED_CONFIG_VERSIONS[{experiment_id}]",
            minimum=0,
        )
    return result


def normalize_urls(value):
    return [url.rstrip("/") for url in parse_csv(value, "PISCES_INSTANCE_URLS")]


def build_url(base_url, path, query=None):
    url = f"{base_url}{path}"
    if query:
        url = f"{url}?{parse.urlencode(query)}"
    return url


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
    body = None
    headers = {"X-Pisces-Api-Key": api_key}
    if payload is not None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"

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


def summarize_config(data):
    if not isinstance(data, dict):
        return {
            "id": None,
            "status": None,
            "configVersion": None,
            "groupCount": 0,
            "groupIds": [],
            "allocation": [],
            "strategy": None,
            "hashKey": None,
            "totalTraffic": None,
            "eventDefinitionCount": 0,
            "metricDefinitionCount": 0,
            "groupConfigSchemaCount": 0,
        }

    groups = data.get("groups") or {}
    if isinstance(groups, dict):
        group_ids = sorted(str(group_id) for group_id in groups.keys())
    elif isinstance(groups, list):
        group_ids = [
            str(group.get("id"))
            for group in groups
            if isinstance(group, dict) and group.get("id") is not None
        ]
    else:
        group_ids = []

    traffic = data.get("traffic") or {}
    allocation = traffic.get("allocation") if isinstance(traffic, dict) else []
    if not isinstance(allocation, list):
        allocation = []
    compact_allocation = []
    for item in allocation:
        if isinstance(item, dict):
            compact_allocation.append({
                "group": item.get("group"),
                "ratio": item.get("ratio"),
            })

    return {
        "id": data.get("id"),
        "status": data.get("status"),
        "configVersion": data.get("configVersion"),
        "groupCount": len(group_ids),
        "groupIds": group_ids,
        "allocation": compact_allocation,
        "strategy": traffic.get("strategy") if isinstance(traffic, dict) else None,
        "hashKey": traffic.get("hashKey") if isinstance(traffic, dict) else None,
        "totalTraffic": traffic.get("totalTraffic") if isinstance(traffic, dict) else None,
        "eventDefinitionCount": len(data.get("eventDefinitions") or []),
        "metricDefinitionCount": len(data.get("metricDefinitions") or []),
        "groupConfigSchemaCount": len(data.get("groupConfigSchema") or []),
    }


def summarize_version(data):
    if not isinstance(data, dict):
        return {
            "experimentId": None,
            "knownVersion": None,
            "currentVersion": None,
            "changed": None,
            "status": None,
            "generatedAt": None,
        }
    return {
        "experimentId": data.get("experimentId"),
        "knownVersion": data.get("knownVersion"),
        "currentVersion": data.get("currentVersion"),
        "changed": data.get("changed"),
        "status": data.get("status"),
        "generatedAt": data.get("generatedAt"),
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


def count_values(values):
    result = {}
    for value in values:
        key = str(value) if value is not None else "null"
        result[key] = result.get(key, 0) + 1
    return dict(sorted(result.items()))


urls = normalize_urls(os.environ["PISCES_INSTANCE_URLS"])
experiment_ids = parse_csv(os.environ["PISCES_EXPERIMENT_IDS"], "PISCES_EXPERIMENT_IDS")
api_key = os.environ["PISCES_RUNTIME_API_KEY"]
version_wait_millis = parse_int(
    os.environ["PISCES_IMPACT_VERSION_WAIT_MILLIS"],
    "PISCES_IMPACT_VERSION_WAIT_MILLIS",
    minimum=0,
)
expected_status = os.environ["PISCES_IMPACT_EXPECTED_STATUS"].strip()
expected_versions = parse_expected_versions(
    os.environ["PISCES_IMPACT_EXPECTED_CONFIG_VERSIONS"]
)
min_group_count = parse_int(
    os.environ["PISCES_IMPACT_MIN_GROUP_COUNT"],
    "PISCES_IMPACT_MIN_GROUP_COUNT",
    minimum=0,
)
trace_enabled = parse_bool(
    os.environ["PISCES_IMPACT_TRACE_ENABLED"],
    "PISCES_IMPACT_TRACE_ENABLED",
)
visitor_count = parse_int(
    os.environ["PISCES_IMPACT_VISITOR_COUNT"],
    "PISCES_IMPACT_VISITOR_COUNT",
    minimum=0,
)
min_trace_group_count = parse_int(
    os.environ["PISCES_IMPACT_MIN_TRACE_GROUP_COUNT"],
    "PISCES_IMPACT_MIN_TRACE_GROUP_COUNT",
    minimum=0,
)
max_error_rate = parse_float(
    os.environ["PISCES_IMPACT_MAX_ERROR_RATE"],
    "PISCES_IMPACT_MAX_ERROR_RATE",
    minimum=0,
)
visitor_prefix = os.environ["PISCES_VISITOR_PREFIX"]
timeout_seconds = parse_float(
    os.environ["PISCES_IMPACT_TIMEOUT_SECONDS"],
    "PISCES_IMPACT_TIMEOUT_SECONDS",
    minimum=0.1,
)

gates = []
experiments = []

for experiment_id in experiment_ids:
    encoded_experiment_id = parse.quote(experiment_id, safe="")
    experiment = {
        "experimentId": experiment_id,
        "instances": [],
        "traceSummary": None,
    }

    for base_url in urls:
        config_url = build_url(
            base_url,
            f"/runtime/experiments/{encoded_experiment_id}/config",
        )
        config_request = request_json("GET", config_url, api_key, timeout_seconds)
        config = summarize_config(config_request["data"]) if config_request["ok"] else summarize_config(None)

        version_request = None
        version = summarize_version(None)
        if config_request["ok"] and config["configVersion"] is not None:
            version_url = build_url(
                base_url,
                f"/runtime/experiments/{encoded_experiment_id}/config/version",
                {
                    "knownVersion": config["configVersion"],
                    "waitMillis": version_wait_millis,
                },
            )
            version_request = request_json("GET", version_url, api_key, timeout_seconds)
            version = summarize_version(version_request["data"]) if version_request["ok"] else summarize_version(None)

        experiment["instances"].append({
            "baseUrl": base_url,
            "config": config,
            "version": version,
            "requests": {
                "config": compact_request_result(config_request),
                "version": compact_request_result(version_request) if version_request else None,
            },
        })

    experiments.append(experiment)

for experiment in experiments:
    experiment_id = experiment["experimentId"]
    config_failures = [
        instance["baseUrl"]
        for instance in experiment["instances"]
        if not instance["requests"]["config"]["ok"]
    ]
    add_gate(
        gates,
        f"{experiment_id}:config_request_success",
        "PASS" if not config_failures else "FAIL",
        actual=0 if not config_failures else len(config_failures),
        threshold=0,
        details={"failedBaseUrls": config_failures} if config_failures else None,
    )

    version_failures = [
        instance["baseUrl"]
        for instance in experiment["instances"]
        if instance["requests"]["version"] is None
        or not instance["requests"]["version"]["ok"]
    ]
    add_gate(
        gates,
        f"{experiment_id}:version_request_success",
        "PASS" if not version_failures else "FAIL",
        actual=0 if not version_failures else len(version_failures),
        threshold=0,
        details={"failedBaseUrls": version_failures} if version_failures else None,
    )

    version_mismatches = []
    for instance in experiment["instances"]:
        config_version = instance["config"]["configVersion"]
        current_version = instance["version"]["currentVersion"]
        if config_version != current_version:
            version_mismatches.append({
                "baseUrl": instance["baseUrl"],
                "configVersion": config_version,
                "currentVersion": current_version,
            })
    add_gate(
        gates,
        f"{experiment_id}:version_matches_config",
        "PASS" if not version_mismatches else "FAIL",
        actual=0 if not version_mismatches else len(version_mismatches),
        threshold=0,
        details={"mismatches": version_mismatches} if version_mismatches else None,
    )

    versions = [
        instance["config"]["configVersion"]
        for instance in experiment["instances"]
        if instance["config"]["configVersion"] is not None
    ]
    unique_versions = sorted({version for version in versions})
    add_gate(
        gates,
        f"{experiment_id}:multi_instance_config_convergence",
        "PASS" if len(unique_versions) <= 1 and len(versions) == len(urls) else "FAIL",
        actual=unique_versions,
        threshold="single configVersion across all instances",
    )

    group_failures = [
        {
            "baseUrl": instance["baseUrl"],
            "groupCount": instance["config"]["groupCount"],
        }
        for instance in experiment["instances"]
        if instance["config"]["groupCount"] < min_group_count
    ]
    add_gate(
        gates,
        f"{experiment_id}:runtime_config_group_count",
        "PASS" if not group_failures else "FAIL",
        actual=0 if not group_failures else len(group_failures),
        threshold=f"every instance groupCount >= {min_group_count}",
        details={"failedBaseUrls": group_failures} if group_failures else None,
    )

    if expected_status:
        status_failures = [
            {
                "baseUrl": instance["baseUrl"],
                "status": instance["config"]["status"],
            }
            for instance in experiment["instances"]
            if instance["config"]["status"] != expected_status
        ]
        add_gate(
            gates,
            f"{experiment_id}:expected_status",
            "PASS" if not status_failures else "FAIL",
            actual=0 if not status_failures else len(status_failures),
            threshold=expected_status,
            details={"failedBaseUrls": status_failures} if status_failures else None,
        )

    if experiment_id in expected_versions:
        expected_version = expected_versions[experiment_id]
        expected_failures = [
            {
                "baseUrl": instance["baseUrl"],
                "configVersion": instance["config"]["configVersion"],
            }
            for instance in experiment["instances"]
            if instance["config"]["configVersion"] != expected_version
        ]
        add_gate(
            gates,
            f"{experiment_id}:expected_config_version",
            "PASS" if not expected_failures else "FAIL",
            actual=0 if not expected_failures else len(expected_failures),
            threshold=expected_version,
            details={"failedBaseUrls": expected_failures} if expected_failures else None,
        )

if trace_enabled:
    for experiment in experiments:
        experiment_id = experiment["experimentId"]
        encoded_experiment_id = parse.quote(experiment_id, safe="")
        known_versions = {
            instance["baseUrl"]: instance["config"]["configVersion"]
            for instance in experiment["instances"]
            if instance["config"]["configVersion"] is not None
        }
        samples = []
        for index in range(visitor_count):
            base_url = urls[index % len(urls)]
            visitor_id = f"{visitor_prefix}-{experiment_id}-{index}"
            trace_url = build_url(base_url, "/traffic/assign/trace")
            payload = {
                "experimentId": experiment_id,
                "visitorId": visitor_id,
                "attributes": {
                    "source": "runtime-impact-sampling",
                    "sequence": index,
                },
            }
            trace_request = request_json(
                "POST",
                trace_url,
                api_key,
                timeout_seconds,
                payload=payload,
            )
            data = trace_request["data"] if isinstance(trace_request["data"], dict) else {}
            samples.append({
                "baseUrl": base_url,
                "visitorId": visitor_id,
                "ok": trace_request["ok"],
                "groupId": data.get("groupId"),
                "assigned": data.get("assigned"),
                "reason": data.get("reason"),
                "source": data.get("source"),
                "strategy": data.get("strategy"),
                "configVersion": data.get("configVersion"),
                "request": compact_request_result(trace_request),
            })

        failed_samples = [sample for sample in samples if not sample["ok"]]
        successful_samples = [sample for sample in samples if sample["ok"]]
        assigned_groups = [
            sample["groupId"]
            for sample in successful_samples
            if sample["assigned"] is True and sample["groupId"] is not None
        ]
        trace_versions = [
            sample["configVersion"]
            for sample in successful_samples
            if sample["configVersion"] is not None
        ]
        version_failures = []
        for sample in successful_samples:
            expected_version = known_versions.get(sample["baseUrl"])
            if expected_version is not None and sample["configVersion"] != expected_version:
                version_failures.append({
                    "baseUrl": sample["baseUrl"],
                    "visitorId": sample["visitorId"],
                    "traceConfigVersion": sample["configVersion"],
                    "runtimeConfigVersion": expected_version,
                })

        error_rate = len(failed_samples) / visitor_count if visitor_count else 0.0
        trace_summary = {
            "enabled": True,
            "visitorCount": visitor_count,
            "failed": len(failed_samples),
            "errorRate": error_rate,
            "assigned": sum(1 for sample in successful_samples if sample["assigned"] is True),
            "blocked": sum(1 for sample in successful_samples if sample["assigned"] is not True),
            "groupCounts": count_values(assigned_groups),
            "reasonCounts": count_values(sample["reason"] for sample in successful_samples),
            "sourceCounts": count_values(sample["source"] for sample in successful_samples),
            "configVersionCounts": count_values(trace_versions),
            "samples": samples,
        }
        experiment["traceSummary"] = trace_summary

        add_gate(
            gates,
            f"{experiment_id}:trace_request_error_rate",
            "PASS" if error_rate <= max_error_rate else "FAIL",
            actual=error_rate,
            threshold=max_error_rate,
            details={
                "failedSamples": [
                    {
                        "baseUrl": sample["baseUrl"],
                        "visitorId": sample["visitorId"],
                        "error": sample["request"]["error"],
                    }
                    for sample in failed_samples
                ]
            } if failed_samples else None,
        )
        add_gate(
            gates,
            f"{experiment_id}:trace_config_version_matches_runtime_config",
            "PASS" if not version_failures else "FAIL",
            actual=0 if not version_failures else len(version_failures),
            threshold=0,
            details={"mismatches": version_failures} if version_failures else None,
        )
        add_gate(
            gates,
            f"{experiment_id}:trace_group_coverage",
            "PASS" if len(set(assigned_groups)) >= min_trace_group_count else "FAIL",
            actual=len(set(assigned_groups)),
            threshold=min_trace_group_count,
        )
else:
    add_gate(
        gates,
        "trace_sampling_enabled",
        "SKIP",
        actual=False,
        threshold=True,
        details={
            "reason": "Set PISCES_IMPACT_TRACE_ENABLED=true to sample /traffic/assign/trace."
        },
    )
    for experiment in experiments:
        experiment["traceSummary"] = {"enabled": False}

status = "FAIL" if any(gate["status"] == "FAIL" for gate in gates) else "PASS"
report = {
    "reportType": "pisces-runtime-plane-experiment-impact-sampling",
    "status": status,
    "generatedAt": now_iso(),
    "environment": os.environ["PISCES_ENVIRONMENT"],
    "releaseId": os.environ["PISCES_RELEASE_ID"] or None,
    "instanceUrls": urls,
    "experimentIds": experiment_ids,
    "traceEnabled": trace_enabled,
    "visitorCount": visitor_count if trace_enabled else 0,
    "visitorPrefix": visitor_prefix if trace_enabled else None,
    "versionWaitMillis": version_wait_millis,
    "expectations": {
        "status": expected_status or None,
        "configVersions": expected_versions,
    },
    "thresholds": {
        "minGroupCount": min_group_count,
        "minTraceGroupCount": min_trace_group_count,
        "maxTraceErrorRate": max_error_rate,
    },
    "gates": gates,
    "experiments": experiments,
}

with open(output_file, "w", encoding="utf-8") as target:
    json.dump(report, target, ensure_ascii=False, indent=2, sort_keys=True)
    target.write("\n")

print(f"Impact sampling report written: {output_file} status={status}", file=sys.stderr)
if status != "PASS":
    sys.exit(1)
PY
}

main "$@"
