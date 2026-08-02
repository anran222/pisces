#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  PISCES_RELEASE_STAGE=canary \
  PISCES_RELEASE_EVIDENCE_MANIFEST_FILE=target/pisces-runtime-release-evidence-archive/<release>/manifest.json \
  PISCES_POST_RELEASE_SLO_SUMMARY_FILE=target/pisces-runtime-post-release-slo-review/summary.json \
  PISCES_EXPERIMENT_IMPACT_SUMMARY_FILE=target/pisces-runtime-experiment-impact-sampling/summary.json \
  PISCES_ROLLOUT_ACCEPTANCE_RECORD_FILE=docs/operations/runtime-plane-staged-rollout-acceptance-sample.json \
  scripts/runtime-plane-staged-rollout-decision.sh

Environment:
  PISCES_RELEASE_STAGE                         preprod | canary | ramp | full | post-release. Default: canary.
  PISCES_RELEASE_EVIDENCE_MANIFEST_FILE        Required release evidence manifest.
  PISCES_POST_RELEASE_SLO_SUMMARY_FILE         Post-release SLO summary JSON.
  PISCES_EXPERIMENT_IMPACT_SUMMARY_FILE        Experiment impact sampling summary JSON.
  PISCES_ROLLOUT_ACCEPTANCE_RECORD_FILE        Optional staged rollout acceptance record JSON.
  PISCES_ROLLOUT_DECISION_OUTPUT_FILE          JSON decision output. Default: target/pisces-runtime-staged-rollout-decision/summary.json.
  PISCES_ROLLOUT_REQUIRE_SLO                   Require SLO summary. Default: true.
  PISCES_ROLLOUT_REQUIRE_IMPACT                Require impact sampling summary. Default: true.
  PISCES_ROLLOUT_REQUIRE_ACCEPTANCE            Require acceptance record. Default: true.
  PISCES_ROLLOUT_REQUIRE_PACKAGE_CI            Require runTests/promtool/ruby in release package evidence. Default: true.
  PISCES_ROLLOUT_REQUIRE_CLEAN_GIT             Require release package gitDirty=false. Default: false.
  PISCES_ROLLOUT_REQUIRE_TRACE_SAMPLING        Require impact summary traceEnabled=true. Default: false.
  PISCES_ROLLOUT_FAILURE_DECISION              hold | rollback | auto. Default: auto.
  PISCES_ROLLOUT_TARGET_TRAFFIC_PERCENT        Optional target traffic percent for this stage.
  PISCES_ROLLOUT_MAX_TRAFFIC_PERCENT           Optional max traffic percent allowed for this stage.
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

  PISCES_RELEASE_STAGE="${PISCES_RELEASE_STAGE:-canary}"
  PISCES_RELEASE_EVIDENCE_MANIFEST_FILE="${PISCES_RELEASE_EVIDENCE_MANIFEST_FILE:-}"
  PISCES_POST_RELEASE_SLO_SUMMARY_FILE="${PISCES_POST_RELEASE_SLO_SUMMARY_FILE:-}"
  PISCES_EXPERIMENT_IMPACT_SUMMARY_FILE="${PISCES_EXPERIMENT_IMPACT_SUMMARY_FILE:-}"
  PISCES_ROLLOUT_ACCEPTANCE_RECORD_FILE="${PISCES_ROLLOUT_ACCEPTANCE_RECORD_FILE:-}"
  PISCES_ROLLOUT_DECISION_OUTPUT_FILE="${PISCES_ROLLOUT_DECISION_OUTPUT_FILE:-target/pisces-runtime-staged-rollout-decision/summary.json}"
  PISCES_ROLLOUT_REQUIRE_SLO="${PISCES_ROLLOUT_REQUIRE_SLO:-true}"
  PISCES_ROLLOUT_REQUIRE_IMPACT="${PISCES_ROLLOUT_REQUIRE_IMPACT:-true}"
  PISCES_ROLLOUT_REQUIRE_ACCEPTANCE="${PISCES_ROLLOUT_REQUIRE_ACCEPTANCE:-true}"
  PISCES_ROLLOUT_REQUIRE_PACKAGE_CI="${PISCES_ROLLOUT_REQUIRE_PACKAGE_CI:-true}"
  PISCES_ROLLOUT_REQUIRE_CLEAN_GIT="${PISCES_ROLLOUT_REQUIRE_CLEAN_GIT:-false}"
  PISCES_ROLLOUT_REQUIRE_TRACE_SAMPLING="${PISCES_ROLLOUT_REQUIRE_TRACE_SAMPLING:-false}"
  PISCES_ROLLOUT_FAILURE_DECISION="${PISCES_ROLLOUT_FAILURE_DECISION:-auto}"
  PISCES_ROLLOUT_TARGET_TRAFFIC_PERCENT="${PISCES_ROLLOUT_TARGET_TRAFFIC_PERCENT:-}"
  PISCES_ROLLOUT_MAX_TRAFFIC_PERCENT="${PISCES_ROLLOUT_MAX_TRAFFIC_PERCENT:-}"

  [[ -n "$PISCES_RELEASE_EVIDENCE_MANIFEST_FILE" ]] || die "PISCES_RELEASE_EVIDENCE_MANIFEST_FILE is required"

  export PISCES_RELEASE_STAGE
  export PISCES_RELEASE_EVIDENCE_MANIFEST_FILE
  export PISCES_POST_RELEASE_SLO_SUMMARY_FILE
  export PISCES_EXPERIMENT_IMPACT_SUMMARY_FILE
  export PISCES_ROLLOUT_ACCEPTANCE_RECORD_FILE
  export PISCES_ROLLOUT_REQUIRE_SLO
  export PISCES_ROLLOUT_REQUIRE_IMPACT
  export PISCES_ROLLOUT_REQUIRE_ACCEPTANCE
  export PISCES_ROLLOUT_REQUIRE_PACKAGE_CI
  export PISCES_ROLLOUT_REQUIRE_CLEAN_GIT
  export PISCES_ROLLOUT_REQUIRE_TRACE_SAMPLING
  export PISCES_ROLLOUT_FAILURE_DECISION
  export PISCES_ROLLOUT_TARGET_TRAFFIC_PERCENT
  export PISCES_ROLLOUT_MAX_TRAFFIC_PERCENT

  local output_file
  output_file="$(resolve_output_file "$PISCES_ROLLOUT_DECISION_OUTPUT_FILE")"
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


