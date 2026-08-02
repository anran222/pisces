#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  PISCES_RELEASE_ID=release-20260720-runtime-plane \
  PISCES_PREPROD_DRILL_RECORD_FILE=docs/operations/releases/release-20260720-runtime-plane.md \
  scripts/runtime-plane-release-evidence-archive.sh

Environment:
  PISCES_RELEASE_ID                              Required release or change ID.
  PISCES_RELEASE_EVIDENCE_ARCHIVE_DIR            Archive root. Default: target/pisces-runtime-release-evidence-archive
  PISCES_RELEASE_PACKAGE_REPORT_FILE             Release package report. Default: target/pisces-runtime-release-package-check/report.json
  PISCES_PREPROD_DRILL_RECORD_FILE               Required preprod drill record markdown file.
  PISCES_CAPACITY_BASELINE_MANIFEST_FILE         Optional capacity baseline archive manifest.
  PISCES_REDIS_FAULT_RECORD_FILE                 Optional Redis fault injection record.
  PISCES_EVENT_REPLAY_AUDIT_SUMMARY_FILE         Optional event pipeline replay audit summary.
  PISCES_RELEASE_BATCH_COMPARE_MANIFEST_FILE     Optional prior/expected release manifest to compare.
  PISCES_ENVIRONMENT                             Environment name. Default: preprod
  PISCES_OPERATOR                                Operator name. Default: current OS user.
  PISCES_EXPECTED_GIT_SHA                        Optional Git SHA expected in package report.
  PISCES_RELEASE_EVIDENCE_REQUIRE_PACKAGE_CI     Require runTests/promtool/ruby in report. Default: true.
  PISCES_RELEASE_EVIDENCE_REQUIRE_CLEAN_GIT      Require report gitDirty=false. Default: false.
USAGE
}

log() {
  printf '[%s] %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*" >&2
}

die() {
  log "ERROR: $*"
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "Missing command: $1"
}

sanitize_path_part() {
  printf '%s' "$1" | tr -c '[:alnum:]._-' '-'
}

resolve_git_sha() {
  if [[ -n "${PISCES_GIT_SHA:-}" ]]; then
    printf '%s' "$PISCES_GIT_SHA"
    return
  fi
  if command -v git >/dev/null 2>&1 && git rev-parse --show-toplevel >/dev/null 2>&1; then
    git rev-parse HEAD
    return
  fi
  printf 'unknown'
}

resolve_git_dirty() {
  if command -v git >/dev/null 2>&1 && git rev-parse --show-toplevel >/dev/null 2>&1; then
    if [[ -n "$(git status --porcelain)" ]]; then
      printf 'true'
      return
    fi
    printf 'false'
    return
  fi
  printf 'unknown'
}

copy_evidence() {
  local source_file="$1"
  local target_file="$2"
  [[ -f "$source_file" ]] || die "Evidence file not found: $source_file"
  cp "$source_file" "$target_file"
}

resolve_unique_archive_dir() {
  local archive_root="$1"
  local archive_name="$2"
  local archive_dir="${archive_root%/}/${archive_name}"
  local index=1
  while [[ -e "$archive_dir" ]]; do
    archive_dir="${archive_root%/}/${archive_name}-${index}"
    index=$((index + 1))
  done
  printf '%s' "$archive_dir"
}

