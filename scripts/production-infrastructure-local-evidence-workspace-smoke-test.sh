#!/usr/bin/env bash

set -euo pipefail

require_command() {
  command -v "$1" >/dev/null 2>&1 || {
    printf 'Missing command: %s\n' "$1" >&2
    exit 1
  }
}

require_command python3

smoke_root="${PISCES_LOCAL_EVIDENCE_WORKSPACE_SMOKE_ROOT:-target/pisces-production-infrastructure-local-evidence-smoke}"
run_id="$(date -u '+%Y%m%dT%H%M%SZ')-$$"
release_id="local-evidence-workspace-smoke-$run_id"
workspace="$smoke_root/$release_id"
log_file="$workspace/local-closeout-placeholder-preflight.log"

mkdir -p "$workspace"

PISCES_RELEASE_ID="$release_id" \
PISCES_LOCAL_EVIDENCE_WORKSPACE_DIR="$workspace" \
bash scripts/production-infrastructure-local-evidence-workspace.sh >/dev/null

python3 - "$workspace" <<'PY'
import sys
from pathlib import Path

workspace = Path(sys.argv[1])
required_files = [
    "README.md",
    "preprod-drill-record.md",
    "capacity-baseline-manifest.json",
    "redis-fault-record.txt",
    "event-replay-audit-summary.json",
    "post-release-metrics.json",
    "experiment-impact-summary.json",
    "full-rollout-acceptance.json",
    "production-acceptance-record.json",
    "validate-local-evidence.sh",
    "run-local-closeout.sh",
]

missing = [name for name in required_files if not (workspace / name).is_file()]
if missing:
    raise SystemExit(f"local evidence workspace missing files: {missing}")

wrapper = workspace / "run-local-closeout.sh"
if not wrapper.stat().st_mode & 0o100:
    raise SystemExit("run-local-closeout.sh must be executable")

validator = workspace / "validate-local-evidence.sh"
if not validator.stat().st_mode & 0o100:
    raise SystemExit("validate-local-evidence.sh must be executable")

todo_files = [
    name
    for name in required_files
    if name not in {"run-local-closeout.sh", "validate-local-evidence.sh"}
    and "TODO" in (workspace / name).read_text(encoding="utf-8")
]
if len(todo_files) < 7:
    raise SystemExit(f"generated workspace should contain editable TODO evidence files: {todo_files}")
PY

set +e
PISCES_LOCAL_CLOSEOUT_REQUIRE_CLEAN_GIT=false \
PISCES_LOCAL_CLOSEOUT_REQUIRE_QIANWEN_KEY=false \
PISCES_LOCAL_CLOSEOUT_RUN_PACKAGE_CHECK=false \
PISCES_RELEASE_ID="$release_id" \
PISCES_PREPROD_DRILL_RECORD_FILE="$workspace/preprod-drill-record.md" \
PISCES_CAPACITY_BASELINE_MANIFEST_FILE="$workspace/capacity-baseline-manifest.json" \
PISCES_REDIS_FAULT_RECORD_FILE="$workspace/redis-fault-record.txt" \
PISCES_EVENT_REPLAY_AUDIT_SUMMARY_FILE="$workspace/event-replay-audit-summary.json" \
PISCES_POST_RELEASE_METRICS_FILE="$workspace/post-release-metrics.json" \
PISCES_EXPERIMENT_IMPACT_SUMMARY_FILE="$workspace/experiment-impact-summary.json" \
PISCES_ROLLOUT_ACCEPTANCE_RECORD_FILE="$workspace/full-rollout-acceptance.json" \
PISCES_PRODUCTION_ACCEPTANCE_RECORD_FILE="$workspace/production-acceptance-record.json" \
bash scripts/production-infrastructure-local-closeout.sh >"$log_file" 2>&1
closeout_status=$?
set -e

if [[ "$closeout_status" -eq 0 ]]; then
  printf 'Local closeout unexpectedly passed with TODO placeholders\n' >&2
  exit 1
fi

grep -q 'Local closeout evidence still contains TODO placeholders' "$log_file" || {
  printf 'Local closeout did not reject TODO placeholders as expected\n' >&2
  sed -n '1,160p' "$log_file" >&2
  exit 1
}

grep -q 'PISCES_PREPROD_DRILL_RECORD_FILE=' "$log_file" || {
  printf 'Local closeout placeholder report should include evidence file names\n' >&2
  sed -n '1,160p' "$log_file" >&2
  exit 1
}

printf 'production infrastructure local evidence workspace smoke test passed\n'