def parse_float(value, field):
    if value is None or str(value).strip() == "":
        return None
    try:
        return float(value)
    except (TypeError, ValueError) as exc:
        raise SystemExit(f"{field} must be numeric: {value}") from exc


def read_json_if_present(path, field, required):
    if not path:
        if required:
            return None, f"{field} is required"
        return None, None
    candidate = Path(path)
    if not candidate.is_file():
        if required:
            return None, f"{field} not found: {path}"
        return None, None
    try:
        with open(candidate, encoding="utf-8") as source:
            return json.load(source), None
    except Exception as exc:
        return None, f"Invalid JSON file {path}: {exc}"


def get_path(payload, path, default=None):
    value = payload
    for part in path.split("."):
        if not isinstance(value, dict) or part not in value:
            return default
        value = value[part]
    return value


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


def status_gate(gates, name, payload, expected_type, expected_status, evidence_path):
    actual_type = payload.get("manifestType") or payload.get("summaryType") or payload.get("reportType") or payload.get("recordType")
    add_gate(
        gates,
        f"{name}_type",
        "PASS" if actual_type == expected_type else "HOLD",
        actual=actual_type,
        expected=expected_type,
        evidence=evidence_path,
    )
    actual_status = payload.get("status")
    add_gate(
        gates,
        f"{name}_status",
        "PASS" if actual_status == expected_status else "FAIL",
        actual=actual_status,
        expected=expected_status,
        evidence=evidence_path,
    )


def failure_decision(stage, override):
    normalized = override.strip().lower()
    if normalized in {"hold", "rollback"}:
        return normalized.upper()
    if normalized != "auto":
        raise SystemExit("PISCES_ROLLOUT_FAILURE_DECISION must be hold, rollback, or auto")
    if stage in {"canary", "ramp", "full", "post-release"}:
        return "ROLLBACK"
    return "HOLD"


