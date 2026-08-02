#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  PISCES_RELEASE_EVIDENCE_MANIFEST_FILE=target/pisces-runtime-release-evidence-archive/<release>/manifest.json \
  PISCES_POST_RELEASE_SLO_SUMMARY_FILE=target/pisces-runtime-post-release-slo-review/summary.json \
  PISCES_EXPERIMENT_IMPACT_SUMMARY_FILE=target/pisces-runtime-experiment-impact-sampling/summary.json \
  PISCES_STAGED_ROLLOUT_DECISION_SUMMARY_FILE=target/pisces-runtime-staged-rollout-decision/summary.json \
  PISCES_PRODUCTION_ACCEPTANCE_RECORD_FILE=docs/operations/runtime-plane-production-acceptance-sample.json \
  scripts/runtime-plane-production-acceptance-check.sh

Environment:
  PISCES_RELEASE_EVIDENCE_MANIFEST_FILE              Required release evidence manifest.
  PISCES_POST_RELEASE_SLO_SUMMARY_FILE               Required post-release SLO summary.
  PISCES_EXPERIMENT_IMPACT_SUMMARY_FILE              Required experiment impact sampling summary.
  PISCES_STAGED_ROLLOUT_DECISION_SUMMARY_FILE        Required staged rollout decision summary.
  PISCES_PRODUCTION_ACCEPTANCE_RECORD_FILE           Required production acceptance sign-off record.
  PISCES_PRODUCTION_ACCEPTANCE_OUTPUT_FILE           JSON output. Default: target/pisces-runtime-production-acceptance/summary.json.
  PISCES_PRODUCTION_ACCEPTANCE_REQUIRE_PACKAGE_CI    Require strict CI package report. Default: true.
  PISCES_PRODUCTION_ACCEPTANCE_REQUIRE_CLEAN_GIT     Require package report gitDirty=false. Default: false.
  PISCES_PRODUCTION_ACCEPTANCE_REQUIRE_CAPACITY      Require capacity baseline evidence. Default: true.
  PISCES_PRODUCTION_ACCEPTANCE_REQUIRE_REDIS_FAULT   Require Redis fault drill evidence. Default: true.
  PISCES_PRODUCTION_ACCEPTANCE_REQUIRE_EVENT_REPLAY  Require event replay audit evidence. Default: false.
  PISCES_PRODUCTION_ACCEPTANCE_REQUIRE_TRACE         Require impact trace sampling. Default: false.
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
  PISCES_POST_RELEASE_SLO_SUMMARY_FILE="${PISCES_POST_RELEASE_SLO_SUMMARY_FILE:-}"
  PISCES_EXPERIMENT_IMPACT_SUMMARY_FILE="${PISCES_EXPERIMENT_IMPACT_SUMMARY_FILE:-}"
  PISCES_STAGED_ROLLOUT_DECISION_SUMMARY_FILE="${PISCES_STAGED_ROLLOUT_DECISION_SUMMARY_FILE:-}"
  PISCES_PRODUCTION_ACCEPTANCE_RECORD_FILE="${PISCES_PRODUCTION_ACCEPTANCE_RECORD_FILE:-}"
  PISCES_PRODUCTION_ACCEPTANCE_OUTPUT_FILE="${PISCES_PRODUCTION_ACCEPTANCE_OUTPUT_FILE:-target/pisces-runtime-production-acceptance/summary.json}"
  PISCES_PRODUCTION_ACCEPTANCE_REQUIRE_PACKAGE_CI="${PISCES_PRODUCTION_ACCEPTANCE_REQUIRE_PACKAGE_CI:-true}"
  PISCES_PRODUCTION_ACCEPTANCE_REQUIRE_CLEAN_GIT="${PISCES_PRODUCTION_ACCEPTANCE_REQUIRE_CLEAN_GIT:-false}"
  PISCES_PRODUCTION_ACCEPTANCE_REQUIRE_CAPACITY="${PISCES_PRODUCTION_ACCEPTANCE_REQUIRE_CAPACITY:-true}"
  PISCES_PRODUCTION_ACCEPTANCE_REQUIRE_REDIS_FAULT="${PISCES_PRODUCTION_ACCEPTANCE_REQUIRE_REDIS_FAULT:-true}"
  PISCES_PRODUCTION_ACCEPTANCE_REQUIRE_EVENT_REPLAY="${PISCES_PRODUCTION_ACCEPTANCE_REQUIRE_EVENT_REPLAY:-false}"
  PISCES_PRODUCTION_ACCEPTANCE_REQUIRE_TRACE="${PISCES_PRODUCTION_ACCEPTANCE_REQUIRE_TRACE:-false}"

  export PISCES_RELEASE_EVIDENCE_MANIFEST_FILE
  export PISCES_POST_RELEASE_SLO_SUMMARY_FILE
  export PISCES_EXPERIMENT_IMPACT_SUMMARY_FILE
  export PISCES_STAGED_ROLLOUT_DECISION_SUMMARY_FILE
  export PISCES_PRODUCTION_ACCEPTANCE_RECORD_FILE
  export PISCES_PRODUCTION_ACCEPTANCE_REQUIRE_PACKAGE_CI
  export PISCES_PRODUCTION_ACCEPTANCE_REQUIRE_CLEAN_GIT
  export PISCES_PRODUCTION_ACCEPTANCE_REQUIRE_CAPACITY
  export PISCES_PRODUCTION_ACCEPTANCE_REQUIRE_REDIS_FAULT
  export PISCES_PRODUCTION_ACCEPTANCE_REQUIRE_EVENT_REPLAY
  export PISCES_PRODUCTION_ACCEPTANCE_REQUIRE_TRACE

  local output_file
  output_file="$(resolve_output_file "$PISCES_PRODUCTION_ACCEPTANCE_OUTPUT_FILE")"
  mkdir -p "$(dirname "$output_file")"

  python3 - "$output_file" <<'PY'
