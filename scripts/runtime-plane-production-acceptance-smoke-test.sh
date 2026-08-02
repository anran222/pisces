#!/usr/bin/env bash

set -euo pipefail

require_command() {
  command -v "$1" >/dev/null 2>&1 || {
    printf 'Missing command: %s\n' "$1" >&2
    exit 1
  }
}

require_command python3

smoke_root="${PISCES_PRODUCTION_ACCEPTANCE_SMOKE_ROOT:-target/pisces-runtime-production-acceptance-smoke}"
run_id="$(date -u '+%Y%m%dT%H%M%SZ')-$$"
input_dir="$smoke_root/input-$run_id"
archive_dir="$smoke_root/archive"
output_dir="$smoke_root/output-$run_id"
release_id="release-production-acceptance-smoke-$run_id"
git_sha="production-acceptance-git-sha"

mkdir -p "$input_dir" "$archive_dir" "$output_dir"

python3 - "$input_dir" "$release_id" "$git_sha" <<'PY'
import json
import sys
from pathlib import Path

input_dir = Path(sys.argv[1])
release_id = sys.argv[2]
git_sha = sys.argv[3]

(input_dir / "report.json").write_text(json.dumps({
    "reportType": "pisces-runtime-plane-release-package-check",
    "status": "PASS",
    "gitSha": git_sha,
    "gitDirty": "false",
    "runTests": "true",
    "requirePromtool": "true",
    "requireRuby": "true",
    "checksPassed": 290,
    "warnings": 0,
    "releaseArtifacts": [
        "scripts/runtime-plane-release-package-check.sh",
        "scripts/runtime-plane-release-evidence-archive.sh",
        "scripts/runtime-plane-production-acceptance-check.sh",
    ],
}, indent=2) + "\n", encoding="utf-8")

(input_dir / "preprod.md").write_text(f"""# Runtime Plane Preprod Drill Record

Release ID: {release_id}
Git SHA: {git_sha}

## Release Package Report

Strict CI package report: PASS

## Event Pipeline Replay Audit

Segmented repair audit: PASS

## Decision

PROCEED
""", encoding="utf-8")

(input_dir / "capacity-manifest.json").write_text(json.dumps({
    "environment": "prod",
    "experimentId": "exp_production_acceptance_smoke",
    "releaseId": release_id,
    "gitSha": git_sha,
    "maxErrorRate": 0,
    "maxP95Ms": 120,
    "maxP99Ms": 190,
}, indent=2) + "\n", encoding="utf-8")

(input_dir / "redis-fault-record.txt").write_text("""Redis fault drill production acceptance smoke
baseline: PASS
during-fault: PASS
recovery: PASS
""", encoding="utf-8")

(input_dir / "event-replay-summary.json").write_text(json.dumps({
    "summaryType": "pisces-event-pipeline-replay-audit",
    "status": "PASS",
    "experimentId": "exp_production_acceptance_smoke",
    "executeReplay": False,
    "repairMaterialization": True,
    "repairSegmentIndex": 1,
    "replayScopeRequest": {
        "startTime": "2026-07-30T00:00:00",
        "endTime": "2026-07-30T01:00:00",
        "eventTypes": ["PAY_SUCCESS"],
        "includeEvents": True,
        "includeExposures": False,
        "segmentCount": 2,
    },
    "replayPlan": {
        "requestedSegmentCount": 2,
        "segmentCount": 2,
        "segmentRecoverySupported": True,
        "maxSegmentAffectedCount": 80,
        "maxSegmentUnmaterializedCount": 3,
        "segments": [
            {
                "segmentIndex": 0,
                "segmentKey": "segment-000",
                "affectedCount": 40,
                "unmaterializedCount": 0,
                "recommendedAction": "NONE",
            },
            {
                "segmentIndex": 1,
                "segmentKey": "segment-001",
                "affectedCount": 40,
                "unmaterializedCount": 3,
                "recommendedAction": "REPAIR_MATERIALIZATION_SEGMENT",
            },
        ],
    },
    "replayPlanAfterRepair": {
        "requestedSegmentCount": 2,
        "segmentCount": 2,
        "segmentRecoverySupported": True,
        "maxSegmentAffectedCount": 80,
        "maxSegmentUnmaterializedCount": 0,
        "segments": [
            {
                "segmentIndex": 0,
                "segmentKey": "segment-000",
                "affectedCount": 40,
                "unmaterializedCount": 0,
                "recommendedAction": "NONE",
            },
            {
                "segmentIndex": 1,
                "segmentKey": "segment-001",
                "affectedCount": 40,
                "unmaterializedCount": 0,
                "recommendedAction": "NONE",
            },
        ],
    },
    "gates": [
        {
            "name": "replay_plan_segments_generated",
            "status": "PASS",
        },
        {
            "name": "repair_materialization_operation_success",
            "status": "PASS",
        },
        {
            "name": "post_repair_replay_plan_unmaterialized_count",
            "status": "PASS",
        },
    ],
}, indent=2) + "\n", encoding="utf-8")