stage = os.environ["PISCES_RELEASE_STAGE"].strip()
allowed_stages = {"preprod", "canary", "ramp", "full", "post-release"}
if stage not in allowed_stages:
    raise SystemExit(f"PISCES_RELEASE_STAGE must be one of {sorted(allowed_stages)}: {stage}")

require_slo = parse_bool(os.environ["PISCES_ROLLOUT_REQUIRE_SLO"], "PISCES_ROLLOUT_REQUIRE_SLO")
require_impact = parse_bool(os.environ["PISCES_ROLLOUT_REQUIRE_IMPACT"], "PISCES_ROLLOUT_REQUIRE_IMPACT")
require_acceptance = parse_bool(os.environ["PISCES_ROLLOUT_REQUIRE_ACCEPTANCE"], "PISCES_ROLLOUT_REQUIRE_ACCEPTANCE")
require_package_ci = parse_bool(os.environ["PISCES_ROLLOUT_REQUIRE_PACKAGE_CI"], "PISCES_ROLLOUT_REQUIRE_PACKAGE_CI")
require_clean_git = parse_bool(os.environ["PISCES_ROLLOUT_REQUIRE_CLEAN_GIT"], "PISCES_ROLLOUT_REQUIRE_CLEAN_GIT")
require_trace_sampling = parse_bool(os.environ["PISCES_ROLLOUT_REQUIRE_TRACE_SAMPLING"], "PISCES_ROLLOUT_REQUIRE_TRACE_SAMPLING")
target_traffic_percent = parse_float(
    os.environ["PISCES_ROLLOUT_TARGET_TRAFFIC_PERCENT"],
    "PISCES_ROLLOUT_TARGET_TRAFFIC_PERCENT",
)
max_traffic_percent = parse_float(
    os.environ["PISCES_ROLLOUT_MAX_TRAFFIC_PERCENT"],
    "PISCES_ROLLOUT_MAX_TRAFFIC_PERCENT",
)
failure_decision_value = failure_decision(stage, os.environ["PISCES_ROLLOUT_FAILURE_DECISION"])

gates = []
manifest_path = os.environ["PISCES_RELEASE_EVIDENCE_MANIFEST_FILE"]
manifest, error_message = read_json_if_present(
    manifest_path,
    "PISCES_RELEASE_EVIDENCE_MANIFEST_FILE",
    True,
)
if error_message:
    add_gate(gates, "release_evidence_available", "HOLD", actual="missing", expected="valid JSON", evidence=manifest_path, reason=error_message)
    manifest = {}
else:
    add_gate(gates, "release_evidence_available", "PASS", actual="present", expected="present", evidence=manifest_path)
    status_gate(
        gates,
        "release_evidence",
        manifest,
        "pisces-runtime-plane-release-evidence",
        None,
        manifest_path,
    )
    gates[-1]["status"] = "PASS"
    gates[-1]["actual"] = "present"
    gates[-1]["expected"] = "valid manifest"

release_id = manifest.get("releaseId")
environment = manifest.get("environment")
release_package = manifest.get("releasePackage") or {}
if manifest:
    add_gate(
        gates,
        "release_package_status",
        "PASS" if release_package.get("status") == "PASS" else "HOLD",
        actual=release_package.get("status"),
        expected="PASS",
        evidence=manifest_path,
    )
    if require_package_ci:
        for field in ("runTests", "requirePromtool", "requireRuby"):
            add_gate(
                gates,
                f"release_package_{field}",
                "PASS" if release_package.get(field) == "true" else "HOLD",
                actual=release_package.get(field),
                expected="true",
                evidence=manifest_path,
            )
    if require_clean_git:
        add_gate(
            gates,
            "release_package_git_dirty",
            "PASS" if release_package.get("gitDirty") == "false" else "HOLD",
            actual=release_package.get("gitDirty"),
            expected="false",
            evidence=manifest_path,
        )
    comparison = manifest.get("comparison") or {}
    if comparison.get("enabled"):
        add_gate(
            gates,
            "release_manifest_comparison",
            "PASS" if comparison.get("status") == "MATCH" else "HOLD",
            actual=comparison.get("status"),
            expected="MATCH",
            evidence=manifest_path,
        )

