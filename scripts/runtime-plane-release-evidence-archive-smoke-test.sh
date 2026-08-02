#!/usr/bin/env bash

set -euo pipefail

require_command() {
  command -v "$1" >/dev/null 2>&1 || {
    printf 'Missing command: %s\n' "$1" >&2
    exit 1
  }
}

require_command python3

smoke_root="${PISCES_RELEASE_EVIDENCE_ARCHIVE_SMOKE_ROOT:-target/pisces-runtime-release-evidence-archive-smoke}"
run_id="$(date -u '+%Y%m%dT%H%M%SZ')-$$"
input_dir="$smoke_root/input-$run_id"
archive_dir="$smoke_root/archive"
release_id="release-event-audit-archive-smoke-$run_id"

mkdir -p "$input_dir" "$archive_dir"

python3 - "$input_dir" "$release_id" <<'PY'
import json
import sys
from pathlib import Path

input_dir = Path(sys.argv[1])
release_id = sys.argv[2]

(input_dir / "report.json").write_text(json.dumps({
    "reportType": "pisces-runtime-plane-release-package-check",
    "status": "PASS",
    "gitSha": "smoke-git-sha",
    "gitDirty": "true",
    "runTests": False,
    "requirePromtool": False,
    "requireRuby": False,
    "checksPassed": 1,
    "warnings": 0,
}, indent=2) + "\n", encoding="utf-8")

(input_dir / "preprod.md").write_text(f"""# Preprod Drill Record

Release ID: {release_id}

## Release Package Report

PASS

## Decision

PROCEED
""", encoding="utf-8")

(input_dir / "event-replay-summary.json").write_text(json.dumps({
    "summaryType": "pisces-event-pipeline-replay-audit",
    "status": "PASS",
    "experimentId": "exp_scope_smoke",
    "executeReplay": False,
    "repairMaterialization": True,
    "repairSegmentIndex": 1,
    "replayScopeRequest": {
        "eventTypes": ["PAY_SUCCESS"],
        "includeEvents": True,
        "includeExposures": False,
        "segmentCount": 2,
        "startTime": "2026-07-30T00:00:00",
        "endTime": "2026-07-30T01:00:00",
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
PISCES_EVENT_REPLAY_AUDIT_SUMMARY_FILE="$input_dir/event-replay-summary.json" \
PISCES_RELEASE_EVIDENCE_REQUIRE_PACKAGE_CI=false \
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
    raise SystemExit(f"Expected one smoke manifest for {release_id}, found {len(matching_manifests)}")

_, manifest = matching_manifests[0]
event_audit = manifest.get("eventPipelineReplayAudit")
if not isinstance(event_audit, dict):
    raise SystemExit("eventPipelineReplayAudit missing from manifest")
if event_audit.get("status") != "PASS":
    raise SystemExit("eventPipelineReplayAudit status must be PASS")
if event_audit.get("failedGateCount") != 0:
    raise SystemExit("eventPipelineReplayAudit failedGateCount must be 0")
if event_audit.get("replayScopeRequest", {}).get("eventTypes") != ["PAY_SUCCESS"]:
    raise SystemExit("eventPipelineReplayAudit replayScopeRequest was not preserved")
if event_audit.get("repairSegmentIndex") != 1:
    raise SystemExit("eventPipelineReplayAudit repairSegmentIndex was not preserved")
segment_summary = event_audit.get("segmentSummary") or {}
if segment_summary.get("segmentGateStatus") != "PASS":
    raise SystemExit("eventPipelineReplayAudit segment gate status was not preserved")
if segment_summary.get("maxSegmentUnmaterializedCountBefore") != 2:
    raise SystemExit("eventPipelineReplayAudit pre-repair segment gap count was not preserved")
if segment_summary.get("maxSegmentUnmaterializedCountAfter") != 0:
    raise SystemExit("eventPipelineReplayAudit post-repair segment gap count was not preserved")
if "eventPipelineReplayAuditSummary" not in manifest.get("evidence", {}):
    raise SystemExit("eventPipelineReplayAuditSummary evidence entry missing")
PY

printf 'release evidence archive event replay audit smoke test passed\n'