(input_dir / "post-release-metrics.json").write_text(json.dumps({
    "assignment": {
        "errorRate": 0,
        "p95Ms": 118,
        "p99Ms": 180,
        "requests": 8000,
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
        "startedAt": "2026-07-30T10:00:00Z",
        "finishedAt": "2026-07-30T10:30:00Z",
    },
}, indent=2) + "\n", encoding="utf-8")

(input_dir / "impact-summary.json").write_text(json.dumps({
    "reportType": "pisces-runtime-plane-experiment-impact-sampling",
    "status": "PASS",
    "generatedAt": "2026-07-30T10:30:00Z",
    "instanceUrls": [
        "https://prod-a.example.com/api",
        "https://prod-b.example.com/api",
    ],
    "experimentIds": [
        "exp_production_acceptance_smoke",
    ],
    "traceEnabled": True,
    "visitorCount": 20,
    "visitorPrefix": "production-acceptance-smoke",
    "gates": [
        {
            "name": "exp_production_acceptance_smoke:runtime_config_available",
            "status": "PASS",
            "actual": 2,
            "threshold": 2,
        },
        {
            "name": "exp_production_acceptance_smoke:trace_error_rate",
            "status": "PASS",
            "actual": 0,
            "threshold": 0,
        },
    ],
    "experiments": [
        {
            "experimentId": "exp_production_acceptance_smoke",
            "traceSummary": {
                "enabled": True,
                "errorRate": 0,
                "assignedGroupCount": 2,
            },
        },
    ],
}, indent=2) + "\n", encoding="utf-8")
PY

PISCES_RELEASE_ID="$release_id" \
PISCES_ENVIRONMENT="prod" \
PISCES_RELEASE_EVIDENCE_ARCHIVE_DIR="$archive_dir" \
PISCES_RELEASE_PACKAGE_REPORT_FILE="$input_dir/report.json" \
PISCES_PREPROD_DRILL_RECORD_FILE="$input_dir/preprod.md" \
PISCES_CAPACITY_BASELINE_MANIFEST_FILE="$input_dir/capacity-manifest.json" \
PISCES_REDIS_FAULT_RECORD_FILE="$input_dir/redis-fault-record.txt" \
PISCES_EVENT_REPLAY_AUDIT_SUMMARY_FILE="$input_dir/event-replay-summary.json" \
PISCES_EXPECTED_GIT_SHA="$git_sha" \
PISCES_RELEASE_EVIDENCE_REQUIRE_CLEAN_GIT=true \
bash scripts/runtime-plane-release-evidence-archive.sh >/dev/null

manifest_file="$(python3 - "$archive_dir" "$release_id" <<'PY'
import json
import sys
from pathlib import Path

archive_dir = Path(sys.argv[1])
release_id = sys.argv[2]
matches = []
for manifest_path in archive_dir.glob("*/manifest.json"):
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("releaseId") == release_id:
        matches.append(manifest_path)
if len(matches) != 1:
    raise SystemExit(f"Expected one manifest for {release_id}, found {len(matches)}")
print(matches[0])
PY
)"

slo_summary_file="$output_dir/slo-summary.json"
PISCES_RELEASE_EVIDENCE_MANIFEST_FILE="$manifest_file" \
PISCES_POST_RELEASE_METRICS_FILE="$input_dir/post-release-metrics.json" \
PISCES_REDIS_FAULT_RECORD_FILE="$input_dir/redis-fault-record.txt" \
PISCES_POST_RELEASE_SLO_OUTPUT_FILE="$slo_summary_file" \
bash scripts/runtime-plane-post-release-slo-review.sh >/dev/null

rollout_acceptance_file="$input_dir/staged-rollout-acceptance.json"
rollout_decision_file="$output_dir/staged-rollout-decision.json"
python3 - "$rollout_acceptance_file" "$release_id" <<'PY'
import json
import sys
from pathlib import Path

target = Path(sys.argv[1])
release_id = sys.argv[2]
target.write_text(json.dumps({
    "recordType": "pisces-runtime-plane-staged-rollout-acceptance",
    "recordVersion": 1,
    "releaseId": release_id,
    "environment": "prod",
    "stage": "full",
    "decision": "PROCEED",
    "operator": "release-operator",
    "approvedBy": ["runtime-owner", "business-owner"],
    "approvalTicket": "CHANGE-PRODUCTION-ACCEPTANCE-SMOKE",
    "startedAt": "2026-07-30T10:00:00Z",
    "targetTrafficPercent": 100,
    "rollbackPlan": {
        "owner": "runtime-owner",
        "commandOrRunbook": "docs/operations/runtime-plane-rollback-decision-drill-template.md",
        "tested": True,
    },
}, indent=2) + "\n", encoding="utf-8")
PY

