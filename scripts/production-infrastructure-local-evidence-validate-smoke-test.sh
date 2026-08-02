#!/usr/bin/env bash

set -euo pipefail

require_command() {
  command -v "$1" >/dev/null 2>&1 || {
    printf 'Missing command: %s\n' "$1" >&2
    exit 1
  }
}

require_command python3

smoke_root="${PISCES_LOCAL_EVIDENCE_VALIDATE_SMOKE_ROOT:-target/pisces-production-infrastructure-local-evidence-validate-smoke}"
run_id="$(date -u '+%Y%m%dT%H%M%SZ')-$$"
todo_release_id="local-evidence-validate-todo-smoke-$run_id"
pass_release_id="local-evidence-validate-pass-smoke-$run_id"
todo_workspace="$smoke_root/$todo_release_id"
pass_workspace="$smoke_root/$pass_release_id"
todo_summary="$todo_workspace/validate-summary.json"
pass_summary="$pass_workspace/validate-summary.json"

mkdir -p "$todo_workspace" "$pass_workspace"

PISCES_RELEASE_ID="$todo_release_id" \
PISCES_LOCAL_EVIDENCE_WORKSPACE_DIR="$todo_workspace" \
bash scripts/production-infrastructure-local-evidence-workspace.sh >/dev/null

set +e
PISCES_RELEASE_ID="$todo_release_id" \
PISCES_LOCAL_EVIDENCE_VALIDATE_OUTPUT_FILE="$todo_summary" \
PISCES_PREPROD_DRILL_RECORD_FILE="$todo_workspace/preprod-drill-record.md" \
PISCES_CAPACITY_BASELINE_MANIFEST_FILE="$todo_workspace/capacity-baseline-manifest.json" \
PISCES_REDIS_FAULT_RECORD_FILE="$todo_workspace/redis-fault-record.txt" \
PISCES_EVENT_REPLAY_AUDIT_SUMMARY_FILE="$todo_workspace/event-replay-audit-summary.json" \
PISCES_POST_RELEASE_METRICS_FILE="$todo_workspace/post-release-metrics.json" \
PISCES_EXPERIMENT_IMPACT_SUMMARY_FILE="$todo_workspace/experiment-impact-summary.json" \
PISCES_ROLLOUT_ACCEPTANCE_RECORD_FILE="$todo_workspace/full-rollout-acceptance.json" \
PISCES_PRODUCTION_ACCEPTANCE_RECORD_FILE="$todo_workspace/production-acceptance-record.json" \
bash scripts/production-infrastructure-local-evidence-validate.sh >/dev/null 2>&1
todo_status=$?
set -e

if [[ "$todo_status" -eq 0 ]]; then
  printf 'Local evidence validator unexpectedly passed TODO workspace\n' >&2
  exit 1
fi

python3 - "$todo_summary" <<'PY'
import json
import sys
from pathlib import Path

summary = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
if summary.get("summaryType") != "pisces-production-infrastructure-local-evidence-validate":
    raise SystemExit("validator summary type mismatch")
if summary.get("status") not in {"HOLD", "FAIL"}:
    raise SystemExit(f"validator should block TODO evidence: {summary.get('status')}")
placeholder_gates = [
    gate for gate in summary.get("gates", [])
    if gate.get("name", "").endswith("_placeholder_scan")
    and gate.get("status") != "PASS"
]
if not placeholder_gates:
    raise SystemExit("validator should report placeholder gates")
PY

git_sha="$(git rev-parse HEAD 2>/dev/null || printf 'unknown')"

python3 - "$pass_workspace" "$pass_release_id" "$git_sha" <<'PY'
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