import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

output_file = sys.argv[1]


def now_iso():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_bool(value, field):
    normalized = str(value).strip().lower()
    if normalized in {"1", "true", "yes", "y"}:
        return True
    if normalized in {"0", "false", "no", "n"}:
        return False
    raise SystemExit(f"{field} must be boolean: {value}")


def read_json(path, field):
    if not path:
        return None, f"{field} is required"
    candidate = Path(path)
    if not candidate.is_file():
        return None, f"{field} not found: {path}"
    try:
        with open(candidate, encoding="utf-8") as source:
            return json.load(source), None
    except Exception as exc:
        return None, f"Invalid JSON file {path}: {exc}"


def add_gate(gates, name, status, actual=None, expected=None, evidence=None, reason=None):
    gate = {
        "name": name,
        "status": status,
        "actual": actual,
        "expected": expected,
    }
    if evidence is not None:
        gate["evidence"] = evidence
    if reason:
        gate["reason"] = reason
    gates.append(gate)


def count_blocking_gates(payload, rollback=False):
    blocking = []
    for gate in payload.get("gates") or []:
        if gate.get("status") not in {"PASS", "SKIP"}:
            blocking.append(gate)
    status = "ROLLBACK" if rollback and blocking else "HOLD" if blocking else "PASS"
    return status, len(blocking)


def gate_json_available(gates, payload, error_message, name, evidence):
    if error_message:
        add_gate(
            gates,
            f"{name}_available",
            "HOLD",
            actual="missing",
            expected="valid JSON",
            evidence=evidence,
            reason=error_message,
        )
        return False
    add_gate(
        gates,
        f"{name}_available",
        "PASS",
        actual="present",
        expected="present",
        evidence=evidence,
    )
    return payload is not None


def expected_type_gate(gates, name, payload, expected_type, evidence):
    actual_type = (
        payload.get("manifestType")
        or payload.get("summaryType")
        or payload.get("reportType")
        or payload.get("recordType")
    )
    add_gate(
        gates,
        f"{name}_type",
        "PASS" if actual_type == expected_type else "HOLD",
        actual=actual_type,
        expected=expected_type,
        evidence=evidence,
    )


def status_gate(gates, name, status, expected, evidence, failure_status="HOLD"):
    add_gate(
        gates,
        f"{name}_status",
        "PASS" if status == expected else failure_status,
        actual=status,
        expected=expected,
        evidence=evidence,
    )


def matching_gate(gates, name, actual, expected, evidence, failure_status="HOLD"):
    add_gate(
        gates,
        name,
        "PASS" if actual == expected else failure_status,
        actual=actual,
        expected=expected,
        evidence=evidence,
    )