main() {
  if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
    usage
    exit 0
  fi

  require_command python3
  require_command cp

  PISCES_RELEASE_ID="${PISCES_RELEASE_ID:-}"
  PISCES_RELEASE_EVIDENCE_ARCHIVE_DIR="${PISCES_RELEASE_EVIDENCE_ARCHIVE_DIR:-target/pisces-runtime-release-evidence-archive}"
  PISCES_RELEASE_PACKAGE_REPORT_FILE="${PISCES_RELEASE_PACKAGE_REPORT_FILE:-target/pisces-runtime-release-package-check/report.json}"
  PISCES_PREPROD_DRILL_RECORD_FILE="${PISCES_PREPROD_DRILL_RECORD_FILE:-}"
  PISCES_CAPACITY_BASELINE_MANIFEST_FILE="${PISCES_CAPACITY_BASELINE_MANIFEST_FILE:-}"
  PISCES_REDIS_FAULT_RECORD_FILE="${PISCES_REDIS_FAULT_RECORD_FILE:-}"
  PISCES_EVENT_REPLAY_AUDIT_SUMMARY_FILE="${PISCES_EVENT_REPLAY_AUDIT_SUMMARY_FILE:-}"
  PISCES_RELEASE_BATCH_COMPARE_MANIFEST_FILE="${PISCES_RELEASE_BATCH_COMPARE_MANIFEST_FILE:-}"
  PISCES_ENVIRONMENT="${PISCES_ENVIRONMENT:-preprod}"
  PISCES_OPERATOR="${PISCES_OPERATOR:-${USER:-unknown}}"
  PISCES_EXPECTED_GIT_SHA="${PISCES_EXPECTED_GIT_SHA:-}"
  PISCES_RELEASE_EVIDENCE_REQUIRE_PACKAGE_CI="${PISCES_RELEASE_EVIDENCE_REQUIRE_PACKAGE_CI:-true}"
  PISCES_RELEASE_EVIDENCE_REQUIRE_CLEAN_GIT="${PISCES_RELEASE_EVIDENCE_REQUIRE_CLEAN_GIT:-false}"
  PISCES_GIT_SHA="$(resolve_git_sha)"
  PISCES_GIT_DIRTY="$(resolve_git_dirty)"

  [[ -n "$PISCES_RELEASE_ID" ]] || die "PISCES_RELEASE_ID is required"
  [[ -n "$PISCES_PREPROD_DRILL_RECORD_FILE" ]] || die "PISCES_PREPROD_DRILL_RECORD_FILE is required"
  [[ -f "$PISCES_RELEASE_PACKAGE_REPORT_FILE" ]] || die "Release package report not found: $PISCES_RELEASE_PACKAGE_REPORT_FILE"
  [[ -f "$PISCES_PREPROD_DRILL_RECORD_FILE" ]] || die "Preprod drill record not found: $PISCES_PREPROD_DRILL_RECORD_FILE"
  if [[ -n "$PISCES_CAPACITY_BASELINE_MANIFEST_FILE" && ! -f "$PISCES_CAPACITY_BASELINE_MANIFEST_FILE" ]]; then
    die "Capacity baseline manifest not found: $PISCES_CAPACITY_BASELINE_MANIFEST_FILE"
  fi
  if [[ -n "$PISCES_REDIS_FAULT_RECORD_FILE" && ! -f "$PISCES_REDIS_FAULT_RECORD_FILE" ]]; then
    die "Redis fault record not found: $PISCES_REDIS_FAULT_RECORD_FILE"
  fi
  if [[ -n "$PISCES_EVENT_REPLAY_AUDIT_SUMMARY_FILE" && ! -f "$PISCES_EVENT_REPLAY_AUDIT_SUMMARY_FILE" ]]; then
    die "Event replay audit summary not found: $PISCES_EVENT_REPLAY_AUDIT_SUMMARY_FILE"
  fi
  if [[ -n "$PISCES_RELEASE_BATCH_COMPARE_MANIFEST_FILE" && ! -f "$PISCES_RELEASE_BATCH_COMPARE_MANIFEST_FILE" ]]; then
    die "Compare manifest not found: $PISCES_RELEASE_BATCH_COMPARE_MANIFEST_FILE"
  fi

  local archive_name archive_dir evidence_dir
  archive_name="$(date -u '+%Y%m%dT%H%M%SZ')-$(sanitize_path_part "$PISCES_ENVIRONMENT")-$(sanitize_path_part "$PISCES_RELEASE_ID")"
  archive_dir="$(resolve_unique_archive_dir "$PISCES_RELEASE_EVIDENCE_ARCHIVE_DIR" "$archive_name")"
  evidence_dir="$archive_dir/evidence"
  mkdir -p "$evidence_dir"

  copy_evidence "$PISCES_RELEASE_PACKAGE_REPORT_FILE" "$evidence_dir/release-package-report.json"
  copy_evidence "$PISCES_PREPROD_DRILL_RECORD_FILE" "$evidence_dir/preprod-drill-record.md"
  if [[ -n "$PISCES_CAPACITY_BASELINE_MANIFEST_FILE" ]]; then
    copy_evidence "$PISCES_CAPACITY_BASELINE_MANIFEST_FILE" "$evidence_dir/capacity-baseline-manifest.json"
  fi
  if [[ -n "$PISCES_REDIS_FAULT_RECORD_FILE" ]]; then
    copy_evidence "$PISCES_REDIS_FAULT_RECORD_FILE" "$evidence_dir/redis-fault-record"
  fi
  if [[ -n "$PISCES_EVENT_REPLAY_AUDIT_SUMMARY_FILE" ]]; then
    copy_evidence "$PISCES_EVENT_REPLAY_AUDIT_SUMMARY_FILE" "$evidence_dir/event-pipeline-replay-audit-summary.json"
  fi
  if [[ -n "$PISCES_RELEASE_BATCH_COMPARE_MANIFEST_FILE" ]]; then
    copy_evidence "$PISCES_RELEASE_BATCH_COMPARE_MANIFEST_FILE" "$evidence_dir/compare-manifest.json"
  fi

  export PISCES_RELEASE_ID
  export PISCES_RELEASE_EVIDENCE_ARCHIVE_DIR
  export PISCES_RELEASE_PACKAGE_REPORT_FILE
  export PISCES_PREPROD_DRILL_RECORD_FILE
  export PISCES_CAPACITY_BASELINE_MANIFEST_FILE
  export PISCES_REDIS_FAULT_RECORD_FILE
  export PISCES_EVENT_REPLAY_AUDIT_SUMMARY_FILE
  export PISCES_RELEASE_BATCH_COMPARE_MANIFEST_FILE
  export PISCES_ENVIRONMENT
  export PISCES_OPERATOR
  export PISCES_EXPECTED_GIT_SHA
  export PISCES_RELEASE_EVIDENCE_REQUIRE_PACKAGE_CI
  export PISCES_RELEASE_EVIDENCE_REQUIRE_CLEAN_GIT
  export PISCES_GIT_SHA
  export PISCES_GIT_DIRTY

  python3 - "$archive_dir/manifest.json" "$archive_dir" <<'PY'
import hashlib
import json
import os
import sys
from pathlib import Path

manifest_file = Path(sys.argv[1])
archive_dir = Path(sys.argv[2])
evidence_dir = archive_dir / "evidence"


def is_true(value):
    return str(value).lower() in {"1", "true", "yes", "y"}


def read_json(path):
    try:
        with open(path, encoding="utf-8") as source:
            return json.load(source)
    except Exception as exc:
        raise SystemExit(f"Invalid JSON file {path}: {exc}") from exc


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def file_entry(source, archived):
    return {
        "source": source,
        "archivePath": str(Path(archived).relative_to(archive_dir)),
        "sha256": sha256_file(archived),
        "sizeBytes": Path(archived).stat().st_size,
    }


def require(condition, message):
    if not condition:
        raise SystemExit(message)


release_id = os.environ["PISCES_RELEASE_ID"]
package_report = read_json(evidence_dir / "release-package-report.json")
require(package_report.get("reportType") == "pisces-runtime-plane-release-package-check",
        "Release package report type is invalid")
require(package_report.get("status") == "PASS", "Release package report status must be PASS")

if is_true(os.environ["PISCES_RELEASE_EVIDENCE_REQUIRE_PACKAGE_CI"]):
    require(package_report.get("runTests") == "true", "Release package report must be generated with runTests=true")
    require(package_report.get("requirePromtool") == "true", "Release package report must require promtool")
    require(package_report.get("requireRuby") == "true", "Release package report must require ruby")

expected_git_sha = os.environ["PISCES_EXPECTED_GIT_SHA"]
if expected_git_sha:
    require(package_report.get("gitSha") == expected_git_sha,
            f"Release package report gitSha mismatch: expected={expected_git_sha}, actual={package_report.get('gitSha')}")

if is_true(os.environ["PISCES_RELEASE_EVIDENCE_REQUIRE_CLEAN_GIT"]):
    require(package_report.get("gitDirty") == "false", "Release package report must have gitDirty=false")

preprod_record_path = evidence_dir / "preprod-drill-record.md"
with open(preprod_record_path, encoding="utf-8") as source:
    preprod_record = source.read()
require(release_id in preprod_record, "Preprod drill record must contain release ID")
require("Release Package Report" in preprod_record, "Preprod drill record must reference release package report")
require("Decision" in preprod_record, "Preprod drill record must contain release decision section")

capacity_manifest = None
capacity_manifest_path = evidence_dir / "capacity-baseline-manifest.json"
if capacity_manifest_path.exists():
    capacity_manifest = read_json(capacity_manifest_path)
    for field in ("environment", "experimentId", "releaseId", "gitSha", "maxErrorRate", "maxP95Ms", "maxP99Ms"):
        require(field in capacity_manifest, f"Capacity baseline manifest missing field: {field}")

archived_at = os.popen("date -u '+%Y-%m-%dT%H:%M:%SZ'").read().strip()
manifest = {
    "manifestType": "pisces-runtime-plane-release-evidence",
    "manifestVersion": 1,
    "archivedAt": archived_at,
    "releaseId": release_id,
    "environment": os.environ["PISCES_ENVIRONMENT"],
    "operator": os.environ["PISCES_OPERATOR"],
    "gitSha": os.environ["PISCES_GIT_SHA"],
    "gitDirty": os.environ["PISCES_GIT_DIRTY"],
    "expectedGitSha": expected_git_sha or None,
    "releasePackage": {
        "status": package_report.get("status"),
        "gitSha": package_report.get("gitSha"),
        "gitDirty": package_report.get("gitDirty"),
        "runTests": package_report.get("runTests"),
        "requirePromtool": package_report.get("requirePromtool"),
        "requireRuby": package_report.get("requireRuby"),
        "checksPassed": package_report.get("checksPassed"),
        "warnings": package_report.get("warnings"),
    },
    "evidence": {
        "releasePackageReport": file_entry(
            os.environ["PISCES_RELEASE_PACKAGE_REPORT_FILE"],
            evidence_dir / "release-package-report.json",
        ),
        "preprodDrillRecord": file_entry(
            os.environ["PISCES_PREPROD_DRILL_RECORD_FILE"],
            preprod_record_path,
        ),
    },
    "capacityBaseline": None,
    "comparison": {
        "enabled": False,
        "status": "SKIPPED",
        "source": None,
        "checkedFields": [],
        "differences": [],
    },
}

if capacity_manifest_path.exists():
    manifest["evidence"]["capacityBaselineManifest"] = file_entry(
        os.environ["PISCES_CAPACITY_BASELINE_MANIFEST_FILE"],
        capacity_manifest_path,
    )
    manifest["capacityBaseline"] = {
        "environment": capacity_manifest.get("environment"),
        "experimentId": capacity_manifest.get("experimentId"),
        "releaseId": capacity_manifest.get("releaseId"),
        "gitSha": capacity_manifest.get("gitSha"),
        "maxErrorRate": capacity_manifest.get("maxErrorRate"),
        "maxP95Ms": capacity_manifest.get("maxP95Ms"),
        "maxP99Ms": capacity_manifest.get("maxP99Ms"),
    }

redis_fault_path = evidence_dir / "redis-fault-record"
if redis_fault_path.exists():
    manifest["evidence"]["redisFaultRecord"] = file_entry(
        os.environ["PISCES_REDIS_FAULT_RECORD_FILE"],
        redis_fault_path,
    )

event_replay_audit_path = evidence_dir / "event-pipeline-replay-audit-summary.json"
if event_replay_audit_path.exists():
    event_replay_audit = read_json(event_replay_audit_path)
    require(event_replay_audit.get("summaryType") == "pisces-event-pipeline-replay-audit",
            "Event replay audit summary type is invalid")
    require(event_replay_audit.get("status") == "PASS", "Event replay audit summary status must be PASS")
    event_replay_gates = [
        gate for gate in event_replay_audit.get("gates", [])
        if isinstance(gate, dict)
    ]
    failed_event_replay_gates = [
        gate for gate in event_replay_gates
        if gate.get("status") == "FAIL"
    ]
    replay_scope_request = event_replay_audit.get("replayScopeRequest")
    if not isinstance(replay_scope_request, dict):
        replay_scope_request = {}
    replay_plan = event_replay_audit.get("replayPlan")
    if not isinstance(replay_plan, dict):
        replay_plan = {}
    replay_plan_after_repair = event_replay_audit.get("replayPlanAfterRepair")
    if not isinstance(replay_plan_after_repair, dict):
        replay_plan_after_repair = {}
    segment_gate = next(
        (
            gate for gate in event_replay_gates
            if gate.get("name") == "replay_plan_segments_generated"
        ),
        None,
    )
    repair_segment_index = event_replay_audit.get("repairSegmentIndex")
    if repair_segment_index is not None:
        require(replay_scope_request.get("segmentCount", 0) > 1,
                "Segmented repair audit must preserve replayScopeRequest.segmentCount > 1")
        require(segment_gate is not None and segment_gate.get("status") == "PASS",
                "Segmented repair audit must include replay_plan_segments_generated=PASS")
        require(isinstance(replay_plan.get("segments"), list) and replay_plan.get("segments"),
                "Segmented repair audit must include replayPlan.segments")
    manifest["evidence"]["eventPipelineReplayAuditSummary"] = file_entry(
        os.environ["PISCES_EVENT_REPLAY_AUDIT_SUMMARY_FILE"],
        event_replay_audit_path,
    )
    manifest["eventPipelineReplayAudit"] = {
        "status": event_replay_audit.get("status"),
        "experimentId": event_replay_audit.get("experimentId"),
        "executeReplay": event_replay_audit.get("executeReplay"),
        "repairMaterialization": event_replay_audit.get("repairMaterialization"),
        "repairSegmentIndex": repair_segment_index,
        "replayScopeRequest": replay_scope_request,
        "failedGateCount": len(failed_event_replay_gates),
        "segmentSummary": {
            "segmentGateStatus": segment_gate.get("status") if segment_gate else None,
            "requestedSegmentCount": replay_plan.get("requestedSegmentCount"),
            "segmentCount": replay_plan.get("segmentCount"),
            "segmentRecoverySupported": replay_plan.get("segmentRecoverySupported"),
            "maxSegmentAffectedCount": replay_plan.get("maxSegmentAffectedCount"),
            "maxSegmentUnmaterializedCountBefore": replay_plan.get("maxSegmentUnmaterializedCount"),
            "maxSegmentUnmaterializedCountAfter": replay_plan_after_repair.get("maxSegmentUnmaterializedCount"),
        },
    }
else:
    manifest["eventPipelineReplayAudit"] = None

compare_manifest_file = os.environ["PISCES_RELEASE_BATCH_COMPARE_MANIFEST_FILE"]
if compare_manifest_file:
    expected = read_json(evidence_dir / "compare-manifest.json")
    checked_fields = [
        "releaseId",
        "environment",
        "releasePackage.status",
        "releasePackage.gitSha",
        "releasePackage.runTests",
        "releasePackage.requirePromtool",
        "releasePackage.requireRuby",
        "evidence.preprodDrillRecord.sha256",
    ]
    if "capacityBaselineManifest" in manifest["evidence"] or "capacityBaselineManifest" in expected.get("evidence", {}):
        checked_fields.append("evidence.capacityBaselineManifest.sha256")
    if "redisFaultRecord" in manifest["evidence"] or "redisFaultRecord" in expected.get("evidence", {}):
        checked_fields.append("evidence.redisFaultRecord.sha256")
    if (
        "eventPipelineReplayAuditSummary" in manifest["evidence"]
        or "eventPipelineReplayAuditSummary" in expected.get("evidence", {})
    ):
        checked_fields.append("evidence.eventPipelineReplayAuditSummary.sha256")

    def get_path(payload, path):
        value = payload
        for part in path.split("."):
            if not isinstance(value, dict) or part not in value:
                return None
            value = value[part]
        return value

    differences = []
    for field in checked_fields:
        actual_value = get_path(manifest, field)
        expected_value = get_path(expected, field)
        if actual_value != expected_value:
            differences.append({
                "field": field,
                "expected": expected_value,
                "actual": actual_value,
            })
    manifest["comparison"] = {
        "enabled": True,
        "status": "MATCH" if not differences else "DIFFERENT",
        "source": compare_manifest_file,
        "checkedFields": checked_fields,
        "differences": differences,
    }
    require(not differences, "Release batch manifest comparison failed: " + json.dumps(differences, ensure_ascii=False))

with open(manifest_file, "w", encoding="utf-8") as target:
    json.dump(manifest, target, ensure_ascii=False, indent=2, sort_keys=True)
    target.write("\n")
PY

  log "Release evidence archive written to ${archive_dir}"
}

main "$@"
