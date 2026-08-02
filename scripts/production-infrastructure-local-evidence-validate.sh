#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  PISCES_RELEASE_ID="local-20260730-runtime-plane" \
  PISCES_PREPROD_DRILL_RECORD_FILE=target/.../preprod-drill-record.md \
  PISCES_CAPACITY_BASELINE_MANIFEST_FILE=target/.../capacity-baseline-manifest.json \
  PISCES_REDIS_FAULT_RECORD_FILE=target/.../redis-fault-record.txt \
  PISCES_EVENT_REPLAY_AUDIT_SUMMARY_FILE=target/.../event-replay-audit-summary.json \
  PISCES_POST_RELEASE_METRICS_FILE=target/.../post-release-metrics.json \
  PISCES_EXPERIMENT_IMPACT_SUMMARY_FILE=target/.../experiment-impact-summary.json \
  PISCES_ROLLOUT_ACCEPTANCE_RECORD_FILE=target/.../full-rollout-acceptance.json \
  PISCES_PRODUCTION_ACCEPTANCE_RECORD_FILE=target/.../production-acceptance-record.json \
  scripts/production-infrastructure-local-evidence-validate.sh

Environment:
  PISCES_REPO_ROOT                                Repository root. Default: inferred from this script.
  PISCES_RELEASE_ID                               Required local release ID.
  PISCES_EXPECTED_GIT_SHA                         Optional expected Git SHA. Default: current repo HEAD when available.
  PISCES_TARGET_ENVIRONMENT                       Target environment. Default: local.
  PISCES_LOCAL_EVIDENCE_VALIDATE_OUTPUT_FILE      JSON output. Default: target/pisces-production-infrastructure-local-evidence-validate/summary.json.

Required evidence inputs:
  PISCES_PREPROD_DRILL_RECORD_FILE
  PISCES_CAPACITY_BASELINE_MANIFEST_FILE
  PISCES_REDIS_FAULT_RECORD_FILE
  PISCES_EVENT_REPLAY_AUDIT_SUMMARY_FILE
  PISCES_POST_RELEASE_METRICS_FILE
  PISCES_EXPERIMENT_IMPACT_SUMMARY_FILE
  PISCES_ROLLOUT_ACCEPTANCE_RECORD_FILE
  PISCES_PRODUCTION_ACCEPTANCE_RECORD_FILE
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

resolve_git_sha() {
  if [[ -n "${PISCES_EXPECTED_GIT_SHA:-}" ]]; then
    printf '%s' "$PISCES_EXPECTED_GIT_SHA"
    return
  fi
  if command -v git >/dev/null 2>&1 && git -C "$PISCES_REPO_ROOT" rev-parse --show-toplevel >/dev/null 2>&1; then
    git -C "$PISCES_REPO_ROOT" rev-parse HEAD
    return
  fi
  printf ''
}