require_package_ci = parse_bool(
    os.environ["PISCES_PRODUCTION_ACCEPTANCE_REQUIRE_PACKAGE_CI"],
    "PISCES_PRODUCTION_ACCEPTANCE_REQUIRE_PACKAGE_CI",
)
require_clean_git = parse_bool(
    os.environ["PISCES_PRODUCTION_ACCEPTANCE_REQUIRE_CLEAN_GIT"],
    "PISCES_PRODUCTION_ACCEPTANCE_REQUIRE_CLEAN_GIT",
)
require_capacity = parse_bool(
    os.environ["PISCES_PRODUCTION_ACCEPTANCE_REQUIRE_CAPACITY"],
    "PISCES_PRODUCTION_ACCEPTANCE_REQUIRE_CAPACITY",
)
require_redis_fault = parse_bool(
    os.environ["PISCES_PRODUCTION_ACCEPTANCE_REQUIRE_REDIS_FAULT"],
    "PISCES_PRODUCTION_ACCEPTANCE_REQUIRE_REDIS_FAULT",
)
require_event_replay = parse_bool(
    os.environ["PISCES_PRODUCTION_ACCEPTANCE_REQUIRE_EVENT_REPLAY"],
    "PISCES_PRODUCTION_ACCEPTANCE_REQUIRE_EVENT_REPLAY",
)
require_trace = parse_bool(
    os.environ["PISCES_PRODUCTION_ACCEPTANCE_REQUIRE_TRACE"],
    "PISCES_PRODUCTION_ACCEPTANCE_REQUIRE_TRACE",
)

manifest_path = os.environ["PISCES_RELEASE_EVIDENCE_MANIFEST_FILE"]
slo_path = os.environ["PISCES_POST_RELEASE_SLO_SUMMARY_FILE"]
impact_path = os.environ["PISCES_EXPERIMENT_IMPACT_SUMMARY_FILE"]
decision_path = os.environ["PISCES_STAGED_ROLLOUT_DECISION_SUMMARY_FILE"]
acceptance_path = os.environ["PISCES_PRODUCTION_ACCEPTANCE_RECORD_FILE"]

gates = []
manifest, error_message = read_json(manifest_path, "PISCES_RELEASE_EVIDENCE_MANIFEST_FILE")
if gate_json_available(gates, manifest, error_message, "release_evidence", manifest_path):
    expected_type_gate(
        gates,
        "release_evidence",
        manifest,
        "pisces-runtime-plane-release-evidence",
        manifest_path,
    )
else:
    manifest = {}

release_id = manifest.get("releaseId")
environment = manifest.get("environment")
release_package = manifest.get("releasePackage") or {}
evidence = manifest.get("evidence") or {}

if manifest:
    status_gate(
        gates,
        "release_package",
        release_package.get("status"),
        "PASS",
        manifest_path,
        failure_status="HOLD",
    )
    if require_package_ci:
        for field in ("runTests", "requirePromtool", "requireRuby"):
            matching_gate(
                gates,
                f"release_package_{field}",
                release_package.get(field),
                "true",
                manifest_path,
            )
    if require_clean_git:
        matching_gate(
            gates,
            "release_package_git_dirty",
            release_package.get("gitDirty"),
            "false",
            manifest_path,
        )
    for key in ("releasePackageReport", "preprodDrillRecord"):
        add_gate(
            gates,
            f"release_evidence_{key}",
            "PASS" if isinstance(evidence.get(key), dict) else "HOLD",
            actual="present" if isinstance(evidence.get(key), dict) else "missing",
            expected="present",
            evidence=manifest_path,
        )
    if require_capacity:
        add_gate(
            gates,
            "capacity_baseline_evidence",
            "PASS" if isinstance(evidence.get("capacityBaselineManifest"), dict)
            and isinstance(manifest.get("capacityBaseline"), dict) else "HOLD",
            actual="present" if isinstance(evidence.get("capacityBaselineManifest"), dict) else "missing",
            expected="present",
            evidence=manifest_path,
        )
    if require_redis_fault:
        add_gate(
            gates,
            "redis_fault_evidence",
            "PASS" if isinstance(evidence.get("redisFaultRecord"), dict) else "HOLD",
            actual="present" if isinstance(evidence.get("redisFaultRecord"), dict) else "missing",
            expected="present",
            evidence=manifest_path,
        )
    event_audit = manifest.get("eventPipelineReplayAudit")
    if require_event_replay:
        add_gate(
            gates,
            "event_replay_audit_evidence",
            "PASS" if isinstance(evidence.get("eventPipelineReplayAuditSummary"), dict)
            and isinstance(event_audit, dict) else "HOLD",
            actual="present" if isinstance(evidence.get("eventPipelineReplayAuditSummary"), dict) else "missing",
            expected="present",
            evidence=manifest_path,
        )
    if isinstance(event_audit, dict):
        status_gate(
            gates,
            "event_replay_audit",
            event_audit.get("status"),
            "PASS",
            manifest_path,
            failure_status="HOLD",
        )
        matching_gate(
            gates,
            "event_replay_failed_gate_count",
            event_audit.get("failedGateCount"),
            0,
            manifest_path,
        )
        if event_audit.get("repairSegmentIndex") is not None:
            segment_summary = event_audit.get("segmentSummary") or {}
            matching_gate(
                gates,
                "event_replay_segment_gate",
                segment_summary.get("segmentGateStatus"),
                "PASS",
                manifest_path,
            )
            matching_gate(
                gates,
                "event_replay_segment_unmaterialized_after",
                segment_summary.get("maxSegmentUnmaterializedCountAfter"),
                0,
                manifest_path,
            )
    elif require_event_replay:
        add_gate(
            gates,
            "event_replay_audit_status",
            "HOLD",
            actual="missing",
            expected="PASS",
            evidence=manifest_path,
        )