workspace = Path(sys.argv[1])
release_id = sys.argv[2]
git_sha = sys.argv[3]
generated_at = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def write_json(name, payload):
    (workspace / name).write_text(
        json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


(workspace / "preprod-drill-record.md").write_text(f"""# Local Runtime Plane Preprod Drill Record

Release ID: {release_id}
Git SHA: {git_sha}

## Release Package Report

| Check | Result | Evidence |
| --- | --- | --- |
| CI workflow `Runtime Plane Release Package` equivalent local strict run | PASS | smoke strict package report |
| `PISCES_RELEASE_PACKAGE_RUN_TESTS=true` | PASS | runTests=true |
| `PISCES_RELEASE_PACKAGE_REQUIRE_PROMTOOL=true` | PASS | requirePromtool=true |
| `report.json` archived | PASS | local closeout output |
| `gitDirty=false` or explained | PASS | gitDirty=false |

## Runtime Contract Smoke

| API | Expected | Result | Evidence |
| --- | --- | --- | --- |
| `GET /api/runtime/experiments/{{id}}/config` | Returns runtime config | PASS | smoke response |
| `GET /api/runtime/experiments/{{id}}/config/version` | Version check works | PASS | smoke response |
| `POST /api/traffic/assign/trace` | Trace assignment works | PASS | smoke response |

## Capacity Baseline

| Field | Value |
| --- | --- |
| JSONL file | smoke-baseline.jsonl |
| Archive manifest | capacity-baseline-manifest.json |
| Max errorRate | 0 |
| Max P95 ms | 100 |
| Max P99 ms | 180 |

## Redis Fault Injection

| Phase | Expected | Result | Evidence |
| --- | --- | --- | --- |
| baseline | Runtime healthy before fault | PASS | smoke baseline |
| during-fault | Runtime degrades without total failure | PASS | smoke fault |
| recovery | Runtime recovers | PASS | smoke recovery |

## Observability

| Item | Result | Link or file |
| --- | --- | --- |
| Prometheus rule semantic check | PASS | promtool output |
| Runtime dashboard reviewed | PASS | screenshot |
| SDK metrics reviewed | PASS | SDK metrics snapshot |

## Event Pipeline Replay Audit

| Item | Result | Evidence |
| --- | --- | --- |
| Replay scope is bounded | PASS | event-replay-audit-summary.json |
| Segment repair is exercised | PASS | event-replay-audit-summary.json |
| Post-repair missing materialization is zero | PASS | event-replay-audit-summary.json |

## Decision

| Item | Value |
| --- | --- |
| Whether to proceed | PROCEED |
| Required fixes before closeout | none |
| Accepted risk | local smoke fixture only |
| Rollback condition | any failed gate |
| Approver | smoke-approver |
| Approved at | {generated_at} |
""", encoding="utf-8")

write_json("capacity-baseline-manifest.json", {
    "environment": "local",
    "experimentId": "smoke-experiment",
    "gitSha": git_sha,
    "maxErrorRate": 0,
    "maxP95Ms": 100,
    "maxP99Ms": 180,
    "releaseId": release_id,
})

(workspace / "redis-fault-record.txt").write_text("""Redis fault drill local record
baseline: PASS smoke baseline healthy
during-fault: PASS smoke fallback healthy
recovery: PASS smoke recovery healthy
""", encoding="utf-8")

write_json("event-replay-audit-summary.json", {
    "summaryType": "pisces-event-pipeline-replay-audit",
    "status": "PASS",
    "environment": "local",
    "releaseId": release_id,
    "experimentId": "smoke-experiment",
    "repairSegmentIndex": 1,
    "replayPlan": {
        "segmentCount": 3,
        "maxSegmentUnmaterializedCountBefore": 2,
    },
    "replayPlanAfterRepair": {
        "segmentCount": 3,
        "maxSegmentUnmaterializedCount": 0,
        "segments": [
            {"segmentIndex": 0, "unmaterializedCount": 0},
            {"segmentIndex": 1, "unmaterializedCount": 0},
            {"segmentIndex": 2, "unmaterializedCount": 0},
        ],
    },
    "gates": [
        {"name": "before_pipeline_healthy", "status": "SKIP"},
        {"name": "replay_execution", "status": "SKIP"},
        {"name": "replay_plan_segments_generated", "status": "PASS"},
        {"name": "repair_materialization_operation_success", "status": "PASS"},
        {"name": "post_repair_replay_plan_unmaterialized_count", "status": "PASS"},
    ],
})

write_json("post-release-metrics.json", {
    "assignment": {
        "errorRate": 0,
        "p95Ms": 100,
        "p99Ms": 180,
        "requests": 1000,
    },
    "broadcast": {
        "invalidDelta": 0,
        "listenerErrorDelta": 0,
        "publishErrorDelta": 0,
    },
    "cache": {
        "errorDelta": 12,
    },
    "sdk": {
        "requestFailureDelta": 0,
        "retryDelta": 0,
        "staleFallbackDelta": 0,
    },
    "window": {
        "finishedAt": generated_at,
        "startedAt": generated_at,
    },
})

write_json("experiment-impact-summary.json", {
    "reportType": "pisces-runtime-plane-experiment-impact-sampling",
    "status": "PASS",
    "environment": "local",
    "releaseId": release_id,
    "generatedAt": generated_at,
    "traceEnabled": True,
    "visitorCount": 100,
    "instanceUrls": ["http://localhost:9990/api"],
    "experimentIds": ["smoke-experiment"],
    "gates": [
        {"name": "runtime_config_available", "status": "PASS"},
        {"name": "trace_error_rate", "status": "PASS", "actual": 0, "threshold": 0},
    ],
})

write_json("full-rollout-acceptance.json", {
    "recordType": "pisces-runtime-plane-staged-rollout-acceptance",
    "recordVersion": 1,
    "releaseId": release_id,
    "environment": "local",
    "stage": "full",
    "decision": "PROCEED",
    "operator": "smoke-operator",
    "approvalTicket": "LOCAL-SMOKE",
    "approvedBy": ["smoke-approver"],
    "targetTrafficPercent": 100,
    "rollbackPlan": {
        "owner": "smoke-owner",
        "commandOrRunbook": "docs/operations/runtime-plane-rollback-decision-drill-template.md",
        "tested": True,
    },
})

write_json("production-acceptance-record.json", {
    "recordType": "pisces-runtime-plane-production-acceptance",
    "recordVersion": 1,
    "releaseId": release_id,
    "environment": "local",
    "stage": "full",
    "finalDecision": "ACCEPT",
    "operator": "smoke-operator",
    "approvalTicket": "LOCAL-SMOKE",
    "approvedBy": ["smoke-approver"],
    "acceptedAt": generated_at,
    "rollbackPlan": {
        "owner": "smoke-owner",
        "runbook": "docs/operations/runtime-plane-rollback-decision-drill-template.md",
        "tested": True,
    },
    "evidence": {
        "releaseEvidenceManifest": "smoke-manifest.json",
        "postReleaseSloSummary": "smoke-slo-summary.json",
        "experimentImpactSummary": str(workspace / "experiment-impact-summary.json"),
        "stagedRolloutDecisionSummary": "smoke-rollout-summary.json",
    },
})
PY

PISCES_RELEASE_ID="$pass_release_id" \
PISCES_EXPECTED_GIT_SHA="$git_sha" \
PISCES_LOCAL_EVIDENCE_VALIDATE_OUTPUT_FILE="$pass_summary" \
PISCES_PREPROD_DRILL_RECORD_FILE="$pass_workspace/preprod-drill-record.md" \
PISCES_CAPACITY_BASELINE_MANIFEST_FILE="$pass_workspace/capacity-baseline-manifest.json" \
PISCES_REDIS_FAULT_RECORD_FILE="$pass_workspace/redis-fault-record.txt" \
PISCES_EVENT_REPLAY_AUDIT_SUMMARY_FILE="$pass_workspace/event-replay-audit-summary.json" \
PISCES_POST_RELEASE_METRICS_FILE="$pass_workspace/post-release-metrics.json" \
PISCES_EXPERIMENT_IMPACT_SUMMARY_FILE="$pass_workspace/experiment-impact-summary.json" \
PISCES_ROLLOUT_ACCEPTANCE_RECORD_FILE="$pass_workspace/full-rollout-acceptance.json" \
PISCES_PRODUCTION_ACCEPTANCE_RECORD_FILE="$pass_workspace/production-acceptance-record.json" \
bash scripts/production-infrastructure-local-evidence-validate.sh >/dev/null

python3 - "$pass_summary" <<'PY'
import json
import sys
from pathlib import Path

summary = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
if summary.get("status") != "PASS":
    raise SystemExit(f"validator pass fixture should pass: {summary.get('status')}")
if summary.get("holdGateCount") != 0 or summary.get("failedGateCount") != 0:
    raise SystemExit("validator pass fixture should have no blocking gates")
PY

printf 'production infrastructure local evidence validate smoke test passed\n'
