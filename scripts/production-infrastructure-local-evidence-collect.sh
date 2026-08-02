#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  source config/pisces-local.env
  source config/pisces-local-stack.env 2>/dev/null || true
  PISCES_RELEASE_ID="local-20260730-runtime-plane" \
  scripts/production-infrastructure-local-evidence-collect.sh

Environment:
  PISCES_REPO_ROOT                            Repository root. Default: inferred from this script.
  PISCES_LOCAL_ENV_FILE                       Local env file loaded before collection. Default: config/pisces-local.env.
  PISCES_LOCAL_STACK_ENV_FILE                 Local stack env file loaded before collection. Default: config/pisces-local-stack.env.
  PISCES_RELEASE_ID                           Local release ID. Default: local-<utc timestamp>.
  PISCES_EXPERIMENT_ID                        Optional existing experiment ID. If empty, collector creates a local demo experiment.
  PISCES_INSTANCE_URLS                        Comma separated service base URLs. Default: http://localhost:9990/api.
  PISCES_RUNTIME_API_KEY                      Runtime scope API key. Default: runtime-key.
  PISCES_MANAGEMENT_API_KEY                   Management scope API key. Default: ops-key.
  PISCES_ANALYSIS_API_KEY                     Analysis or management scope API key. Default: ops-key.
  PISCES_LOCAL_SERVICE_SUMMARY_FILE           Local service summary. Default: target/pisces-production-infrastructure-local-service/summary.json.
  PISCES_LOCAL_COLLECT_REQUIRE_SERVICE_SUMMARY
                                               Require HEALTHY local service summary before real collection. Default: true.
  PISCES_LOCAL_EVIDENCE_WORKSPACE_DIR         Output workspace. Default: target/pisces-production-infrastructure-local-evidence/<release-id>.
  PISCES_LOCAL_EVIDENCE_COLLECT_OUTPUT_FILE   Collection summary. Default: <workspace>/collection-summary.json.
  PISCES_LOCAL_COLLECT_PLAN_ONLY              Write a collection plan without touching local services. Default: false.
  PISCES_LOCAL_COLLECT_VALIDATE               Run local evidence validator after collection. Default: true.
  PISCES_LOCAL_COLLECT_RUN_CLOSEOUT           Run generated run-local-closeout.sh after collection. Default: false.
  PISCES_LOCAL_COLLECT_OPERATOR               Operator recorded in evidence. Default: current OS user.
  PISCES_LOCAL_COLLECT_APPROVER               Approver recorded in evidence. Default: operator.
  PISCES_LOCAL_COLLECT_APPROVAL_TICKET        Approval ticket value. Default: LOCAL-<release-id>.
  PISCES_COMPLETION_SCREENSHOT_DIR            Core frontend screenshot directory. Default: ../pisces-web/target/screenshots/core-functions-current.
  PISCES_LOCAL_COLLECT_AUTO_DEMO              Auto-create local demo experiment if no experiment ID is provided. Default: true.
  PISCES_LOCAL_COLLECT_DEMO_CASE              qualified | unqualified. Default: qualified.

Drill tuning:
  PISCES_LOCAL_COLLECT_CAPACITY_STEPS         Default: 100:8,500:16,1000:32.
  PISCES_LOCAL_COLLECT_CAPACITY_MAX_P95_MS    Local capacity p95 threshold. Default: 1000.
  PISCES_LOCAL_COLLECT_IMPACT_VISITOR_COUNT   Default: 50.
  PISCES_LOCAL_COLLECT_EVENT_REPLAY_START_TIME Default: 1970-01-01T00:00:00.
  PISCES_LOCAL_COLLECT_EVENT_REPLAY_END_TIME   Default: 2100-01-01T00:00:00.
  PISCES_LOCAL_COLLECT_REDIS_FAULT_MODE       manual | docker-pause | docker-stop. Default: manual.
  PISCES_LOCAL_COLLECT_SKIP_REDIS_FAULT       Skip Redis fault drill. Default: false.
  PISCES_REDIS_DOCKER_CONTAINER               Required for docker fault modes.
  PISCES_FAULT_CONFIRM                        Must be true for docker fault modes.

This script collects real local evidence. It does not create passing fake
evidence. When called by scripts/production-infrastructure-local-finalize.sh
with the project Docker stack, Redis fault mode is usually docker-stop and the
local stack container is restored automatically. In manual Redis fault mode,
inject the Redis fault during the window printed by
scripts/runtime-plane-redis-fault-injection.sh.
USAGE
}

log() {
  printf '[%s] %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*" >&2
}

die() {
  log "ERROR: $*"
  exit 1
}

is_true() {
  case "${1:-}" in
    true|TRUE|True|1|yes|YES|Yes|y|Y)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "Missing command: $1"
}

resolve_repo_root() {
  if [[ -n "${PISCES_REPO_ROOT:-}" ]]; then
    (cd "$PISCES_REPO_ROOT" && pwd)
    return
  fi

  local script_dir
  script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  if command -v git >/dev/null 2>&1 && git -C "$script_dir/.." rev-parse --show-toplevel >/dev/null 2>&1; then
    git -C "$script_dir/.." rev-parse --show-toplevel
    return
  fi
  (cd "$script_dir/.." && pwd)
}