slo_summary, error_message = read_json(slo_path, "PISCES_POST_RELEASE_SLO_SUMMARY_FILE")
if gate_json_available(gates, slo_summary, error_message, "post_release_slo", slo_path):
    expected_type_gate(
        gates,
        "post_release_slo",
        slo_summary,
        "pisces-runtime-plane-post-release-slo-review",
        slo_path,
    )
    status_gate(
        gates,
        "post_release_slo",
        slo_summary.get("status"),
        "PASS",
        slo_path,
        failure_status="ROLLBACK",
    )
    matching_gate(
        gates,
        "post_release_slo_release_id",
        slo_summary.get("releaseId"),
        release_id,
        slo_path,
    )
    matching_gate(
        gates,
        "post_release_slo_environment",
        slo_summary.get("environment"),
        environment,
        slo_path,
    )
    status, blocking_count = count_blocking_gates(slo_summary, rollback=True)
    add_gate(
        gates,
        "post_release_slo_blocking_gate_count",
        status,
        actual=blocking_count,
        expected=0,
        evidence=slo_path,
    )
else:
    slo_summary = {}

impact_summary, error_message = read_json(
    impact_path,
    "PISCES_EXPERIMENT_IMPACT_SUMMARY_FILE",
)
if gate_json_available(gates, impact_summary, error_message, "experiment_impact", impact_path):
    expected_type_gate(
        gates,
        "experiment_impact",
        impact_summary,
        "pisces-runtime-plane-experiment-impact-sampling",
        impact_path,
    )
    status_gate(
        gates,
        "experiment_impact",
        impact_summary.get("status"),
        "PASS",
        impact_path,
        failure_status="ROLLBACK",
    )
    if require_trace:
        matching_gate(
            gates,
            "experiment_impact_trace_enabled",
            impact_summary.get("traceEnabled"),
            True,
            impact_path,
        )
    status, blocking_count = count_blocking_gates(impact_summary, rollback=True)
    add_gate(
        gates,
        "experiment_impact_blocking_gate_count",
        status,
        actual=blocking_count,
        expected=0,
        evidence=impact_path,
    )
else:
    impact_summary = {}

decision_summary, error_message = read_json(
    decision_path,
    "PISCES_STAGED_ROLLOUT_DECISION_SUMMARY_FILE",
)
if gate_json_available(gates, decision_summary, error_message, "staged_rollout_decision", decision_path):
    expected_type_gate(
        gates,
        "staged_rollout_decision",
        decision_summary,
        "pisces-runtime-plane-staged-rollout-decision",
        decision_path,
    )
    matching_gate(
        gates,
        "staged_rollout_decision",
        decision_summary.get("decision"),
        "PROCEED",
        decision_path,
        failure_status="ROLLBACK" if decision_summary.get("decision") == "ROLLBACK" else "HOLD",
    )
    matching_gate(
        gates,
        "staged_rollout_release_id",
        decision_summary.get("releaseId"),
        release_id,
        decision_path,
    )
    matching_gate(
        gates,
        "staged_rollout_environment",
        decision_summary.get("environment"),
        environment,
        decision_path,
    )
    status, blocking_count = count_blocking_gates(
        decision_summary,
        rollback=decision_summary.get("decision") == "ROLLBACK",
    )
    add_gate(
        gates,
        "staged_rollout_blocking_gate_count",
        status,
        actual=blocking_count,
        expected=0,
        evidence=decision_path,
    )