main() {
  if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
    usage
    exit 0
  fi

  command -v python3 >/dev/null 2>&1 || die "Missing command: python3"

  PISCES_REPO_ROOT="$(resolve_repo_root)"
  PISCES_RELEASE_ID="${PISCES_RELEASE_ID:-}"
  PISCES_TARGET_ENVIRONMENT="${PISCES_TARGET_ENVIRONMENT:-local}"
  PISCES_EXPECTED_GIT_SHA="$(resolve_git_sha)"
  PISCES_LOCAL_EVIDENCE_VALIDATE_OUTPUT_FILE="${PISCES_LOCAL_EVIDENCE_VALIDATE_OUTPUT_FILE:-target/pisces-production-infrastructure-local-evidence-validate/summary.json}"

  local output_file
  output_file="$(resolve_path "$PISCES_LOCAL_EVIDENCE_VALIDATE_OUTPUT_FILE")"
  mkdir -p "$(dirname "$output_file")"

  export PISCES_REPO_ROOT
  export PISCES_RELEASE_ID
  export PISCES_TARGET_ENVIRONMENT
  export PISCES_EXPECTED_GIT_SHA
  export PISCES_LOCAL_EVIDENCE_VALIDATE_OUTPUT_FILE="$output_file"

  python3 <<'PY'
import json
import os
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

REQUIRED_INPUTS = [
    ("PISCES_PREPROD_DRILL_RECORD_FILE", "preprodDrillRecord", "markdown"),
    ("PISCES_CAPACITY_BASELINE_MANIFEST_FILE", "capacityBaselineManifest", "json"),
    ("PISCES_REDIS_FAULT_RECORD_FILE", "redisFaultRecord", "text"),
    ("PISCES_EVENT_REPLAY_AUDIT_SUMMARY_FILE", "eventReplayAuditSummary", "json"),
    ("PISCES_POST_RELEASE_METRICS_FILE", "postReleaseMetrics", "json"),
    ("PISCES_EXPERIMENT_IMPACT_SUMMARY_FILE", "experimentImpactSummary", "json"),
    ("PISCES_ROLLOUT_ACCEPTANCE_RECORD_FILE", "rolloutAcceptanceRecord", "json"),
    ("PISCES_PRODUCTION_ACCEPTANCE_RECORD_FILE", "productionAcceptanceRecord", "json"),
]

PLACEHOLDER_RE = re.compile(r"TODO[A-Z0-9_]*|LOCAL-TODO")
JSON_TYPES = (dict, list, str, int, float, bool, type(None))


def now_iso():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def resolve_path(value):
    if not value:
        return None
    candidate = Path(value)
    if candidate.is_absolute():
        return candidate
    return Path(os.environ["PISCES_REPO_ROOT"]) / value


def add_gate(gates, name, status, actual=None, expected=None, evidence=None, reason=None):
    gate = {
        "name": name,
        "status": status,
        "actual": actual,
        "expected": expected,
    }
    if evidence is not None:
        gate["evidence"] = str(evidence)
    if reason:
        gate["reason"] = reason
    gates.append(gate)


def as_number(value):
    if isinstance(value, bool) or value is None:
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def get_path(payload, dotted_path, default=None):
    value = payload
    for part in dotted_path.split("."):
        if not isinstance(value, dict) or part not in value:
            return default
        value = value[part]
    return value


def placeholder_findings(path):
    findings = []
    try:
        for index, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
            if PLACEHOLDER_RE.search(line):
                findings.append({"line": index, "text": line.strip()[:240]})
    except UnicodeDecodeError:
        findings.append({"line": None, "text": "file is not valid UTF-8 text"})
    return findings


def recursively_invalid_json_values(value, prefix="$"):
    findings = []
    if not isinstance(value, JSON_TYPES):
        findings.append(f"{prefix}: unsupported JSON value")
        return findings
    if isinstance(value, str):
        if PLACEHOLDER_RE.search(value):
            findings.append(f"{prefix}: placeholder value")
        if not value.strip():
            findings.append(f"{prefix}: blank string")
        return findings
    if isinstance(value, list):
        for index, item in enumerate(value):
            findings.extend(recursively_invalid_json_values(item, f"{prefix}[{index}]"))
        return findings
    if isinstance(value, dict):
        for key, item in value.items():
            findings.extend(recursively_invalid_json_values(item, f"{prefix}.{key}"))
    return findings


def read_required_files(gates):
    files = {}
    payloads = {}
    for env_name, evidence_name, file_type in REQUIRED_INPUTS:
        value = os.environ.get(env_name, "")
        path = resolve_path(value)
        if not value:
            add_gate(
                gates,
                f"{evidence_name}_env",
                "HOLD",
                actual="missing",
                expected=f"{env_name} set",
            )
            continue
        if not path.is_file():
            add_gate(
                gates,
                f"{evidence_name}_file",
                "HOLD",
                actual=str(path),
                expected="existing file",
            )
            continue
        files[evidence_name] = path
        add_gate(
            gates,
            f"{evidence_name}_file",
            "PASS",
            actual=str(path),
            expected="existing file",
            evidence=path,
        )
        findings = placeholder_findings(path)
        add_gate(
            gates,
            f"{evidence_name}_placeholder_scan",
            "PASS" if not findings else "HOLD",
            actual=len(findings),
            expected=0,
            evidence=path,
            reason=json.dumps(findings[:10], ensure_ascii=False) if findings else None,
        )
        if file_type == "json":
            try:
                payload = json.loads(path.read_text(encoding="utf-8"))
            except Exception as exc:
                add_gate(
                    gates,
                    f"{evidence_name}_json_parse",
                    "FAIL",
                    actual="invalid",
                    expected="valid JSON",
                    evidence=path,
                    reason=str(exc),
                )
                continue
            add_gate(
                gates,
                f"{evidence_name}_json_parse",
                "PASS",
                actual="valid",
                expected="valid JSON",
                evidence=path,
            )
            invalid_values = recursively_invalid_json_values(payload)
            add_gate(
                gates,
                f"{evidence_name}_json_values",
                "PASS" if not invalid_values else "HOLD",
                actual=len(invalid_values),
                expected=0,
                evidence=path,
                reason=", ".join(invalid_values[:12]) if invalid_values else None,
            )
            payloads[evidence_name] = payload
        else:
            try:
                payloads[evidence_name] = path.read_text(encoding="utf-8")
            except UnicodeDecodeError as exc:
                add_gate(
                    gates,
                    f"{evidence_name}_text_parse",
                    "FAIL",
                    actual="invalid",
                    expected="UTF-8 text",
                    evidence=path,
                    reason=str(exc),
                )
    return files, payloads


def expect_equal(gates, name, actual, expected, evidence, failure_status="HOLD"):
    add_gate(
        gates,
        name,
        "PASS" if actual == expected else failure_status,
        actual=actual,
        expected=expected,
        evidence=evidence,
    )


def expect_present(gates, name, actual, evidence):
    add_gate(
        gates,
        name,
        "PASS" if actual not in (None, "", [], {}) else "HOLD",
        actual=actual if actual not in (None, "") else "missing",
        expected="present",
        evidence=evidence,
    )


def expect_true(gates, name, actual, evidence):
    add_gate(
        gates,
        name,
        "PASS" if actual is True else "HOLD",
        actual=actual,
        expected=True,
        evidence=evidence,
    )


def expect_number(gates, name, actual, evidence, minimum=None, maximum=None, failure_status="FAIL"):
    number = as_number(actual)
    passed = number is not None
    expected = "numeric"
    if passed and minimum is not None:
        passed = number >= minimum
        expected = f">= {minimum}"
    if passed and maximum is not None:
        passed = number <= maximum
        expected = f"<= {maximum}"
    add_gate(
        gates,
        name,
        "PASS" if passed else failure_status if number is not None else "HOLD",
        actual=actual,
        expected=expected,
        evidence=evidence,
    )


def gate_list_all_pass(gates, name, gate_items, evidence):
    if not isinstance(gate_items, list) or not gate_items:
        add_gate(
            gates,
            name,
            "HOLD",
            actual="missing",
            expected="non-empty gate list",
            evidence=evidence,
        )
        return
    blocking = [
        item for item in gate_items
        if not isinstance(item, dict) or item.get("status") not in {"PASS", "SKIP"}
    ]
    add_gate(
        gates,
        name,
        "PASS" if not blocking else "FAIL",
        actual=len(blocking),
        expected=0,
        evidence=evidence,
        reason=json.dumps(blocking[:10], ensure_ascii=False) if blocking else None,
    )


def redis_fault_drill_passed(payloads):
    text = payloads.get("redisFaultRecord")
    if not isinstance(text, str):
        return False
    for phase in ("baseline", "during-fault", "recovery"):
        match = re.search(rf"^{re.escape(phase)}\s*:\s*(\S+)", text, re.IGNORECASE | re.MULTILINE)
        if not match or match.group(1).upper() != "PASS":
            return False
    return True


def validate_common_identity(gates, name, payload, evidence, require_release=False, require_environment=False):
    release_id = os.environ["PISCES_RELEASE_ID"]
    target_environment = os.environ["PISCES_TARGET_ENVIRONMENT"]
    if "releaseId" in payload or require_release:
        expect_equal(gates, f"{name}_release_id", payload.get("releaseId"), release_id, evidence)
    if "environment" in payload or require_environment:
        expect_equal(gates, f"{name}_environment", payload.get("environment"), target_environment, evidence)


def validate_preprod(gates, files, payloads):
    evidence = files.get("preprodDrillRecord")
    markdown = payloads.get("preprodDrillRecord")
    if not evidence or not isinstance(markdown, str):
        return
    release_id = os.environ["PISCES_RELEASE_ID"]
    expected_git_sha = os.environ.get("PISCES_EXPECTED_GIT_SHA", "")
    expect_equal(
        gates,
        "preprod_record_release_id",
        release_id in markdown,
        True,
        evidence,
    )
    if expected_git_sha:
        expect_equal(
            gates,
            "preprod_record_git_sha",
            expected_git_sha in markdown,
            True,
            evidence,
        )
    for section in (
        "Release Package Report",
        "Runtime Contract Smoke",
        "Capacity Baseline",
        "Redis Fault Injection",
        "Observability",
        "Event Pipeline Replay Audit",
        "Decision",
    ):
        expect_equal(
            gates,
            f"preprod_record_section_{section.lower().replace(' ', '_')}",
            section in markdown,
            True,
            evidence,
        )


def validate_capacity(gates, files, payloads):
    evidence = files.get("capacityBaselineManifest")
    payload = payloads.get("capacityBaselineManifest")
    if not evidence or not isinstance(payload, dict):
        return
    validate_common_identity(gates, "capacity_baseline", payload, evidence, True, True)
    expect_present(gates, "capacity_baseline_experiment_id", payload.get("experimentId"), evidence)
    expected_git_sha = os.environ.get("PISCES_EXPECTED_GIT_SHA", "")
    if expected_git_sha:
        expect_equal(gates, "capacity_baseline_git_sha", payload.get("gitSha"), expected_git_sha, evidence)
    else:
        expect_present(gates, "capacity_baseline_git_sha", payload.get("gitSha"), evidence)
    expect_number(gates, "capacity_baseline_max_error_rate", payload.get("maxErrorRate"), evidence, maximum=0)
    expect_number(gates, "capacity_baseline_max_p95_ms", payload.get("maxP95Ms"), evidence, minimum=0)
    expect_number(gates, "capacity_baseline_max_p99_ms", payload.get("maxP99Ms"), evidence, minimum=0)


def validate_redis_fault(gates, files, payloads):
    evidence = files.get("redisFaultRecord")
    text = payloads.get("redisFaultRecord")
    if not evidence or not isinstance(text, str):
        return
    lower_text = text.lower()
    for phase in ("baseline", "during-fault", "recovery"):
        match = re.search(rf"^{re.escape(phase)}\s*:\s*(.+)$", text, re.IGNORECASE | re.MULTILINE)
        actual = match.group(1).strip() if match else None
        status = actual is not None and actual and not PLACEHOLDER_RE.search(actual)
        expect_equal(gates, f"redis_fault_{phase.replace('-', '_')}", status, True, evidence)
    expect_equal(gates, "redis_fault_record_mentions_recovery", "recovery" in lower_text, True, evidence)


def validate_event_replay(gates, files, payloads):
    evidence = files.get("eventReplayAuditSummary")
    payload = payloads.get("eventReplayAuditSummary")
    if not evidence or not isinstance(payload, dict):
        return
    validate_common_identity(gates, "event_replay", payload, evidence)
    expect_equal(
        gates,
        "event_replay_summary_type",
        payload.get("summaryType"),
        "pisces-event-pipeline-replay-audit",
        evidence,
    )
    expect_equal(gates, "event_replay_status", payload.get("status"), "PASS", evidence, "FAIL")
    failed_gate_count = payload.get("failedGateCount")
    if failed_gate_count is None and isinstance(payload.get("gates"), list):
        failed_gate_count = len([
            item for item in payload.get("gates")
            if isinstance(item, dict) and item.get("status") == "FAIL"
        ])
    expect_number(gates, "event_replay_failed_gate_count", failed_gate_count, evidence, maximum=0)
    expect_number(gates, "event_replay_repair_segment_index", payload.get("repairSegmentIndex"), evidence, minimum=0)
    segment_summary = payload.get("segmentSummary") or {}
    if not segment_summary:
        replay_plan = payload.get("replayPlan") or {}
        replay_plan_after_repair = payload.get("replayPlanAfterRepair") or {}
        segment_gate = next(
            (
                item for item in payload.get("gates") or []
                if isinstance(item, dict) and item.get("name") == "replay_plan_segments_generated"
            ),
            {},
        )
        segment_summary = {
            "segmentGateStatus": segment_gate.get("status"),
            "segmentCount": replay_plan.get("segmentCount"),
            "maxSegmentUnmaterializedCountAfter": replay_plan_after_repair.get("maxSegmentUnmaterializedCount"),
            "maxSegmentUnmaterializedCountBefore": replay_plan.get("maxSegmentUnmaterializedCount"),
        }
    expect_equal(
        gates,
        "event_replay_segment_gate_status",
        segment_summary.get("segmentGateStatus"),
        "PASS",
        evidence,
    )
    expect_number(
        gates,
        "event_replay_segment_unmaterialized_after",
        segment_summary.get("maxSegmentUnmaterializedCountAfter"),
        evidence,
        maximum=0,
    )
    gate_items = payload.get("gates")
    gate_list_all_pass(gates, "event_replay_gates_all_pass", gate_items, evidence)
    gate_names = {
        item.get("name")
        for item in gate_items or []
        if isinstance(item, dict)
    }
    for required_name in (
        "replay_plan_segments_generated",
        "repair_materialization_operation_success",
        "post_repair_replay_plan_unmaterialized_count",
    ):
        expect_equal(
            gates,
            f"event_replay_gate_{required_name}",
            required_name in gate_names,
            True,
            evidence,
        )


def validate_post_release_metrics(gates, files, payloads):
    evidence = files.get("postReleaseMetrics")
    payload = payloads.get("postReleaseMetrics")
    if not evidence or not isinstance(payload, dict):
        return
    cache_error_delta = get_path(payload, "cache.errorDelta")
    cache_error_delta_number = as_number(cache_error_delta)
    if cache_error_delta_number and cache_error_delta_number > 0 and redis_fault_drill_passed(payloads):
        add_gate(
            gates,
            "post_release_cache_error_delta",
            "PASS",
            actual=cache_error_delta,
            expected="attributed to passing Redis fault drill",
            evidence=evidence,
            reason="redisFaultRecord baseline/during-fault/recovery gates all passed",
        )
    else:
        expect_number(gates, "post_release_cache_error_delta", cache_error_delta, evidence, maximum=0)
    metric_thresholds = (
        ("post_release_assignment_error_rate", "assignment.errorRate", 0),
        ("post_release_broadcast_publish_error_delta", "broadcast.publishErrorDelta", 0),
        ("post_release_broadcast_invalid_delta", "broadcast.invalidDelta", 0),
        ("post_release_broadcast_listener_error_delta", "broadcast.listenerErrorDelta", 0),
        ("post_release_sdk_request_failure_delta", "sdk.requestFailureDelta", 0),
        ("post_release_sdk_stale_fallback_delta", "sdk.staleFallbackDelta", 0),
    )
    for name, path, maximum in metric_thresholds:
        expect_number(gates, name, get_path(payload, path), evidence, maximum=maximum)
    for name, path in (
        ("post_release_assignment_p95_ms", "assignment.p95Ms"),
        ("post_release_assignment_p99_ms", "assignment.p99Ms"),
        ("post_release_assignment_requests", "assignment.requests"),
        ("post_release_sdk_retry_delta", "sdk.retryDelta"),
    ):
        expect_number(gates, name, get_path(payload, path), evidence, minimum=0)
    expect_present(gates, "post_release_window_started_at", get_path(payload, "window.startedAt"), evidence)
    expect_present(gates, "post_release_window_finished_at", get_path(payload, "window.finishedAt"), evidence)


def validate_impact(gates, files, payloads):
    evidence = files.get("experimentImpactSummary")
    payload = payloads.get("experimentImpactSummary")
    if not evidence or not isinstance(payload, dict):
        return
    validate_common_identity(gates, "experiment_impact", payload, evidence, require_environment=True)
    actual_type = payload.get("reportType") or payload.get("summaryType")
    expect_equal(
        gates,
        "experiment_impact_type",
        actual_type,
        "pisces-runtime-plane-experiment-impact-sampling",
        evidence,
    )
    expect_equal(gates, "experiment_impact_status", payload.get("status"), "PASS", evidence, "FAIL")
    expect_true(gates, "experiment_impact_trace_enabled", payload.get("traceEnabled"), evidence)
    expect_number(gates, "experiment_impact_visitor_count", payload.get("visitorCount"), evidence, minimum=1)
    expect_present(gates, "experiment_impact_instance_urls", payload.get("instanceUrls"), evidence)
    expect_present(gates, "experiment_impact_experiment_ids", payload.get("experimentIds"), evidence)
    gate_list_all_pass(gates, "experiment_impact_gates_all_pass", payload.get("gates"), evidence)


def validate_rollout_acceptance(gates, files, payloads):
    evidence = files.get("rolloutAcceptanceRecord")
    payload = payloads.get("rolloutAcceptanceRecord")
    if not evidence or not isinstance(payload, dict):
        return
    validate_common_identity(gates, "rollout_acceptance", payload, evidence, True, True)
    expect_equal(
        gates,
        "rollout_acceptance_type",
        payload.get("recordType"),
        "pisces-runtime-plane-staged-rollout-acceptance",
        evidence,
    )
    expect_equal(gates, "rollout_acceptance_stage", payload.get("stage"), "full", evidence)
    expect_equal(gates, "rollout_acceptance_decision", payload.get("decision"), "PROCEED", evidence, "FAIL")
    expect_number(gates, "rollout_acceptance_target_traffic_percent", payload.get("targetTrafficPercent"), evidence, minimum=100)
    expect_present(gates, "rollout_acceptance_operator", payload.get("operator"), evidence)
    expect_present(gates, "rollout_acceptance_approval_ticket", payload.get("approvalTicket"), evidence)
    expect_present(gates, "rollout_acceptance_approved_by", payload.get("approvedBy"), evidence)
    expect_true(gates, "rollout_acceptance_rollback_plan_tested", get_path(payload, "rollbackPlan.tested"), evidence)
    expect_present(gates, "rollout_acceptance_rollback_plan_owner", get_path(payload, "rollbackPlan.owner"), evidence)


def validate_production_acceptance(gates, files, payloads):
    evidence = files.get("productionAcceptanceRecord")
    payload = payloads.get("productionAcceptanceRecord")
    if not evidence or not isinstance(payload, dict):
        return
    validate_common_identity(gates, "production_acceptance", payload, evidence, True, True)
    expect_equal(
        gates,
        "production_acceptance_type",
        payload.get("recordType"),
        "pisces-runtime-plane-production-acceptance",
        evidence,
    )
    expect_equal(gates, "production_acceptance_stage", payload.get("stage"), "full", evidence)
    expect_equal(gates, "production_acceptance_final_decision", payload.get("finalDecision"), "ACCEPT", evidence, "FAIL")
    expect_present(gates, "production_acceptance_operator", payload.get("operator"), evidence)
    expect_present(gates, "production_acceptance_approval_ticket", payload.get("approvalTicket"), evidence)
    expect_present(gates, "production_acceptance_approved_by", payload.get("approvedBy"), evidence)
    expect_present(gates, "production_acceptance_accepted_at", payload.get("acceptedAt"), evidence)
    expect_true(gates, "production_acceptance_rollback_plan_tested", get_path(payload, "rollbackPlan.tested"), evidence)
    record_evidence = payload.get("evidence") or {}
    for key in (
        "releaseEvidenceManifest",
        "postReleaseSloSummary",
        "experimentImpactSummary",
        "stagedRolloutDecisionSummary",
    ):
        expect_present(gates, f"production_acceptance_evidence_{key}", record_evidence.get(key), evidence)


def main():
    gates = []
    release_id = os.environ.get("PISCES_RELEASE_ID", "")
    add_gate(
        gates,
        "release_id",
        "PASS" if release_id else "HOLD",
        actual=release_id or "missing",
        expected="set",
    )
    expect_equal(gates, "target_environment", os.environ.get("PISCES_TARGET_ENVIRONMENT"), "local", None)

    files, payloads = read_required_files(gates)
    validate_preprod(gates, files, payloads)
    validate_capacity(gates, files, payloads)
    validate_redis_fault(gates, files, payloads)
    validate_event_replay(gates, files, payloads)
    validate_post_release_metrics(gates, files, payloads)
    validate_impact(gates, files, payloads)
    validate_rollout_acceptance(gates, files, payloads)
    validate_production_acceptance(gates, files, payloads)

    failed = [gate for gate in gates if gate["status"] == "FAIL"]
    holds = [gate for gate in gates if gate["status"] == "HOLD"]
    status = "FAIL" if failed else "HOLD" if holds else "PASS"
    summary = {
        "summaryType": "pisces-production-infrastructure-local-evidence-validate",
        "summaryVersion": 1,
        "status": status,
        "generatedAt": now_iso(),
        "releaseId": release_id or None,
        "targetEnvironment": os.environ.get("PISCES_TARGET_ENVIRONMENT"),
        "expectedGitSha": os.environ.get("PISCES_EXPECTED_GIT_SHA") or None,
        "gateCount": len(gates),
        "passedGateCount": len([gate for gate in gates if gate["status"] == "PASS"]),
        "holdGateCount": len(holds),
        "failedGateCount": len(failed),
        "evidence": {
            evidence_name: str(path)
            for evidence_name, path in sorted(files.items())
        },
        "gates": gates,
    }

    output_file = Path(os.environ["PISCES_LOCAL_EVIDENCE_VALIDATE_OUTPUT_FILE"])
    output_file.write_text(json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"Local evidence validation written: {output_file} status={status}", file=sys.stderr)
    if status == "PASS":
        return 0
    if status == "HOLD":
        return 1
    return 2


sys.exit(main())
PY
}

main "$@"