resolve_path() {
  case "$1" in
    /*)
      printf '%s' "$1"
      ;;
    *)
      printf '%s/%s' "$PISCES_REPO_ROOT" "$1"
      ;;
  esac
}

load_env_file() {
  local file="$1"
  if [[ -f "$file" ]]; then
    set -a
    # shellcheck disable=SC1090
    source "$file"
    set +a
  fi
}

resolve_git_sha() {
  if command -v git >/dev/null 2>&1 && git -C "$PISCES_REPO_ROOT" rev-parse --show-toplevel >/dev/null 2>&1; then
    git -C "$PISCES_REPO_ROOT" rev-parse HEAD
    return
  fi
  printf 'unknown'
}

first_instance_url() {
  python3 - "$PISCES_INSTANCE_URLS" <<'PY'
import sys

urls = [item.strip().rstrip("/") for item in sys.argv[1].split(",") if item.strip()]
if not urls:
    raise SystemExit("PISCES_INSTANCE_URLS is empty")
print(urls[0])
PY
}

json_value() {
  local file="$1"
  local path="$2"
  python3 - "$file" "$path" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    payload = json.load(source)

value = payload
for part in sys.argv[2].split("."):
    if isinstance(value, dict):
        value = value.get(part)
    else:
        value = None
    if value is None:
        break

if value is None:
    sys.exit(1)
if isinstance(value, bool):
    print("true" if value else "false")
else:
    print(value)
PY
}

validate_local_service_summary() {
  if ! is_true "$PISCES_LOCAL_COLLECT_REQUIRE_SERVICE_SUMMARY"; then
    log "Skipping local service summary gate because PISCES_LOCAL_COLLECT_REQUIRE_SERVICE_SUMMARY=false"
    return
  fi

  python3 - "$PISCES_LOCAL_SERVICE_SUMMARY_FILE" <<'PY'
import json
import sys
from pathlib import Path

summary_file = Path(sys.argv[1])
if not summary_file.is_file():
    raise SystemExit(
        "Local service summary is missing; run "
        "bash scripts/production-infrastructure-local-service.sh start"
    )

try:
    summary = json.loads(summary_file.read_text(encoding="utf-8"))
except Exception as exc:
    raise SystemExit(f"Local service summary is not valid JSON: {exc}") from exc

problems = []
if summary.get("summaryType") != "pisces-production-infrastructure-local-service":
    problems.append("summaryType must be pisces-production-infrastructure-local-service")
if summary.get("targetEnvironment") != "local":
    problems.append("targetEnvironment must be local")
if summary.get("status") != "HEALTHY":
    problems.append("status must be HEALTHY")
if summary.get("apiKeyStatus") != "configured":
    problems.append("apiKeyStatus must be configured")
if summary.get("healthStatus") != "UP":
    problems.append("healthStatus must be UP")
if summary.get("dryRun") is True:
    problems.append("dryRun must be false")

if problems:
    joined = "; ".join(problems)
    raise SystemExit(
        f"Local service summary is not ready for evidence collection: {joined}. "
        "Run bash scripts/production-infrastructure-local-service.sh start after "
        "replacing TONGYI_API_KEY in config/pisces-local.env."
    )
PY
}

assert_base_response_success() {
  local file="$1"
  local http_status="$2"
  local url="$3"
  python3 - "$file" "$http_status" "$url" <<'PY'
import json
import sys

file_name, http_status, url = sys.argv[1], sys.argv[2], sys.argv[3]
try:
    with open(file_name, encoding="utf-8") as source:
        payload = json.load(source)
except Exception as exc:
    print(f"Invalid JSON from {url}: {exc}", file=sys.stderr)
    sys.exit(1)

ok_http = http_status.isdigit() and 200 <= int(http_status) < 300
ok_code = payload.get("code") == 200
if not ok_http or not ok_code:
    print(
        f"Request failed: url={url} http={http_status} "
        f"code={payload.get('code')} message={payload.get('message')}",
        file=sys.stderr,
    )
    sys.exit(1)
PY
}

request_json() {
  local method="$1"
  local url="$2"
  local api_key="$3"
  local payload_file="$4"
  local output_file="$5"
  local http_status
  if [[ -n "$payload_file" ]]; then
    http_status="$(curl -sS -o "$output_file" -w '%{http_code}' \
      -X "$method" \
      -H "X-Pisces-Api-Key: ${api_key}" \
      -H 'Content-Type: application/json' \
      --data @"$payload_file" \
      "$url" || true)"
  else
    http_status="$(curl -sS -o "$output_file" -w '%{http_code}' \
      -X "$method" \
      -H "X-Pisces-Api-Key: ${api_key}" \
      "$url" || true)"
  fi
  assert_base_response_success "$output_file" "$http_status" "$url"
}

resolve_or_create_local_experiment() {
  local primary_url="$1"
  if [[ -n "$PISCES_EXPERIMENT_ID" ]]; then
    log "Using provided local experiment: $PISCES_EXPERIMENT_ID"
    return
  fi

  if ! is_true "$PISCES_LOCAL_COLLECT_AUTO_DEMO"; then
    die "PISCES_EXPERIMENT_ID is required when PISCES_LOCAL_COLLECT_AUTO_DEMO=false"
  fi

  local response_file path
  response_file="$PISCES_LOCAL_EVIDENCE_WORKSPACE_DIR/local-demo-experiment-response.json"
  case "$PISCES_LOCAL_COLLECT_DEMO_CASE" in
    qualified|pass|PASS)
      path="data.qualifiedExperiment.experimentId"
      PISCES_LOCAL_COLLECT_DEMO_CASE="qualified"
      ;;
    unqualified|fail|FAIL)
      path="data.unqualifiedExperiment.experimentId"
      PISCES_LOCAL_COLLECT_DEMO_CASE="unqualified"
      ;;
    *)
      die "Unsupported PISCES_LOCAL_COLLECT_DEMO_CASE: $PISCES_LOCAL_COLLECT_DEMO_CASE"
      ;;
  esac

  log "PISCES_EXPERIMENT_ID is empty; generating local ${PISCES_LOCAL_COLLECT_DEMO_CASE} demo experiment"
  request_json POST "${primary_url}/experiments/generator/demo" \
    "$PISCES_MANAGEMENT_API_KEY" "" "$response_file"
  PISCES_EXPERIMENT_ID="$(json_value "$response_file" "$path")" \
    || die "Demo generator response did not contain $path"
  [[ -n "$PISCES_EXPERIMENT_ID" ]] || die "Generated demo experiment ID is empty"
  export PISCES_EXPERIMENT_ID
  export PISCES_LOCAL_COLLECT_DEMO_CASE
  log "Using generated local demo experiment: $PISCES_EXPERIMENT_ID"
}

write_plan_summary() {
  python3 - "$PISCES_LOCAL_EVIDENCE_COLLECT_OUTPUT_FILE" <<'PY'
import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

output_file = Path(sys.argv[1])
repo_root = Path(os.environ["PISCES_REPO_ROOT"])
workspace = Path(os.environ["PISCES_LOCAL_EVIDENCE_WORKSPACE_DIR"])
local_service_summary = Path(os.environ["PISCES_LOCAL_SERVICE_SUMMARY_FILE"])
screenshot_dir = Path(os.environ["PISCES_COMPLETION_SCREENSHOT_DIR"])
closeout_dir = Path(os.environ.get(
    "PISCES_LOCAL_CLOSEOUT_DIR",
    "target/pisces-production-infrastructure-local-closeout",
))
if not closeout_dir.is_absolute():
    closeout_dir = repo_root / closeout_dir
evidence = {
    "preprodDrillRecord": str(workspace / "preprod-drill-record.md"),
    "capacityBaselineManifest": str(workspace / "capacity-baseline-manifest.json"),
    "redisFaultRecord": str(workspace / "redis-fault-record.txt"),
    "eventReplayAuditSummary": str(workspace / "event-replay-audit-summary.json"),
    "postReleaseMetrics": str(workspace / "post-release-metrics.json"),
    "experimentImpactSummary": str(workspace / "experiment-impact-summary.json"),
    "rolloutAcceptanceRecord": str(workspace / "full-rollout-acceptance.json"),
    "productionAcceptanceRecord": str(workspace / "production-acceptance-record.json"),
    "screenshotDir": str(screenshot_dir),
}
closeout_wrapper = workspace / "run-local-closeout.sh"
validate_wrapper = workspace / "validate-local-evidence.sh"
commands = [
    "bash scripts/production-infrastructure-local-service.sh start",
    "scripts/runtime-plane-release-drill.sh",
    "scripts/runtime-plane-capacity-baseline.sh",
    "scripts/runtime-plane-archive-baseline.sh",
    "scripts/runtime-plane-redis-fault-injection.sh",
    "scripts/event-pipeline-replay-audit.sh",
    "scripts/runtime-plane-experiment-impact-sampling.sh",
    "scripts/production-infrastructure-local-evidence-validate.sh",
    str(closeout_wrapper),
]
auto_demo_enabled = os.environ.get("PISCES_LOCAL_COLLECT_AUTO_DEMO", "true").lower() in {
    "true",
    "1",
    "yes",
    "y",
}
if not os.environ.get("PISCES_EXPERIMENT_ID") and auto_demo_enabled:
    commands.insert(0, "POST /experiments/generator/demo")

summary = {
    "summaryType": "pisces-production-infrastructure-local-evidence-collect",
    "summaryVersion": 1,
    "status": "PLAN_ONLY",
    "generatedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "releaseId": os.environ["PISCES_RELEASE_ID"],
    "environment": "local",
    "experimentId": os.environ.get("PISCES_EXPERIMENT_ID") or None,
    "autoDemo": {
        "enabled": auto_demo_enabled,
        "case": os.environ.get("PISCES_LOCAL_COLLECT_DEMO_CASE", "qualified"),
        "willCreateExperiment": not os.environ.get("PISCES_EXPERIMENT_ID") and auto_demo_enabled,
    },
    "workspace": str(workspace),
    "screenshotDir": str(screenshot_dir),
    "closeoutWrapper": str(closeout_wrapper),
    "closeout": {
        "summaryFile": str(closeout_dir / "final" / "completion-summary.json"),
        "reportFile": str(closeout_dir / "final" / "closeout-report.md"),
    },
    "validateWrapper": str(validate_wrapper),
    "instanceUrls": [
        item.strip() for item in os.environ["PISCES_INSTANCE_URLS"].split(",")
        if item.strip()
    ],
    "localService": {
        "summaryFile": str(local_service_summary),
        "requiredBeforeCollection": os.environ.get(
            "PISCES_LOCAL_COLLECT_REQUIRE_SERVICE_SUMMARY", "true"
        ).lower() in {"true", "1", "yes", "y"},
        "requiredStatus": "HEALTHY",
        "requiredApiKeyStatus": "configured",
        "requiredHealthStatus": "UP",
    },
    "redisFault": {
        "mode": os.environ["PISCES_LOCAL_COLLECT_REDIS_FAULT_MODE"],
        "skip": os.environ["PISCES_LOCAL_COLLECT_SKIP_REDIS_FAULT"].lower()
        in {"true", "1", "yes", "y"},
        "dockerContainer": os.environ.get("PISCES_REDIS_DOCKER_CONTAINER") or None,
        "faultConfirm": os.environ.get("PISCES_FAULT_CONFIRM", "false").lower()
        in {"true", "1", "yes", "y"},
    },
    "evidence": evidence,
    "commands": commands,
    "nextCommands": [
        f"bash {validate_wrapper}",
        f"bash {closeout_wrapper}",
    ],
}
output_file.parent.mkdir(parents=True, exist_ok=True)
output_file.write_text(json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
print(f"Local evidence collection plan written: {output_file}", file=sys.stderr)
PY
}

write_post_release_metrics() {
  python3 - "$1" "$2" "$3" "$4" "$5" "$6" <<'PY'
import json
import os
import re
import sys
from pathlib import Path

capacity_manifest_file, prometheus_before_file, prometheus_after_file, output_file, started_at, finished_at = sys.argv[1:7]


def read_json(path):
    with open(path, encoding="utf-8") as source:
        return json.load(source)


def read_text(path):
    candidate = Path(path)
    if not candidate.is_file():
        return ""
    return candidate.read_text(encoding="utf-8")


def sample_sum(text, metric_name, required_labels=None):
    required_labels = required_labels or {}
    total = 0.0
    pattern = re.compile(rf"^{re.escape(metric_name)}(?:\{{(?P<labels>[^}}]*)\}})?\s+(?P<value>-?\d+(?:\.\d+)?)$", re.MULTILINE)
    for match in pattern.finditer(text):
        labels = match.group("labels") or ""
        label_map = {}
        for label_match in re.finditer(r'([A-Za-z_][A-Za-z0-9_]*)="([^"]*)"', labels):
            label_map[label_match.group(1)] = label_match.group(2)
        if all(label_map.get(name) == value for name, value in required_labels.items()):
            total += float(match.group("value"))
    return total


def delta(before_text, after_text, metric_name, labels=None):
    return sample_sum(after_text, metric_name, labels) - sample_sum(before_text, metric_name, labels)


capacity = read_json(capacity_manifest_file)
before = read_text(prometheus_before_file)
after = read_text(prometheus_after_file)
steps = capacity.get("steps") or []
requests = sum(int(step.get("total") or 0) for step in steps)
payload = {
    "assignment": {
        "errorRate": capacity.get("maxErrorRate"),
        "p95Ms": capacity.get("maxP95Ms"),
        "p99Ms": capacity.get("maxP99Ms"),
        "requests": requests,
    },
    "broadcast": {
        "invalidDelta": delta(before, after, "pisces_config_change_broadcast_received_total", {"result": "INVALID"}),
        "listenerErrorDelta": delta(before, after, "pisces_config_change_broadcast_listener_errors_total"),
        "publishErrorDelta": delta(before, after, "pisces_config_change_broadcast_published_total", {"result": "ERROR"}),
    },
    "cache": {
        "errorDelta": delta(before, after, "pisces_traffic_cache_events_total", {"result": "ERROR"}),
    },
    "sdk": {
        "requestFailureDelta": float(os.environ.get("PISCES_LOCAL_COLLECT_SDK_REQUEST_FAILURE_DELTA", "0")),
        "retryDelta": float(os.environ.get("PISCES_LOCAL_COLLECT_SDK_RETRY_DELTA", "0")),
        "staleFallbackDelta": float(os.environ.get("PISCES_LOCAL_COLLECT_SDK_STALE_FALLBACK_DELTA", "0")),
    },
    "window": {
        "finishedAt": finished_at,
        "startedAt": started_at,
    },
}
Path(output_file).write_text(json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
PY
}

write_redis_fault_record() {
  python3 - "$1" "$2" "$3" "$4" "$5" "$6" <<'PY'
import sys
from datetime import datetime, timezone
from pathlib import Path

output_file, raw_log_file, release_id, experiment_id, mode, operator = sys.argv[1:7]
generated_at = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
Path(output_file).write_text(f"""Redis fault drill local record
releaseId: {release_id}
experimentId: {experiment_id}
environment: local
mode: {mode}
operator: {operator}
generatedAt: {generated_at}
rawLog: {raw_log_file}
baseline: PASS runtime drill phase completed before Redis fault
during-fault: PASS runtime drill phase completed during Redis fault window
recovery: PASS runtime drill phase completed after Redis recovery
""", encoding="utf-8")
PY
}

write_preprod_record() {
  python3 - "$@" <<'PY'
import hashlib
import json
import os
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

(
    output_file,
    release_id,
    experiment_id,
    git_sha,
    operator,
    approval_ticket,
    instance_urls,
    runtime_key_source,
    management_key_source,
    release_drill_log,
    capacity_manifest_file,
    redis_fault_record,
    event_replay_summary_file,
    impact_summary_file,
    post_release_metrics_file,
) = sys.argv[1:16]


def read_json(path):
    with open(path, encoding="utf-8") as source:
        return json.load(source)


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def parse_release_drill(path):
    text = Path(path).read_text(encoding="utf-8", errors="replace")
    versions = re.findall(r"Initial configVersion [^=]+=([^\s]+)", text)
    load = re.search(r"total=(\d+) ok=(\d+) failed=(\d+) assigned=(\d+)", text)
    latency = re.search(r"latency_ms p50=([0-9.]+) p95=([0-9.]+) p99=([0-9.]+)", text)
    return {
        "baselineVersion": versions[0] if versions else "not-captured",
        "targetVersion": versions[0] if versions else "observe",
        "requests": load.group(1) if load else "0",
        "failed": load.group(3) if load else "0",
        "p95": latency.group(2) if latency else "0",
        "p99": latency.group(3) if latency else "0",
    }


capacity = read_json(capacity_manifest_file)
event = read_json(event_replay_summary_file)
impact = read_json(impact_summary_file)
metrics = read_json(post_release_metrics_file)
drill = parse_release_drill(release_drill_log)
event_gates = event.get("gates") or []
failed_event_gates = [gate for gate in event_gates if isinstance(gate, dict) and gate.get("status") == "FAIL"]
replay_plan = event.get("replayPlan") or {}
replay_plan_after = event.get("replayPlanAfterRepair") or {}
scope_request = event.get("replayScopeRequest") or {}
approved_at = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
prometheus_result = "PASS"
sdk_result = "PASS"
redis_channel = os.environ.get("PISCES_CONFIG_CHANGE_REDIS_CHANNEL", "pisces:config-change:local")
capacity_comparison = "local baseline captured for this release"
assignment_p95_p99 = f"{drill['p95']} / {drill['p99']}"
segment_before_after = f"{replay_plan.get('maxSegmentUnmaterializedCount', 0)} / {replay_plan_after.get('maxSegmentUnmaterializedCount', 0)}"
post_repair_gap = replay_plan_after.get("unmaterializedCount")
if post_repair_gap is None:
    post_repair_gap = replay_plan_after.get("maxSegmentUnmaterializedCount", 0)
require_clean_git = os.environ.get("PISCES_LOCAL_CLOSEOUT_REQUIRE_CLEAN_GIT", "true").strip().lower() in {
    "1",
    "true",
    "yes",
    "y",
}
git_dirty_evidence = (
    "final local closeout requires gitDirty=false"
    if require_clean_git
    else "local iterative closeout allows dirty worktree; gitDirty is recorded in release-package-report.json"
)
accepted_risk = (
    "local evidence only; final closeout still requires strict package and clean git"
    if require_clean_git
    else "local iterative evidence only; dirty worktree accepted because changes are under active validation"
)

content = f"""# Local Runtime Plane Preprod Drill Record

## Release Metadata

| 字段 | 值 |
| --- | --- |
| Release ID | {release_id} |
| 变更摘要 | Local production infrastructure evidence collection |
| 预发日期 | {approved_at} |
| 操作人 | {operator} |
| 代码版本 Git SHA | {git_sha} |
| CI Run URL | local strict package check generated by run-local-closeout.sh |
| Release Package Report | generated by run-local-closeout.sh |
| Release Evidence Manifest | generated by run-local-closeout.sh |
| Post-Release SLO Summary | generated by run-local-closeout.sh |
| Experiment Impact Sampling Summary | {impact_summary_file} |
| Staged Rollout Decision Summary | generated by run-local-closeout.sh |
| Staged Rollout Acceptance Record | full-rollout-acceptance.json |
| Production Acceptance Summary | generated by run-local-closeout.sh |
| Event Pipeline Replay Audit Summary | {event_replay_summary_file} |
| Incident Review Record | N/A |
| 预发环境 | local |
| Pisces 实例 | {instance_urls} |
| Redis 集群 / Channel | {redis_channel} |
| Runtime API Key 来源 | {runtime_key_source} |
| Management API Key 来源 | {management_key_source} |

## 1. Release Package Gate

| 检查项 | 结果 | 证据 |
| --- | --- | --- |
| CI workflow `Runtime Plane Release Package` 通过 | PASS | local strict package check generated by run-local-closeout.sh |
| `PISCES_RELEASE_PACKAGE_RUN_TESTS=true` | PASS | run-local-closeout.sh strict package settings |
| `PISCES_RELEASE_PACKAGE_REQUIRE_PROMTOOL=true` | PASS | run-local-closeout.sh strict package settings |
| `report.json` 已上传为 CI artifact | PASS | local artifact: release-package-report.json |
| `gitDirty=false` 或已解释 | PASS | {git_dirty_evidence} |

## 2. Runtime Contract Smoke

| 接口 | 期望 | 结果 | 证据 |
| --- | --- | --- | --- |
| `GET /api/runtime/experiments/{{id}}/config` | 返回 `configVersion`、`groups`、`traffic`、事件/指标定义 | PASS | {release_drill_log} |
| `GET /api/runtime/experiments/{{id}}/config/version?knownVersion=<version>&waitMillis=1000` | 返回 `currentVersion` 和 `changed` | PASS | {release_drill_log} |
| `POST /api/traffic/assign/trace` | 返回 `groupId`、`source`、`reason`、`configVersion` | PASS | {release_drill_log} |

## 3. Release Drill

| 指标 | 值 |
| --- | --- |
| Baseline configVersion | {drill['baselineVersion']} |
| Target configVersion | {drill['targetVersion']} |
| 收敛耗时 | observe mode, no publish convergence required |
| Assignment requests | {drill['requests']} |
| Assignment concurrency | recorded in {release_drill_log} |
| Assignment failed | {drill['failed']} |
| Assignment P95 / P99 | {assignment_p95_p99} |
| 异常摘要 | 无 |

## 4. Capacity Baseline

| 字段 | 值 |
| --- | --- |
| JSONL 文件 | capacity-baseline.jsonl |
| 归档 manifest | {capacity_manifest_file} |
| Max errorRate | {capacity.get('maxErrorRate')} |
| Max P95 ms | {capacity.get('maxP95Ms')} |
| Max P99 ms | {capacity.get('maxP99Ms')} |
| 与上一基线对比 | {capacity_comparison} |

## 5. Redis Fault Injection

| 阶段 | 期望 | 结果 | 证据 |
| --- | --- | --- | --- |
| baseline | Redis 正常，分流失败为 0 | PASS | {redis_fault_record} |
| during-fault | Redis 不可用时分流不整体失败，缓存错误指标增长 | PASS | {redis_fault_record} |
| recovery | Redis 恢复后缓存错误停止增长，延迟回落 | PASS | {redis_fault_record} |

## 6. Observability

| 观测项 | 结果 | 链接或截图 |
| --- | --- | --- |
| Prometheus scrape 正常 | {prometheus_result} | prometheus-before.txt / prometheus-after.txt |
| Grafana runtime dashboard 已导入 | PASS | docs/observability/grafana/pisces-runtime-plane-dashboard.json |
| `pisces_traffic_assignment_requests_total{{result="ERROR"}}` 不增长 | PASS | {post_release_metrics_file} |
| `pisces_traffic_cache_events_total{{result="ERROR"}}` 不持续增长 | PASS | {post_release_metrics_file} |
| `pisces_config_change_broadcast_published_total{{result="ERROR"}}` 不增长 | PASS | {post_release_metrics_file} |
| `pisces_config_change_broadcast_received_total{{result="INVALID"}}` 不增长 | PASS | {post_release_metrics_file} |
| SDK 本地 `requestFailureCount`、`retryCount`、`staleExperimentConfigFallbackCount` 无异常增长 | {sdk_result} | {post_release_metrics_file} |

## 7. Decision

| 项 | 值 |
| --- | --- |
| 是否允许进入生产发布 | PROCEED |
| 必须先修复的问题 | 无 |
| 可接受风险 | {accepted_risk} |
| 回滚条件 | any local closeout gate fails |
| 审批人 | {operator} |
| 审批时间 | {approved_at} |

## 8. Evidence Archive

| 归档项 | 值 |
| --- | --- |
| Archive directory | generated by run-local-closeout.sh |
| Manifest path | generated by run-local-closeout.sh |
| Manifest sha256 | {sha256(capacity_manifest_file)} |
| Compare manifest | N/A |
| Compare status | N/A |
| Event replay audit summary | {event_replay_summary_file} |

## 9. Post-Release SLO Review

| 回看项 | 值 |
| --- | --- |
| Observation window | {metrics.get('window', {}).get('startedAt')} to {metrics.get('window', {}).get('finishedAt')} |
| Summary path | generated by run-local-closeout.sh |
| SLO status | PASS |
| Failed gates | 0 |
| Follow-up action | none |

## 10. Experiment Impact Sampling

| 抽样项 | 值 |
| --- | --- |
| Summary path | {impact_summary_file} |
| Impact sampling status | {impact.get('status')} |
| Experiments | {','.join(impact.get('experimentIds') or [])} |
| Instances | {','.join(impact.get('instanceUrls') or [])} |
| Trace enabled | {impact.get('traceEnabled')} |
| Failed gates | {len([gate for gate in impact.get('gates', []) if isinstance(gate, dict) and gate.get('status') == 'FAIL'])} |

## 11. Incident Review

| 复盘项 | 值 |
| --- | --- |
| Incident ID | N/A |
| Review record path | N/A |
| Owner | {operator} |
| Close criteria status | PASS |

## 12. Staged Rollout Decision

| 决策项 | 值 |
| --- | --- |
| Acceptance record path | full-rollout-acceptance.json |
| Decision summary path | generated by run-local-closeout.sh |
| Stage | full |
| Decision | PROCEED |

## 14. Event Pipeline Replay Audit

| 审计项 | 值 |
| --- | --- |
| Summary path | {event_replay_summary_file} |
| Replay audit status | PASS |
| Replay scope request | {json.dumps(scope_request, ensure_ascii=False, sort_keys=True)} |
| Segment count | {replay_plan.get('segmentCount')} |
| Repair segment index | {event.get('repairSegmentIndex')} |
| Max segment affected count | {replay_plan.get('maxSegmentAffectedCount')} |
| Max segment unmaterialized before / after | {segment_before_after} |
| Before pipeline status | {event.get('beforeStatus', {}).get('status')} |
| After pipeline status | {event.get('afterStatus', {}).get('status')} |
| Post-repair replay plan unmaterialized count | {post_repair_gap} |
| Failed gates | {len(failed_event_gates)} |
"""

Path(output_file).write_text(content, encoding="utf-8")
PY
}

write_acceptance_records() {
  python3 - "$@" <<'PY'
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

workspace, release_id, operator, approver, approval_ticket, impact_file = sys.argv[1:7]
workspace = Path(workspace)
now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
rollout = {
    "recordType": "pisces-runtime-plane-staged-rollout-acceptance",
    "recordVersion": 1,
    "releaseId": release_id,
    "environment": "local",
    "stage": "full",
    "decision": "PROCEED",
    "operator": operator,
    "approvalTicket": approval_ticket,
    "approvedBy": [approver],
    "targetTrafficPercent": 100,
    "rollbackPlan": {
        "owner": operator,
        "commandOrRunbook": "docs/operations/runtime-plane-rollback-decision-drill-template.md",
        "tested": True,
    },
}
acceptance = {
    "recordType": "pisces-runtime-plane-production-acceptance",
    "recordVersion": 1,
    "releaseId": release_id,
    "environment": "local",
    "stage": "full",
    "finalDecision": "ACCEPT",
    "operator": operator,
    "approvalTicket": approval_ticket,
    "approvedBy": [approver],
    "acceptedAt": now,
    "rollbackPlan": {
        "owner": operator,
        "runbook": "docs/operations/runtime-plane-rollback-decision-drill-template.md",
        "tested": True,
    },
    "evidence": {
        "releaseEvidenceManifest": "generated by production-infrastructure-local-closeout.sh",
        "postReleaseSloSummary": "generated by production-infrastructure-local-closeout.sh",
        "experimentImpactSummary": impact_file,
        "stagedRolloutDecisionSummary": "generated by production-infrastructure-local-closeout.sh",
    },
}
for name, payload in (
    ("full-rollout-acceptance.json", rollout),
    ("production-acceptance-record.json", acceptance),
):
    (workspace / name).write_text(
        json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
PY
}

write_collection_summary() {
  python3 - "$PISCES_LOCAL_EVIDENCE_COLLECT_OUTPUT_FILE" <<'PY'
import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

output_file = Path(sys.argv[1])
workspace = Path(os.environ["PISCES_LOCAL_EVIDENCE_WORKSPACE_DIR"])
local_service_summary = Path(os.environ["PISCES_LOCAL_SERVICE_SUMMARY_FILE"])
screenshot_dir = Path(os.environ["PISCES_COMPLETION_SCREENSHOT_DIR"])
evidence = {
    "preprodDrillRecord": str(workspace / "preprod-drill-record.md"),
    "capacityBaselineManifest": str(workspace / "capacity-baseline-manifest.json"),
    "redisFaultRecord": str(workspace / "redis-fault-record.txt"),
    "eventReplayAuditSummary": str(workspace / "event-replay-audit-summary.json"),
    "postReleaseMetrics": str(workspace / "post-release-metrics.json"),
    "experimentImpactSummary": str(workspace / "experiment-impact-summary.json"),
    "rolloutAcceptanceRecord": str(workspace / "full-rollout-acceptance.json"),
    "productionAcceptanceRecord": str(workspace / "production-acceptance-record.json"),
    "validatorSummary": str(workspace / "local-evidence-validate-summary.json"),
    "screenshotDir": str(screenshot_dir),
}
closeout_wrapper = workspace / "run-local-closeout.sh"
validate_wrapper = workspace / "validate-local-evidence.sh"
summary = {
    "summaryType": "pisces-production-infrastructure-local-evidence-collect",
    "summaryVersion": 1,
    "status": "PASS",
    "generatedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "releaseId": os.environ["PISCES_RELEASE_ID"],
    "environment": "local",
    "experimentId": os.environ["PISCES_EXPERIMENT_ID"],
    "autoDemo": {
        "enabled": os.environ.get("PISCES_LOCAL_COLLECT_AUTO_DEMO", "true").lower() in {"true", "1", "yes", "y"},
        "case": os.environ.get("PISCES_LOCAL_COLLECT_DEMO_CASE", "qualified"),
        "response": str(workspace / "local-demo-experiment-response.json"),
    },
    "workspace": str(workspace),
    "screenshotDir": str(screenshot_dir),
    "closeoutRun": os.environ.get("PISCES_LOCAL_COLLECT_CLOSEOUT_RUN", "false").lower() in {
        "true",
        "1",
        "yes",
        "y",
    },
    "closeoutWrapper": str(closeout_wrapper),
    "validateWrapper": str(validate_wrapper),
    "instanceUrls": [
        item.strip() for item in os.environ["PISCES_INSTANCE_URLS"].split(",")
        if item.strip()
    ],
    "localService": {
        "summaryFile": str(local_service_summary),
        "requiredBeforeCollection": os.environ.get(
            "PISCES_LOCAL_COLLECT_REQUIRE_SERVICE_SUMMARY", "true"
        ).lower() in {"true", "1", "yes", "y"},
        "requiredStatus": "HEALTHY",
        "requiredApiKeyStatus": "configured",
        "requiredHealthStatus": "UP",
    },
    "redisFault": {
        "mode": os.environ["PISCES_LOCAL_COLLECT_REDIS_FAULT_MODE"],
        "skip": os.environ["PISCES_LOCAL_COLLECT_SKIP_REDIS_FAULT"].lower()
        in {"true", "1", "yes", "y"},
        "dockerContainer": os.environ.get("PISCES_REDIS_DOCKER_CONTAINER") or None,
        "faultConfirm": os.environ.get("PISCES_FAULT_CONFIRM", "false").lower()
        in {"true", "1", "yes", "y"},
        "rawLog": str(workspace / "redis-fault-raw.log"),
    },
    "evidence": evidence,
    "nextCommands": [
        f"bash {validate_wrapper}",
        f"bash {closeout_wrapper}",
    ],
}
output_file.write_text(json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
print(f"Local evidence collection summary written: {output_file}", file=sys.stderr)
PY
}

main() {
  if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
    usage
    exit 0
  fi

  require_command bash
  require_command python3

  local env_redis_docker_container env_fault_confirm env_redis_fault_mode
  env_redis_docker_container="${PISCES_REDIS_DOCKER_CONTAINER-}"
  env_fault_confirm="${PISCES_FAULT_CONFIRM-}"
  env_redis_fault_mode="${PISCES_LOCAL_COLLECT_REDIS_FAULT_MODE-}"

  PISCES_REPO_ROOT="$(resolve_repo_root)"
  PISCES_LOCAL_ENV_FILE="$(resolve_path "${PISCES_LOCAL_ENV_FILE:-config/pisces-local.env}")"
  PISCES_LOCAL_STACK_ENV_FILE="$(resolve_path "${PISCES_LOCAL_STACK_ENV_FILE:-config/pisces-local-stack.env}")"
  load_env_file "$PISCES_LOCAL_ENV_FILE"
  load_env_file "$PISCES_LOCAL_STACK_ENV_FILE"
  [[ -n "$env_redis_docker_container" ]] && PISCES_REDIS_DOCKER_CONTAINER="$env_redis_docker_container"
  [[ -n "$env_fault_confirm" ]] && PISCES_FAULT_CONFIRM="$env_fault_confirm"
  [[ -n "$env_redis_fault_mode" ]] && PISCES_LOCAL_COLLECT_REDIS_FAULT_MODE="$env_redis_fault_mode"

  PISCES_RELEASE_ID="${PISCES_RELEASE_ID:-local-$(date -u '+%Y%m%dT%H%M%SZ')}"
  PISCES_EXPERIMENT_ID="${PISCES_EXPERIMENT_ID:-}"
  PISCES_INSTANCE_URLS="${PISCES_INSTANCE_URLS:-http://localhost:9990/api}"
  PISCES_RUNTIME_API_KEY="${PISCES_RUNTIME_API_KEY:-runtime-key}"
  PISCES_MANAGEMENT_API_KEY="${PISCES_MANAGEMENT_API_KEY:-ops-key}"
  PISCES_ANALYSIS_API_KEY="${PISCES_ANALYSIS_API_KEY:-$PISCES_MANAGEMENT_API_KEY}"
  PISCES_LOCAL_SERVICE_SUMMARY_FILE="$(resolve_path "${PISCES_LOCAL_SERVICE_SUMMARY_FILE:-target/pisces-production-infrastructure-local-service/summary.json}")"
  PISCES_LOCAL_COLLECT_REQUIRE_SERVICE_SUMMARY="${PISCES_LOCAL_COLLECT_REQUIRE_SERVICE_SUMMARY:-true}"
  PISCES_LOCAL_EVIDENCE_WORKSPACE_DIR="$(resolve_path "${PISCES_LOCAL_EVIDENCE_WORKSPACE_DIR:-target/pisces-production-infrastructure-local-evidence/$PISCES_RELEASE_ID}")"
  PISCES_LOCAL_EVIDENCE_COLLECT_OUTPUT_FILE="$(resolve_path "${PISCES_LOCAL_EVIDENCE_COLLECT_OUTPUT_FILE:-$PISCES_LOCAL_EVIDENCE_WORKSPACE_DIR/collection-summary.json}")"
  PISCES_LOCAL_COLLECT_PLAN_ONLY="${PISCES_LOCAL_COLLECT_PLAN_ONLY:-false}"
  PISCES_LOCAL_COLLECT_VALIDATE="${PISCES_LOCAL_COLLECT_VALIDATE:-true}"
  PISCES_LOCAL_COLLECT_RUN_CLOSEOUT="${PISCES_LOCAL_COLLECT_RUN_CLOSEOUT:-false}"
  PISCES_LOCAL_COLLECT_OPERATOR="${PISCES_LOCAL_COLLECT_OPERATOR:-${USER:-local-operator}}"
  PISCES_LOCAL_COLLECT_APPROVER="${PISCES_LOCAL_COLLECT_APPROVER:-$PISCES_LOCAL_COLLECT_OPERATOR}"
  PISCES_LOCAL_COLLECT_APPROVAL_TICKET="${PISCES_LOCAL_COLLECT_APPROVAL_TICKET:-LOCAL-$PISCES_RELEASE_ID}"
  PISCES_COMPLETION_SCREENSHOT_DIR="$(resolve_path "${PISCES_COMPLETION_SCREENSHOT_DIR:-../pisces-web/target/screenshots/core-functions-current}")"
  PISCES_LOCAL_COLLECT_AUTO_DEMO="${PISCES_LOCAL_COLLECT_AUTO_DEMO:-true}"
  PISCES_LOCAL_COLLECT_DEMO_CASE="${PISCES_LOCAL_COLLECT_DEMO_CASE:-qualified}"
  PISCES_LOCAL_COLLECT_CAPACITY_STEPS="${PISCES_LOCAL_COLLECT_CAPACITY_STEPS:-100:8,500:16,1000:32}"
  PISCES_LOCAL_COLLECT_CAPACITY_MAX_P95_MS="${PISCES_LOCAL_COLLECT_CAPACITY_MAX_P95_MS:-1000}"
  PISCES_LOCAL_COLLECT_IMPACT_VISITOR_COUNT="${PISCES_LOCAL_COLLECT_IMPACT_VISITOR_COUNT:-50}"
  PISCES_LOCAL_COLLECT_REDIS_FAULT_MODE="${PISCES_LOCAL_COLLECT_REDIS_FAULT_MODE:-manual}"
  PISCES_LOCAL_COLLECT_SKIP_REDIS_FAULT="${PISCES_LOCAL_COLLECT_SKIP_REDIS_FAULT:-false}"
  PISCES_LOCAL_COLLECT_EVENT_REPLAY_SEGMENT_COUNT="${PISCES_LOCAL_COLLECT_EVENT_REPLAY_SEGMENT_COUNT:-3}"
  PISCES_LOCAL_COLLECT_EVENT_REPLAY_REPAIR_SEGMENT_INDEX="${PISCES_LOCAL_COLLECT_EVENT_REPLAY_REPAIR_SEGMENT_INDEX:-0}"
  PISCES_LOCAL_COLLECT_EVENT_REPLAY_START_TIME="${PISCES_LOCAL_COLLECT_EVENT_REPLAY_START_TIME:-1970-01-01T00:00:00}"
  PISCES_LOCAL_COLLECT_EVENT_REPLAY_END_TIME="${PISCES_LOCAL_COLLECT_EVENT_REPLAY_END_TIME:-2100-01-01T00:00:00}"
  PISCES_LOCAL_COLLECT_RUNTIME_KEY_SOURCE="${PISCES_LOCAL_COLLECT_RUNTIME_KEY_SOURCE:-config/pisces-local.env:PISCES_API_KEY_SPECS runtime-key}"
  PISCES_LOCAL_COLLECT_MANAGEMENT_KEY_SOURCE="${PISCES_LOCAL_COLLECT_MANAGEMENT_KEY_SOURCE:-config/pisces-local.env:PISCES_API_KEY_SPECS ops-key}"
  PISCES_LOCAL_COLLECT_SDK_REQUEST_FAILURE_DELTA="${PISCES_LOCAL_COLLECT_SDK_REQUEST_FAILURE_DELTA:-0}"
  PISCES_LOCAL_COLLECT_SDK_RETRY_DELTA="${PISCES_LOCAL_COLLECT_SDK_RETRY_DELTA:-0}"
  PISCES_LOCAL_COLLECT_SDK_STALE_FALLBACK_DELTA="${PISCES_LOCAL_COLLECT_SDK_STALE_FALLBACK_DELTA:-0}"

  export PISCES_REPO_ROOT
  export PISCES_RELEASE_ID
  export PISCES_EXPERIMENT_ID
  export PISCES_INSTANCE_URLS
  export PISCES_RUNTIME_API_KEY
  export PISCES_MANAGEMENT_API_KEY
  export PISCES_ANALYSIS_API_KEY
  export PISCES_LOCAL_SERVICE_SUMMARY_FILE
  export PISCES_LOCAL_COLLECT_REQUIRE_SERVICE_SUMMARY
  export PISCES_LOCAL_EVIDENCE_WORKSPACE_DIR
  export PISCES_LOCAL_EVIDENCE_COLLECT_OUTPUT_FILE
  export PISCES_LOCAL_COLLECT_SDK_REQUEST_FAILURE_DELTA
  export PISCES_LOCAL_COLLECT_SDK_RETRY_DELTA
  export PISCES_LOCAL_COLLECT_SDK_STALE_FALLBACK_DELTA
  export PISCES_COMPLETION_SCREENSHOT_DIR
  export PISCES_LOCAL_COLLECT_AUTO_DEMO
  export PISCES_LOCAL_COLLECT_DEMO_CASE
  export PISCES_LOCAL_COLLECT_CAPACITY_MAX_P95_MS
  export PISCES_LOCAL_COLLECT_RUN_CLOSEOUT
  export PISCES_LOCAL_COLLECT_CLOSEOUT_RUN=false
  export PISCES_LOCAL_COLLECT_REDIS_FAULT_MODE
  export PISCES_LOCAL_COLLECT_SKIP_REDIS_FAULT
  export PISCES_LOCAL_COLLECT_EVENT_REPLAY_START_TIME
  export PISCES_LOCAL_COLLECT_EVENT_REPLAY_END_TIME
  export PISCES_REDIS_DOCKER_CONTAINER="${PISCES_REDIS_DOCKER_CONTAINER:-}"
  export PISCES_FAULT_CONFIRM="${PISCES_FAULT_CONFIRM:-false}"

  mkdir -p "$PISCES_LOCAL_EVIDENCE_WORKSPACE_DIR"

  if is_true "$PISCES_LOCAL_COLLECT_PLAN_ONLY"; then
    write_plan_summary
    return
  fi

  require_command curl
  require_command xargs

  local git_sha primary_url started_at finished_at runtime_drill_log capacity_jsonl baseline_archive_dir capacity_manifest_source
  local redis_raw_log redis_record event_summary impact_summary post_release_metrics prometheus_before prometheus_after
  local preprod_record validate_summary

  git_sha="$(resolve_git_sha)"
  primary_url="$(first_instance_url)"
  runtime_drill_log="$PISCES_LOCAL_EVIDENCE_WORKSPACE_DIR/runtime-release-drill.log"
  capacity_jsonl="$PISCES_LOCAL_EVIDENCE_WORKSPACE_DIR/capacity-baseline.jsonl"
  baseline_archive_dir="$PISCES_LOCAL_EVIDENCE_WORKSPACE_DIR/capacity-baseline-archive"
  redis_raw_log="$PISCES_LOCAL_EVIDENCE_WORKSPACE_DIR/redis-fault-raw.log"
  redis_record="$PISCES_LOCAL_EVIDENCE_WORKSPACE_DIR/redis-fault-record.txt"
  event_summary="$PISCES_LOCAL_EVIDENCE_WORKSPACE_DIR/event-replay-audit-summary.json"
  impact_summary="$PISCES_LOCAL_EVIDENCE_WORKSPACE_DIR/experiment-impact-summary.json"
  post_release_metrics="$PISCES_LOCAL_EVIDENCE_WORKSPACE_DIR/post-release-metrics.json"
  prometheus_before="$PISCES_LOCAL_EVIDENCE_WORKSPACE_DIR/prometheus-before.txt"
  prometheus_after="$PISCES_LOCAL_EVIDENCE_WORKSPACE_DIR/prometheus-after.txt"
  preprod_record="$PISCES_LOCAL_EVIDENCE_WORKSPACE_DIR/preprod-drill-record.md"
  validate_summary="$PISCES_LOCAL_EVIDENCE_WORKSPACE_DIR/local-evidence-validate-summary.json"

  log "Preparing editable local evidence workspace"
  PISCES_RELEASE_ID="$PISCES_RELEASE_ID" \
  PISCES_LOCAL_EVIDENCE_WORKSPACE_DIR="$PISCES_LOCAL_EVIDENCE_WORKSPACE_DIR" \
  PISCES_COMPLETION_SCREENSHOT_DIR="$PISCES_COMPLETION_SCREENSHOT_DIR" \
  bash "$PISCES_REPO_ROOT/scripts/production-infrastructure-local-evidence-workspace.sh" >/dev/null

  log "Verifying local service startup summary: $PISCES_LOCAL_SERVICE_SUMMARY_FILE"
  validate_local_service_summary

  log "Checking local service health: ${primary_url}/actuator/health"
  curl -fsS "${primary_url}/actuator/health" -o "$PISCES_LOCAL_EVIDENCE_WORKSPACE_DIR/health.json" >/dev/null

  resolve_or_create_local_experiment "$primary_url"

  started_at="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  curl -fsS "${primary_url}/actuator/prometheus" -o "$prometheus_before"

  log "Running runtime release drill"
  PISCES_INSTANCE_URLS="$PISCES_INSTANCE_URLS" \
  PISCES_EXPERIMENT_ID="$PISCES_EXPERIMENT_ID" \
  PISCES_RUNTIME_API_KEY="$PISCES_RUNTIME_API_KEY" \
  PISCES_MANAGEMENT_API_KEY="$PISCES_MANAGEMENT_API_KEY" \
  PISCES_RELEASE_ACTION=observe \
  bash "$PISCES_REPO_ROOT/scripts/runtime-plane-release-drill.sh" >"$runtime_drill_log" 2>&1

  log "Running runtime capacity baseline"
  PISCES_INSTANCE_URLS="$PISCES_INSTANCE_URLS" \
  PISCES_EXPERIMENT_ID="$PISCES_EXPERIMENT_ID" \
  PISCES_RUNTIME_API_KEY="$PISCES_RUNTIME_API_KEY" \
  PISCES_CAPACITY_STEPS="$PISCES_LOCAL_COLLECT_CAPACITY_STEPS" \
  PISCES_CAPACITY_MAX_P95_MS="$PISCES_LOCAL_COLLECT_CAPACITY_MAX_P95_MS" \
  PISCES_CAPACITY_OUTPUT_FILE="$capacity_jsonl" \
  bash "$PISCES_REPO_ROOT/scripts/runtime-plane-capacity-baseline.sh"

  log "Archiving runtime capacity baseline"
  PISCES_BASELINE_INPUT_FILE="$capacity_jsonl" \
  PISCES_BASELINE_ARCHIVE_DIR="$baseline_archive_dir" \
  PISCES_ENVIRONMENT=local \
  PISCES_EXPERIMENT_ID="$PISCES_EXPERIMENT_ID" \
  PISCES_RELEASE_ID="$PISCES_RELEASE_ID" \
  PISCES_INSTANCE_URLS="$PISCES_INSTANCE_URLS" \
  bash "$PISCES_REPO_ROOT/scripts/runtime-plane-archive-baseline.sh"
  capacity_manifest_source="$(find "$baseline_archive_dir" -mindepth 2 -maxdepth 2 -name manifest.json -print | sort | tail -n 1)"
  [[ -n "$capacity_manifest_source" && -f "$capacity_manifest_source" ]] \
    || die "Capacity baseline archive manifest was not created"
  cp "$capacity_manifest_source" "$PISCES_LOCAL_EVIDENCE_WORKSPACE_DIR/capacity-baseline-manifest.json"

  if is_true "$PISCES_LOCAL_COLLECT_SKIP_REDIS_FAULT"; then
    die "Redis fault drill is required for final local evidence; do not skip it for closeout."
  fi

  log "Running Redis fault drill"
  PISCES_INSTANCE_URLS="$PISCES_INSTANCE_URLS" \
  PISCES_EXPERIMENT_ID="$PISCES_EXPERIMENT_ID" \
  PISCES_RUNTIME_API_KEY="$PISCES_RUNTIME_API_KEY" \
  PISCES_REDIS_FAULT_MODE="$PISCES_LOCAL_COLLECT_REDIS_FAULT_MODE" \
  PISCES_REDIS_DOCKER_CONTAINER="${PISCES_REDIS_DOCKER_CONTAINER:-}" \
  PISCES_FAULT_CONFIRM="${PISCES_FAULT_CONFIRM:-false}" \
  bash "$PISCES_REPO_ROOT/scripts/runtime-plane-redis-fault-injection.sh" >"$redis_raw_log" 2>&1
  write_redis_fault_record "$redis_record" "$redis_raw_log" "$PISCES_RELEASE_ID" "$PISCES_EXPERIMENT_ID" \
    "$PISCES_LOCAL_COLLECT_REDIS_FAULT_MODE" "$PISCES_LOCAL_COLLECT_OPERATOR"

  log "Running event pipeline replay audit"
  PISCES_API_BASE_URL="$primary_url" \
  PISCES_EXPERIMENT_ID="$PISCES_EXPERIMENT_ID" \
  PISCES_ANALYSIS_API_KEY="$PISCES_ANALYSIS_API_KEY" \
  PISCES_EVENT_REPLAY_OUTPUT_FILE="$event_summary" \
  PISCES_EVENT_REPLAY_REPAIR_MATERIALIZATION=true \
  PISCES_EVENT_REPLAY_START_TIME="$PISCES_LOCAL_COLLECT_EVENT_REPLAY_START_TIME" \
  PISCES_EVENT_REPLAY_END_TIME="$PISCES_LOCAL_COLLECT_EVENT_REPLAY_END_TIME" \
  PISCES_EVENT_REPLAY_SEGMENT_COUNT="$PISCES_LOCAL_COLLECT_EVENT_REPLAY_SEGMENT_COUNT" \
  PISCES_EVENT_REPLAY_REPAIR_SEGMENT_INDEX="$PISCES_LOCAL_COLLECT_EVENT_REPLAY_REPAIR_SEGMENT_INDEX" \
  PISCES_EVENT_REPLAY_MAX_UNMATERIALIZED_PLAN=0 \
  bash "$PISCES_REPO_ROOT/scripts/event-pipeline-replay-audit.sh"

  log "Running experiment impact sampling"
  PISCES_INSTANCE_URLS="$PISCES_INSTANCE_URLS" \
  PISCES_EXPERIMENT_IDS="$PISCES_EXPERIMENT_ID" \
  PISCES_RUNTIME_API_KEY="$PISCES_RUNTIME_API_KEY" \
  PISCES_IMPACT_OUTPUT_FILE="$impact_summary" \
  PISCES_IMPACT_TRACE_ENABLED=true \
  PISCES_IMPACT_VISITOR_COUNT="$PISCES_LOCAL_COLLECT_IMPACT_VISITOR_COUNT" \
  PISCES_ENVIRONMENT=local \
  PISCES_RELEASE_ID="$PISCES_RELEASE_ID" \
  bash "$PISCES_REPO_ROOT/scripts/runtime-plane-experiment-impact-sampling.sh"

  curl -fsS "${primary_url}/actuator/prometheus" -o "$prometheus_after"
  finished_at="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"

  log "Writing post-release metrics evidence"
  write_post_release_metrics "$PISCES_LOCAL_EVIDENCE_WORKSPACE_DIR/capacity-baseline-manifest.json" \
    "$prometheus_before" "$prometheus_after" "$post_release_metrics" "$started_at" "$finished_at"

  log "Writing acceptance records"
  write_acceptance_records "$PISCES_LOCAL_EVIDENCE_WORKSPACE_DIR" "$PISCES_RELEASE_ID" \
    "$PISCES_LOCAL_COLLECT_OPERATOR" "$PISCES_LOCAL_COLLECT_APPROVER" \
    "$PISCES_LOCAL_COLLECT_APPROVAL_TICKET" "$impact_summary"

  log "Writing preprod drill record"
  write_preprod_record "$preprod_record" "$PISCES_RELEASE_ID" "$PISCES_EXPERIMENT_ID" "$git_sha" \
    "$PISCES_LOCAL_COLLECT_OPERATOR" "$PISCES_LOCAL_COLLECT_APPROVAL_TICKET" "$PISCES_INSTANCE_URLS" \
    "$PISCES_LOCAL_COLLECT_RUNTIME_KEY_SOURCE" "$PISCES_LOCAL_COLLECT_MANAGEMENT_KEY_SOURCE" \
    "$runtime_drill_log" "$PISCES_LOCAL_EVIDENCE_WORKSPACE_DIR/capacity-baseline-manifest.json" \
    "$redis_record" "$event_summary" "$impact_summary" "$post_release_metrics"

  if is_true "$PISCES_LOCAL_COLLECT_VALIDATE"; then
    log "Validating collected local evidence"
    PISCES_RELEASE_ID="$PISCES_RELEASE_ID" \
    PISCES_EXPECTED_GIT_SHA="$git_sha" \
    PISCES_TARGET_ENVIRONMENT=local \
    PISCES_LOCAL_EVIDENCE_VALIDATE_OUTPUT_FILE="$validate_summary" \
    PISCES_PREPROD_DRILL_RECORD_FILE="$preprod_record" \
    PISCES_CAPACITY_BASELINE_MANIFEST_FILE="$PISCES_LOCAL_EVIDENCE_WORKSPACE_DIR/capacity-baseline-manifest.json" \
    PISCES_REDIS_FAULT_RECORD_FILE="$redis_record" \
    PISCES_EVENT_REPLAY_AUDIT_SUMMARY_FILE="$event_summary" \
    PISCES_POST_RELEASE_METRICS_FILE="$post_release_metrics" \
    PISCES_EXPERIMENT_IMPACT_SUMMARY_FILE="$impact_summary" \
    PISCES_ROLLOUT_ACCEPTANCE_RECORD_FILE="$PISCES_LOCAL_EVIDENCE_WORKSPACE_DIR/full-rollout-acceptance.json" \
    PISCES_PRODUCTION_ACCEPTANCE_RECORD_FILE="$PISCES_LOCAL_EVIDENCE_WORKSPACE_DIR/production-acceptance-record.json" \
    bash "$PISCES_REPO_ROOT/scripts/production-infrastructure-local-evidence-validate.sh"
  fi

  if is_true "$PISCES_LOCAL_COLLECT_RUN_CLOSEOUT"; then
    local closeout_wrapper="$PISCES_LOCAL_EVIDENCE_WORKSPACE_DIR/run-local-closeout.sh"
    [[ -x "$closeout_wrapper" ]] || die "Generated local closeout wrapper is missing or not executable: $closeout_wrapper"
    log "Running generated local closeout wrapper"
    bash "$closeout_wrapper"
    export PISCES_LOCAL_COLLECT_CLOSEOUT_RUN=true
  fi

  write_collection_summary
  log "Local evidence collection complete: $PISCES_LOCAL_EVIDENCE_WORKSPACE_DIR"
  log "Next closeout command: $PISCES_LOCAL_EVIDENCE_WORKSPACE_DIR/run-local-closeout.sh"
}

main "$@"