else:
    decision_summary = {}

acceptance_record, error_message = read_json(
    acceptance_path,
    "PISCES_PRODUCTION_ACCEPTANCE_RECORD_FILE",
)
if gate_json_available(gates, acceptance_record, error_message, "production_acceptance_record", acceptance_path):
    expected_type_gate(
        gates,
        "production_acceptance_record",
        acceptance_record,
        "pisces-runtime-plane-production-acceptance",
        acceptance_path,
    )
    matching_gate(
        gates,
        "production_acceptance_release_id",
        acceptance_record.get("releaseId"),
        release_id,
        acceptance_path,
    )
    matching_gate(
        gates,
        "production_acceptance_environment",
        acceptance_record.get("environment"),
        environment,
        acceptance_path,
    )
    matching_gate(
        gates,
        "production_acceptance_stage",
        acceptance_record.get("stage"),
        decision_summary.get("stage"),
        acceptance_path,
    )
    final_decision = acceptance_record.get("finalDecision")
    matching_gate(
        gates,
        "production_acceptance_final_decision",
        final_decision,
        "ACCEPT",
        acceptance_path,
        failure_status="ROLLBACK" if final_decision == "ROLLBACK" else "HOLD",
    )
    approved_by = acceptance_record.get("approvedBy")
    add_gate(
        gates,
        "production_acceptance_approved_by",
        "PASS" if isinstance(approved_by, list) and approved_by else "HOLD",
        actual=approved_by,
        expected="non-empty approver list",
        evidence=acceptance_path,
    )
    rollback_plan = acceptance_record.get("rollbackPlan") or {}
    matching_gate(
        gates,
        "production_acceptance_rollback_plan_tested",
        rollback_plan.get("tested"),
        True,
        acceptance_path,
    )
    record_evidence = acceptance_record.get("evidence") or {}
    for key in (
        "releaseEvidenceManifest",
        "postReleaseSloSummary",
        "experimentImpactSummary",
        "stagedRolloutDecisionSummary",
    ):
        add_gate(
            gates,
            f"production_acceptance_evidence_{key}",
            "PASS" if record_evidence.get(key) else "HOLD",
            actual="present" if record_evidence.get(key) else "missing",
            expected="present",
            evidence=acceptance_path,
        )
else:
    acceptance_record = {}

if any(gate["status"] == "ROLLBACK" for gate in gates):
    decision = "ROLLBACK"
elif any(gate["status"] == "HOLD" for gate in gates):
    decision = "HOLD"
else:
    decision = "ACCEPT"

recommended_action = {
    "ACCEPT": "Archive this summary as the final production acceptance evidence.",
    "HOLD": "Do not close the release; fix missing or incomplete evidence and rerun acceptance.",
    "ROLLBACK": "Execute rollback or mitigation and open the incident review workflow.",
}[decision]

summary = {
    "summaryType": "pisces-runtime-plane-production-acceptance-check",
    "summaryVersion": 1,
    "status": "PASS" if decision == "ACCEPT" else decision,
    "decision": decision,
    "generatedAt": now_iso(),
    "releaseId": release_id,
    "environment": environment,
    "stage": decision_summary.get("stage") or acceptance_record.get("stage"),
    "requirements": {
        "packageCi": require_package_ci,
        "cleanGit": require_clean_git,
        "capacityBaseline": require_capacity,
        "redisFault": require_redis_fault,
        "eventReplay": require_event_replay,
        "traceSampling": require_trace,
    },
    "evidence": {
        "releaseEvidenceManifest": manifest_path or None,
        "postReleaseSloSummary": slo_path or None,
        "experimentImpactSummary": impact_path or None,
        "stagedRolloutDecisionSummary": decision_path or None,
        "productionAcceptanceRecord": acceptance_path or None,
    },
    "gates": gates,
    "recommendedAction": recommended_action,
}

with open(output_file, "w", encoding="utf-8") as target:
    json.dump(summary, target, ensure_ascii=False, indent=2, sort_keys=True)
    target.write("\n")

print(f"Production acceptance summary written: {output_file} decision={decision}", file=sys.stderr)
if decision == "ACCEPT":
    sys.exit(0)
if decision == "ROLLBACK":
    sys.exit(2)
sys.exit(1)
PY
}

main "$@"