slo_path = os.environ["PISCES_POST_RELEASE_SLO_SUMMARY_FILE"]
slo_summary, error_message = read_json_if_present(
    slo_path,
    "PISCES_POST_RELEASE_SLO_SUMMARY_FILE",
    require_slo,
)
if error_message:
    add_gate(gates, "post_release_slo_available", "HOLD", actual="missing", expected="valid JSON", evidence=slo_path, reason=error_message)
elif slo_summary:
    add_gate(gates, "post_release_slo_available", "PASS", actual="present", expected="present", evidence=slo_path)
    status_gate(
        gates,
        "post_release_slo",
        slo_summary,
        "pisces-runtime-plane-post-release-slo-review",
        "PASS",
        slo_path,
    )
    if slo_summary.get("releaseId") != release_id:
        add_gate(
            gates,
            "post_release_slo_release_id",
            "HOLD",
            actual=slo_summary.get("releaseId"),
            expected=release_id,
            evidence=slo_path,
        )
    if slo_summary.get("environment") != environment:
        add_gate(
            gates,
            "post_release_slo_environment",
            "HOLD",
            actual=slo_summary.get("environment"),
            expected=environment,
            evidence=slo_path,
        )
else:
    add_gate(gates, "post_release_slo_available", "SKIP", actual="not required", expected="not required")

impact_path = os.environ["PISCES_EXPERIMENT_IMPACT_SUMMARY_FILE"]
impact_summary, error_message = read_json_if_present(
    impact_path,
    "PISCES_EXPERIMENT_IMPACT_SUMMARY_FILE",
    require_impact,
)
if error_message:
    add_gate(gates, "experiment_impact_available", "HOLD", actual="missing", expected="valid JSON", evidence=impact_path, reason=error_message)
elif impact_summary:
    add_gate(gates, "experiment_impact_available", "PASS", actual="present", expected="present", evidence=impact_path)
    actual_type = impact_summary.get("reportType") or impact_summary.get("summaryType")
    add_gate(
        gates,
        "experiment_impact_type",
        "PASS" if actual_type == "pisces-runtime-plane-experiment-impact-sampling" else "HOLD",
        actual=actual_type,
        expected="pisces-runtime-plane-experiment-impact-sampling",
        evidence=impact_path,
    )
    add_gate(
        gates,
        "experiment_impact_status",
        "PASS" if impact_summary.get("status") == "PASS" else "FAIL",
        actual=impact_summary.get("status"),
        expected="PASS",
        evidence=impact_path,
    )
    if require_trace_sampling:
        add_gate(
            gates,
            "experiment_impact_trace_enabled",
            "PASS" if impact_summary.get("traceEnabled") is True else "HOLD",
            actual=impact_summary.get("traceEnabled"),
            expected=True,
            evidence=impact_path,
        )
else:
    add_gate(gates, "experiment_impact_available", "SKIP", actual="not required", expected="not required")

acceptance_path = os.environ["PISCES_ROLLOUT_ACCEPTANCE_RECORD_FILE"]
acceptance, error_message = read_json_if_present(
    acceptance_path,
    "PISCES_ROLLOUT_ACCEPTANCE_RECORD_FILE",
    require_acceptance,
)
if error_message:
    add_gate(gates, "rollout_acceptance_available", "HOLD", actual="missing", expected="valid JSON", evidence=acceptance_path, reason=error_message)
