#!/usr/bin/env bash

set -euo pipefail

require_command() {
  command -v "$1" >/dev/null 2>&1 || {
    printf 'Missing command: %s\n' "$1" >&2
    exit 1
  }
}

require_command python3

smoke_root="${PISCES_RELEASE_EVIDENCE_STRICT_SMOKE_ROOT:-target/pisces-runtime-release-evidence-strict-smoke}"
run_id="$(date -u '+%Y%m%dT%H%M%SZ')-$$"
input_dir="$smoke_root/input-$run_id"
archive_dir="$smoke_root/archive"
release_id="release-strict-evidence-smoke-$run_id"

mkdir -p "$input_dir" "$archive_dir"

python3 - "$input_dir" "$release_id" <<'PY'
import json
import sys
from pathlib import Path

input_dir = Path(sys.argv[1])
release_id = sys.argv[2]
git_sha = "strict-ci-git-sha"

(input_dir / "report.json").write_text(json.dumps({
    "reportType": "pisces-runtime-plane-release-package-check",
    "status": "PASS",
    "gitSha": git_sha,
    "gitDirty": "false",
    "runTests": "true",
    "requirePromtool": "true",
    "requireRuby": "true",
    "checksPassed": 283,
    "warnings": 0,
    "releaseArtifacts": [
        "scripts/runtime-plane-release-package-check.sh",
        "scripts/runtime-plane-release-evidence-archive.sh",
        "scripts/event-pipeline-replay-audit.sh",
    ],
}, indent=2) + "\n", encoding="utf-8")

(input_dir / "preprod.md").write_text(f"""# Runtime Plane Preprod Drill Record

Release ID: {release_id}
Git SHA: {git_sha}

## Release Package Report

CI strict package report: PASS

## Event Pipeline Replay Audit

Segmented repair: PASS

## Decision

PROCEED
""", encoding="utf-8")

(input_dir / "capacity-manifest.json").write_text(json.dumps({
    "environment": "preprod",
    "experimentId": "exp_strict_smoke",
    "releaseId": release_id,
    "gitSha": git_sha,
    "maxErrorRate": 0,
    "maxP95Ms": 85,
    "maxP99Ms": 140,
}, indent=2) + "\n", encoding="utf-8")

(input_dir / "redis-fault-record.txt").write_text("""Redis fault drill strict smoke
baseline: PASS
during-fault: PASS
recovery: PASS
""", encoding="utf-8")

(input_dir / "event-replay-summary.json").write_text(json.dumps({
    "summaryType": "pisces-event-pipeline-replay-audit",
    "status": "PASS",
    "experimentId": "exp_strict_smoke",
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
        "maxSegmentAffectedCount": 60,
        "maxSegmentUnmaterializedCount": 2,
        "segments": [
            {
                "segmentIndex": 0,
                "segmentKey": "segment-000",
                "affectedCount": 60,
                "unmaterializedCount": 0,
                "recommendedAction": "NONE",
            },
            {
                "segmentIndex": 1,
                "segmentKey": "segment-001",
                "affectedCount": 60,
                "unmaterializedCount": 2,
                "recommendedAction": "REPAIR_MATERIALIZATION_SEGMENT",
            },
        ],
    },
    "replayPlanAfterRepair": {
        "requestedSegmentCount": 2,
        "segmentCount": 2,
        "segmentRecoverySupported": True,
        "maxSegmentAffectedCount": 60,
        "maxSegmentUnmaterializedCount": 0,
        "segments": [
            {
                "segmentIndex": 0,
                "segmentKey": "segment-000",
                "affectedCount": 60,
                "unmaterializedCount": 0,
                "recommendedAction": "NONE",
            },
            {
                "segmentIndex": 1,
                "segmentKey": "segment-001",
                "affectedCount": 60,
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
PY

PISCES_RELEASE_ID="$release_id" \
PISCES_RELEASE_EVIDENCE_ARCHIVE_DIR="$archive_dir" \
PISCES_RELEASE_PACKAGE_REPORT_FILE="$input_dir/report.json" \
PISCES_PREPROD_DRILL_RECORD_FILE="$input_dir/preprod.md" \
PISCES_CAPACITY_BASELINE_MANIFEST_FILE="$input_dir/capacity-manifest.json" \
PISCES_REDIS_FAULT_RECORD_FILE="$input_dir/redis-fault-record.txt" \
PISCES_EVENT_REPLAY_AUDIT_SUMMARY_FILE="$input_dir/event-replay-summary.json" \
PISCES_EXPECTED_GIT_SHA="strict-ci-git-sha" \
PISCES_RELEASE_EVIDENCE_REQUIRE_CLEAN_GIT=true \
bash scripts/runtime-plane-release-evidence-archive.sh >/dev/null

python3 - "$archive_dir" "$release_id" <<'PY'
import json
import sys
from pathlib import Path

archive_dir = Path(sys.argv[1])
release_id = sys.argv[2]

matching_manifests = []
for manifest_path in archive_dir.glob("*/manifest.json"):
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("releaseId") == release_id:
        matching_manifests.append((manifest_path, manifest))

if len(matching_manifests) != 1:
    raise SystemExit(f"Expected one strict smoke manifest for {release_id}, found {len(matching_manifests)}")

_, manifest = matching_manifests[0]
release_package = manifest.get("releasePackage") or {}
if release_package.get("runTests") != "true":
    raise SystemExit("strict manifest must preserve runTests=true")
if release_package.get("requirePromtool") != "true":
    raise SystemExit("strict manifest must preserve requirePromtool=true")
if release_package.get("requireRuby") != "true":
    raise SystemExit("strict manifest must preserve requireRuby=true")
if release_package.get("gitDirty") != "false":
    raise SystemExit("strict manifest must preserve package gitDirty=false")
if manifest.get("expectedGitSha") != "strict-ci-git-sha":
    raise SystemExit("strict manifest expectedGitSha mismatch")
if not isinstance(manifest.get("capacityBaseline"), dict):
    raise SystemExit("strict manifest missing capacityBaseline summary")
evidence = manifest.get("evidence") or {}
for key in ("capacityBaselineManifest", "redisFaultRecord", "eventPipelineReplayAuditSummary"):
    if key not in evidence:
        raise SystemExit(f"strict manifest missing evidence entry: {key}")
event_audit = manifest.get("eventPipelineReplayAudit") or {}
segment_summary = event_audit.get("segmentSummary") or {}
if event_audit.get("repairSegmentIndex") != 1:
    raise SystemExit("strict manifest repairSegmentIndex mismatch")
if segment_summary.get("segmentGateStatus") != "PASS":
    raise SystemExit("strict manifest segmentGateStatus must be PASS")
if segment_summary.get("maxSegmentUnmaterializedCountBefore") != 2:
    raise SystemExit("strict manifest pre-repair segment gap count mismatch")
if segment_summary.get("maxSegmentUnmaterializedCountAfter") != 0:
    raise SystemExit("strict manifest post-repair segment gap count mismatch")
PY

printf 'release evidence archive strict CI smoke test passed\n'
