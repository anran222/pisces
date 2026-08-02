#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  scripts/production-infrastructure-local-evidence-workspace.sh

Environment:
  PISCES_REPO_ROOT                         Repository root. Default: inferred from this script.
  PISCES_RELEASE_ID                        Local release ID. Default: local-<utc timestamp>.
  PISCES_LOCAL_EVIDENCE_WORKSPACE_DIR      Output directory. Default: target/pisces-production-infrastructure-local-evidence/<release-id>.
  PISCES_COMPLETION_SCREENSHOT_DIR         Core screenshot directory. Default: ../pisces-web/target/screenshots/core-functions-current.

Output:
  Editable local evidence templates, a validate-local-evidence.sh helper, and a run-local-closeout.sh command wrapper.

This script prepares a workspace. It does not create passing evidence. Replace
TODO values with real local drill, metrics, audit, and acceptance results before
running the generated closeout wrapper.
USAGE
}

log() {
  printf '[%s] %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*" >&2
}

die() {
  log "ERROR: $*"
  exit 1
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

main() {
  if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
    usage
    exit 0
  fi

  command -v python3 >/dev/null 2>&1 || die "Missing command: python3"

  PISCES_REPO_ROOT="$(resolve_repo_root)"
  PISCES_RELEASE_ID="${PISCES_RELEASE_ID:-local-$(date -u '+%Y%m%dT%H%M%SZ')}"
  PISCES_LOCAL_EVIDENCE_WORKSPACE_DIR="${PISCES_LOCAL_EVIDENCE_WORKSPACE_DIR:-target/pisces-production-infrastructure-local-evidence/$PISCES_RELEASE_ID}"
  PISCES_COMPLETION_SCREENSHOT_DIR="${PISCES_COMPLETION_SCREENSHOT_DIR:-../pisces-web/target/screenshots/core-functions-current}"

  local workspace_dir
  workspace_dir="$(resolve_path "$PISCES_LOCAL_EVIDENCE_WORKSPACE_DIR")"
  mkdir -p "$workspace_dir"

  export PISCES_REPO_ROOT
  export PISCES_RELEASE_ID
  export PISCES_LOCAL_EVIDENCE_WORKSPACE_DIR="$workspace_dir"
  export PISCES_COMPLETION_SCREENSHOT_DIR

  python3 <<'PY'
import json
import os
import stat
from datetime import datetime, timezone
from pathlib import Path

repo_root = Path(os.environ["PISCES_REPO_ROOT"])
release_id = os.environ["PISCES_RELEASE_ID"]
workspace = Path(os.environ["PISCES_LOCAL_EVIDENCE_WORKSPACE_DIR"])
screenshot_dir = os.environ["PISCES_COMPLETION_SCREENSHOT_DIR"]
git_sha = "unknown"

try:
    import subprocess

    git_sha = subprocess.check_output(
        ["git", "-C", str(repo_root), "rev-parse", "HEAD"],
        text=True,
    ).strip()
except Exception:
    pass

generated_at = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def write_json(name, payload):
    path = workspace / name
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return path


preprod = workspace / "preprod-drill-record.md"
preprod.write_text(f"""# Local Runtime Plane Preprod Drill Record

Release ID: {release_id}
Git SHA: {git_sha}

## Release Package Report

| Check | Result | Evidence |
| --- | --- | --- |
| CI workflow `Runtime Plane Release Package` equivalent local strict run | TODO | Replace after strict package check |
| `PISCES_RELEASE_PACKAGE_RUN_TESTS=true` | TODO | `release-package-report.json.runTests=true` |
| `PISCES_RELEASE_PACKAGE_REQUIRE_PROMTOOL=true` | TODO | `release-package-report.json.requirePromtool=true` |
| `report.json` archived | TODO | Local closeout output |
| `gitDirty=false` or explained | TODO | `release-package-report.json.gitDirty=false` |

## Runtime Contract Smoke

| API | Expected | Result | Evidence |
| --- | --- | --- | --- |
| `GET /api/runtime/experiments/{{id}}/config` | Returns runtime config | TODO | Replace with local command output |
| `GET /api/runtime/experiments/{{id}}/config/version` | Version check works | TODO | Replace with local command output |
| `POST /api/traffic/assign/trace` | Trace assignment works | TODO | Replace with local command output |

## Capacity Baseline

| Field | Value |
| --- | --- |
| JSONL file | TODO |
| Archive manifest | TODO |
| Max errorRate | TODO |
| Max P95 ms | TODO |
| Max P99 ms | TODO |

## Redis Fault Injection

| Phase | Expected | Result | Evidence |
| --- | --- | --- | --- |
| baseline | Runtime healthy before fault | TODO | TODO |
| during-fault | Runtime degrades without total failure | TODO | TODO |
| recovery | Runtime recovers | TODO | TODO |

## Observability

| Item | Result | Link or file |
| --- | --- | --- |
| Prometheus rule semantic check | TODO | promtool output |
| Runtime dashboard reviewed | TODO | screenshot or local URL |
| SDK metrics reviewed | TODO | SDK metrics snapshot |

## Event Pipeline Replay Audit

| Item | Result | Evidence |
| --- | --- | --- |
| Replay scope is bounded | TODO | event-replay-audit-summary.json |
| Segment repair is exercised | TODO | event-replay-audit-summary.json |
| Post-repair missing materialization is zero | TODO | event-replay-audit-summary.json |

## Decision

| Item | Value |
| --- | --- |
| Whether to proceed | TODO |
| Required fixes before closeout | TODO |
| Accepted risk | TODO |
| Rollback condition | TODO |
| Approver | TODO |
| Approved at | TODO |
""", encoding="utf-8")

write_json("capacity-baseline-manifest.json", {
    "environment": "local",
    "experimentId": "TODO_EXPERIMENT_ID",
    "gitSha": git_sha,
    "maxErrorRate": "TODO_NUMBER",
    "maxP95Ms": "TODO_NUMBER",
    "maxP99Ms": "TODO_NUMBER",
    "releaseId": release_id,
})

(workspace / "redis-fault-record.txt").write_text("""Redis fault drill local record
baseline: TODO
during-fault: TODO
recovery: TODO
""", encoding="utf-8")

write_json("event-replay-audit-summary.json", {
    "summaryType": "pisces-event-pipeline-replay-audit",
    "status": "TODO_PASS",
    "experimentId": "TODO_EXPERIMENT_ID",
    "failedGateCount": "TODO_0",
    "repairSegmentIndex": "TODO_NUMBER",
    "segmentSummary": {
        "segmentGateStatus": "TODO_PASS",
        "segmentCount": "TODO_NUMBER",
        "maxSegmentUnmaterializedCountAfter": "TODO_0",
        "maxSegmentUnmaterializedCountBefore": "TODO_NUMBER",
    },
    "gates": [
        {"name": "replay_plan_segments_generated", "status": "TODO_PASS"},
        {"name": "repair_materialization_operation_success", "status": "TODO_PASS"},
        {"name": "post_repair_replay_plan_unmaterialized_count", "status": "TODO_PASS"},
    ],
})

write_json("post-release-metrics.json", {
    "assignment": {
        "errorRate": "TODO_NUMBER",
        "p95Ms": "TODO_NUMBER",
        "p99Ms": "TODO_NUMBER",
        "requests": "TODO_NUMBER",
    },
    "broadcast": {
        "invalidDelta": "TODO_NUMBER",
        "listenerErrorDelta": "TODO_NUMBER",
        "publishErrorDelta": "TODO_NUMBER",
    },
    "cache": {
        "errorDelta": "TODO_NUMBER",
    },
    "sdk": {
        "requestFailureDelta": "TODO_NUMBER",
        "retryDelta": "TODO_NUMBER",
        "staleFallbackDelta": "TODO_NUMBER",
    },
    "window": {
        "finishedAt": "TODO_ISO_TIME",
        "startedAt": "TODO_ISO_TIME",
    },
})

write_json("experiment-impact-summary.json", {
    "reportType": "pisces-runtime-plane-experiment-impact-sampling",
    "status": "TODO_PASS",
    "environment": "local",
    "generatedAt": generated_at,
    "traceEnabled": "TODO_TRUE",
    "visitorCount": "TODO_NUMBER",
    "instanceUrls": ["http://localhost:9990/api"],
    "experimentIds": ["TODO_EXPERIMENT_ID"],
    "gates": [
        {"name": "runtime_config_available", "status": "TODO_PASS"},
        {"name": "trace_error_rate", "status": "TODO_PASS", "actual": "TODO_NUMBER", "threshold": 0},
    ],
})

write_json("full-rollout-acceptance.json", {
    "recordType": "pisces-runtime-plane-staged-rollout-acceptance",
    "recordVersion": 1,
    "releaseId": release_id,
    "environment": "local",
    "stage": "full",
    "decision": "TODO_PROCEED",
    "operator": "TODO_OPERATOR",
    "approvalTicket": "LOCAL-TODO",
    "approvedBy": ["TODO_APPROVER"],
    "targetTrafficPercent": 100,
    "rollbackPlan": {
        "owner": "TODO_OWNER",
        "commandOrRunbook": "docs/operations/runtime-plane-rollback-decision-drill-template.md",
        "tested": "TODO_TRUE",
    },
})

write_json("production-acceptance-record.json", {
    "recordType": "pisces-runtime-plane-production-acceptance",
    "recordVersion": 1,
    "releaseId": release_id,
    "environment": "local",
    "stage": "full",
    "finalDecision": "TODO_ACCEPT",
    "operator": "TODO_OPERATOR",
    "approvalTicket": "LOCAL-TODO",
    "approvedBy": ["TODO_APPROVER"],
    "acceptedAt": "TODO_ISO_TIME",
    "rollbackPlan": {
        "owner": "TODO_OWNER",
        "runbook": "docs/operations/runtime-plane-rollback-decision-drill-template.md",
        "tested": "TODO_TRUE",
    },
    "evidence": {
        "releaseEvidenceManifest": "generated by production-infrastructure-local-closeout.sh",
        "postReleaseSloSummary": "generated by production-infrastructure-local-closeout.sh",
        "experimentImpactSummary": str(workspace / "experiment-impact-summary.json"),
        "stagedRolloutDecisionSummary": "generated by production-infrastructure-local-closeout.sh",
    },
})

validator = workspace / "validate-local-evidence.sh"
validator.write_text(f"""#!/usr/bin/env bash
set -euo pipefail

PISCES_RELEASE_ID="{release_id}" \\
PISCES_EXPECTED_GIT_SHA="{git_sha}" \\
PISCES_TARGET_ENVIRONMENT="local" \\
PISCES_LOCAL_EVIDENCE_VALIDATE_OUTPUT_FILE="{workspace / 'local-evidence-validate-summary.json'}" \\
PISCES_PREPROD_DRILL_RECORD_FILE="{preprod}" \\
PISCES_CAPACITY_BASELINE_MANIFEST_FILE="{workspace / 'capacity-baseline-manifest.json'}" \\
PISCES_REDIS_FAULT_RECORD_FILE="{workspace / 'redis-fault-record.txt'}" \\
PISCES_EVENT_REPLAY_AUDIT_SUMMARY_FILE="{workspace / 'event-replay-audit-summary.json'}" \\
PISCES_POST_RELEASE_METRICS_FILE="{workspace / 'post-release-metrics.json'}" \\
PISCES_EXPERIMENT_IMPACT_SUMMARY_FILE="{workspace / 'experiment-impact-summary.json'}" \\
PISCES_ROLLOUT_ACCEPTANCE_RECORD_FILE="{workspace / 'full-rollout-acceptance.json'}" \\
PISCES_PRODUCTION_ACCEPTANCE_RECORD_FILE="{workspace / 'production-acceptance-record.json'}" \\
bash "{repo_root / 'scripts' / 'production-infrastructure-local-evidence-validate.sh'}"
""", encoding="utf-8")
validator.chmod(validator.stat().st_mode | stat.S_IXUSR)

wrapper = workspace / "run-local-closeout.sh"
wrapper.write_text(f"""#!/usr/bin/env bash
set -euo pipefail

bash "{validator}"

: "${{TONGYI_API_KEY:?Set TONGYI_API_KEY in your local shell before final closeout}}"

PISCES_RELEASE_ID="{release_id}" \\
PISCES_PREPROD_DRILL_RECORD_FILE="{preprod}" \\
PISCES_CAPACITY_BASELINE_MANIFEST_FILE="{workspace / 'capacity-baseline-manifest.json'}" \\
PISCES_REDIS_FAULT_RECORD_FILE="{workspace / 'redis-fault-record.txt'}" \\
PISCES_EVENT_REPLAY_AUDIT_SUMMARY_FILE="{workspace / 'event-replay-audit-summary.json'}" \\
PISCES_POST_RELEASE_METRICS_FILE="{workspace / 'post-release-metrics.json'}" \\
PISCES_EXPERIMENT_IMPACT_SUMMARY_FILE="{workspace / 'experiment-impact-summary.json'}" \\
PISCES_ROLLOUT_ACCEPTANCE_RECORD_FILE="{workspace / 'full-rollout-acceptance.json'}" \\
PISCES_PRODUCTION_ACCEPTANCE_RECORD_FILE="{workspace / 'production-acceptance-record.json'}" \\
PISCES_COMPLETION_SCREENSHOT_DIR="{screenshot_dir}" \\
bash "{repo_root / 'scripts' / 'production-infrastructure-local-closeout.sh'}"
""", encoding="utf-8")
wrapper.chmod(wrapper.stat().st_mode | stat.S_IXUSR)

readme = workspace / "README.md"
readme.write_text(f"""# Local Production Infrastructure Evidence Workspace

Release ID: `{release_id}`
Generated at: `{generated_at}`

Replace every `TODO` value with real local evidence before running:

```bash
{validator}
{wrapper}
```

Final closeout still requires:

- `TONGYI_API_KEY` set in your shell
- `promtool` and `ruby` available
- clean git worktree
- all local evidence files updated with real PASS results
- `validate-local-evidence.sh` passes with `status=PASS`
""", encoding="utf-8")

print(f"Local evidence workspace prepared: {workspace}", file=os.sys.stderr)
print(f"Next: edit TODO values, then run {wrapper}", file=os.sys.stderr)
PY
}

main "$@"