elif acceptance:
    add_gate(gates, "rollout_acceptance_available", "PASS", actual="present", expected="present", evidence=acceptance_path)
    add_gate(
        gates,
        "rollout_acceptance_type",
        "PASS" if acceptance.get("recordType") == "pisces-runtime-plane-staged-rollout-acceptance" else "HOLD",
        actual=acceptance.get("recordType"),
        expected="pisces-runtime-plane-staged-rollout-acceptance",
        evidence=acceptance_path,
    )
    record_decision = acceptance.get("decision")
    if record_decision == "ROLLBACK":
        record_status = "ROLLBACK"
    elif record_decision == "PROCEED":
        record_status = "PASS"
    else:
        record_status = "HOLD"
    add_gate(
        gates,
        "rollout_acceptance_decision",
        record_status,
        actual=record_decision,
        expected="PROCEED",
        evidence=acceptance_path,
    )
    for field_name, expected_value, actual_value in (
        ("release_id", release_id, acceptance.get("releaseId")),
        ("stage", stage, acceptance.get("stage")),
        ("environment", environment, acceptance.get("environment")),
    ):
        add_gate(
            gates,
            f"rollout_acceptance_{field_name}",
            "PASS" if actual_value == expected_value else "HOLD",
            actual=actual_value,
            expected=expected_value,
            evidence=acceptance_path,
        )
    approved_by = acceptance.get("approvedBy")
    add_gate(
        gates,
        "rollout_acceptance_approved_by",
        "PASS" if isinstance(approved_by, list) and len(approved_by) > 0 else "HOLD",
        actual=approved_by,
        expected="non-empty approver list",
        evidence=acceptance_path,
    )
    rollback_plan = acceptance.get("rollbackPlan") or {}
    add_gate(
        gates,
        "rollout_acceptance_rollback_plan_tested",
        "PASS" if rollback_plan.get("tested") is True else "HOLD",
        actual=rollback_plan.get("tested"),
        expected=True,
        evidence=acceptance_path,
    )
    record_target_traffic = parse_float(
        acceptance.get("targetTrafficPercent"),
        "rolloutAcceptance.targetTrafficPercent",
    )
    if target_traffic_percent is None:
        target_traffic_percent = record_target_traffic
else:
    add_gate(gates, "rollout_acceptance_available", "SKIP", actual="not required", expected="not required")

if target_traffic_percent is not None:
    add_gate(
        gates,
        "target_traffic_percent_non_negative",
        "PASS" if target_traffic_percent >= 0 else "HOLD",
        actual=target_traffic_percent,
        expected=">= 0",
    )
if target_traffic_percent is not None and max_traffic_percent is not None:
    add_gate(
        gates,
        "target_traffic_percent_within_stage_cap",
        "PASS" if target_traffic_percent <= max_traffic_percent else "HOLD",
        actual=target_traffic_percent,
        expected=f"<= {max_traffic_percent}",
    )

rollback_gate_names = {
    "post_release_slo_status",
    "experiment_impact_status",
}
normalized_gates = []
for gate in gates:
    if gate["status"] == "FAIL" and gate["name"] in rollback_gate_names and failure_decision_value == "ROLLBACK":
        gate = dict(gate)
        gate["status"] = "ROLLBACK"
        gate["reason"] = gate.get("reason") or "Runtime health evidence failed during production rollout."
    normalized_gates.append(gate)
gates = normalized_gates

if any(gate["status"] == "ROLLBACK" for gate in gates):
    decision = "ROLLBACK"
elif any(gate["status"] in {"FAIL", "HOLD"} for gate in gates):
    decision = "HOLD"
else:
    decision = "PROCEED"

recommended_action = {
    "PROCEED": "Continue to the next staged rollout step.",
    "HOLD": "Do not advance traffic; fix missing or failed gates and rerun the decision.",
    "ROLLBACK": "Stop rollout, execute rollback or mitigation, and open incident review.",
}[decision]

summary = {
    "summaryType": "pisces-runtime-plane-staged-rollout-decision",
    "summaryVersion": 1,
    "decision": decision,
    "status": "PASS" if decision == "PROCEED" else decision,
    "generatedAt": now_iso(),
    "releaseId": release_id,
    "environment": environment,
    "stage": stage,
    "failureDecision": failure_decision_value,
    "targetTrafficPercent": target_traffic_percent,
    "maxTrafficPercent": max_traffic_percent,
    "evidence": {
        "releaseEvidenceManifest": manifest_path,
        "postReleaseSloSummary": slo_path or None,
        "experimentImpactSummary": impact_path or None,
        "rolloutAcceptanceRecord": acceptance_path or None,
    },
    "gates": gates,
    "recommendedAction": recommended_action,
}

with open(output_file, "w", encoding="utf-8") as target:
    json.dump(summary, target, ensure_ascii=False, indent=2, sort_keys=True)
    target.write("\n")

print(f"Staged rollout decision written: {output_file} decision={decision}", file=sys.stderr)
if decision == "PROCEED":
    sys.exit(0)
if decision == "ROLLBACK":
    sys.exit(2)
sys.exit(1)
PY
}

main "$@"