PISCES_RELEASE_STAGE="full" \
PISCES_RELEASE_EVIDENCE_MANIFEST_FILE="$manifest_file" \
PISCES_POST_RELEASE_SLO_SUMMARY_FILE="$slo_summary_file" \
PISCES_EXPERIMENT_IMPACT_SUMMARY_FILE="$input_dir/impact-summary.json" \
PISCES_ROLLOUT_ACCEPTANCE_RECORD_FILE="$rollout_acceptance_file" \
PISCES_ROLLOUT_DECISION_OUTPUT_FILE="$rollout_decision_file" \
PISCES_ROLLOUT_REQUIRE_CLEAN_GIT=true \
PISCES_ROLLOUT_REQUIRE_TRACE_SAMPLING=true \
PISCES_ROLLOUT_TARGET_TRAFFIC_PERCENT=100 \
PISCES_ROLLOUT_MAX_TRAFFIC_PERCENT=100 \
bash scripts/runtime-plane-staged-rollout-decision.sh >/dev/null

production_acceptance_record="$input_dir/production-acceptance.json"
production_acceptance_summary="$output_dir/production-acceptance-summary.json"
python3 - "$production_acceptance_record" "$release_id" "$manifest_file" "$slo_summary_file" "$input_dir/impact-summary.json" "$rollout_decision_file" <<'PY'
import json
import sys
from pathlib import Path

target = Path(sys.argv[1])
release_id, manifest_file, slo_file, impact_file, decision_file = sys.argv[2:7]
target.write_text(json.dumps({
    "recordType": "pisces-runtime-plane-production-acceptance",
    "recordVersion": 1,
    "releaseId": release_id,
    "environment": "prod",
    "stage": "full",
    "finalDecision": "ACCEPT",
    "operator": "release-operator",
    "approvedBy": ["runtime-owner", "business-owner", "oncall-owner"],
    "approvalTicket": "CHANGE-PRODUCTION-ACCEPTANCE-SMOKE",
    "acceptedAt": "2026-07-30T10:45:00Z",
    "evidence": {
        "releaseEvidenceManifest": manifest_file,
        "postReleaseSloSummary": slo_file,
        "experimentImpactSummary": impact_file,
        "stagedRolloutDecisionSummary": decision_file,
    },
    "rollbackPlan": {
        "owner": "runtime-owner",
        "runbook": "docs/operations/runtime-plane-rollback-decision-drill-template.md",
        "tested": True,
    },
}, indent=2) + "\n", encoding="utf-8")
PY

PISCES_RELEASE_EVIDENCE_MANIFEST_FILE="$manifest_file" \
PISCES_POST_RELEASE_SLO_SUMMARY_FILE="$slo_summary_file" \
PISCES_EXPERIMENT_IMPACT_SUMMARY_FILE="$input_dir/impact-summary.json" \
PISCES_STAGED_ROLLOUT_DECISION_SUMMARY_FILE="$rollout_decision_file" \
PISCES_PRODUCTION_ACCEPTANCE_RECORD_FILE="$production_acceptance_record" \
PISCES_PRODUCTION_ACCEPTANCE_OUTPUT_FILE="$production_acceptance_summary" \
PISCES_PRODUCTION_ACCEPTANCE_REQUIRE_CLEAN_GIT=true \
PISCES_PRODUCTION_ACCEPTANCE_REQUIRE_EVENT_REPLAY=true \
PISCES_PRODUCTION_ACCEPTANCE_REQUIRE_TRACE=true \
bash scripts/runtime-plane-production-acceptance-check.sh >/dev/null

python3 - "$production_acceptance_summary" <<'PY'
import json
import sys
from pathlib import Path

summary = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
if summary.get("summaryType") != "pisces-runtime-plane-production-acceptance-check":
    raise SystemExit("production acceptance summary type mismatch")
if summary.get("status") != "PASS":
    raise SystemExit("production acceptance summary status must be PASS")
if summary.get("decision") != "ACCEPT":
    raise SystemExit("production acceptance decision must be ACCEPT")
bad_gates = [
    gate for gate in summary.get("gates", [])
    if gate.get("status") not in {"PASS", "SKIP"}
]
if bad_gates:
    raise SystemExit(f"production acceptance has blocking gates: {bad_gates}")
requirements = summary.get("requirements") or {}
for key in ("packageCi", "cleanGit", "capacityBaseline", "redisFault", "eventReplay", "traceSampling"):
    if requirements.get(key) is not True:
        raise SystemExit(f"production acceptance requirement not enforced: {key}")
PY

printf 'runtime plane production acceptance smoke test passed\n'
