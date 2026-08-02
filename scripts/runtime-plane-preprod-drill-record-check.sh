#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  PISCES_PREPROD_DRILL_RECORD_FILE=docs/operations/releases/<release>.md \
  scripts/runtime-plane-preprod-drill-record-check.sh

Environment:
  PISCES_PREPROD_DRILL_RECORD_FILE               Required preprod drill record markdown.
  PISCES_PREPROD_DRILL_RECORD_OUTPUT_FILE        JSON output. Default: target/pisces-runtime-preprod-drill-record-check/summary.json.
  PISCES_RELEASE_ID                              Optional expected release ID.
  PISCES_EXPECTED_GIT_SHA                        Optional expected Git SHA.
  PISCES_RELEASE_PACKAGE_REPORT_FILE             Optional release package report JSON.
  PISCES_CAPACITY_BASELINE_MANIFEST_FILE         Optional capacity baseline manifest JSON.
  PISCES_EVENT_REPLAY_AUDIT_SUMMARY_FILE         Optional event replay audit summary JSON.
  PISCES_PREPROD_REQUIRE_STRICT_PACKAGE_CI       Require runTests/promtool/ruby evidence. Default: true.
  PISCES_PREPROD_REQUIRE_EVIDENCE_ARCHIVE        Require archive manifest fields in the record. Default: false.
  PISCES_PREPROD_REQUIRE_CAPACITY_BASELINE       Require capacity baseline record fields. Default: true.
  PISCES_PREPROD_REQUIRE_REDIS_FAULT             Require Redis fault drill record fields. Default: true.
  PISCES_PREPROD_REQUIRE_OBSERVABILITY           Require observability record fields. Default: true.
  PISCES_PREPROD_REQUIRE_EVENT_REPLAY            Require event replay audit record fields. Default: false.
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

  PISCES_PREPROD_DRILL_RECORD_FILE="${PISCES_PREPROD_DRILL_RECORD_FILE:-}"
  PISCES_PREPROD_DRILL_RECORD_OUTPUT_FILE="${PISCES_PREPROD_DRILL_RECORD_OUTPUT_FILE:-target/pisces-runtime-preprod-drill-record-check/summary.json}"
  PISCES_RELEASE_ID="${PISCES_RELEASE_ID:-}"
  PISCES_EXPECTED_GIT_SHA="${PISCES_EXPECTED_GIT_SHA:-}"
  PISCES_RELEASE_PACKAGE_REPORT_FILE="${PISCES_RELEASE_PACKAGE_REPORT_FILE:-}"
  PISCES_CAPACITY_BASELINE_MANIFEST_FILE="${PISCES_CAPACITY_BASELINE_MANIFEST_FILE:-}"
  PISCES_EVENT_REPLAY_AUDIT_SUMMARY_FILE="${PISCES_EVENT_REPLAY_AUDIT_SUMMARY_FILE:-}"
  PISCES_PREPROD_REQUIRE_STRICT_PACKAGE_CI="${PISCES_PREPROD_REQUIRE_STRICT_PACKAGE_CI:-true}"
  PISCES_PREPROD_REQUIRE_EVIDENCE_ARCHIVE="${PISCES_PREPROD_REQUIRE_EVIDENCE_ARCHIVE:-false}"
  PISCES_PREPROD_REQUIRE_CAPACITY_BASELINE="${PISCES_PREPROD_REQUIRE_CAPACITY_BASELINE:-true}"
  PISCES_PREPROD_REQUIRE_REDIS_FAULT="${PISCES_PREPROD_REQUIRE_REDIS_FAULT:-true}"
  PISCES_PREPROD_REQUIRE_OBSERVABILITY="${PISCES_PREPROD_REQUIRE_OBSERVABILITY:-true}"
  PISCES_PREPROD_REQUIRE_EVENT_REPLAY="${PISCES_PREPROD_REQUIRE_EVENT_REPLAY:-false}"

  [[ -n "$PISCES_PREPROD_DRILL_RECORD_FILE" ]] || die "PISCES_PREPROD_DRILL_RECORD_FILE is required"
  [[ -f "$PISCES_PREPROD_DRILL_RECORD_FILE" ]] || die "Preprod drill record not found: $PISCES_PREPROD_DRILL_RECORD_FILE"

  export PISCES_PREPROD_DRILL_RECORD_FILE
  export PISCES_RELEASE_ID
  export PISCES_EXPECTED_GIT_SHA
  export PISCES_RELEASE_PACKAGE_REPORT_FILE
  export PISCES_CAPACITY_BASELINE_MANIFEST_FILE
  export PISCES_EVENT_REPLAY_AUDIT_SUMMARY_FILE
  export PISCES_PREPROD_REQUIRE_STRICT_PACKAGE_CI
  export PISCES_PREPROD_REQUIRE_EVIDENCE_ARCHIVE
  export PISCES_PREPROD_REQUIRE_CAPACITY_BASELINE
  export PISCES_PREPROD_REQUIRE_REDIS_FAULT
  export PISCES_PREPROD_REQUIRE_OBSERVABILITY
  export PISCES_PREPROD_REQUIRE_EVENT_REPLAY

  local output_file
  output_file="$(resolve_output_file "$PISCES_PREPROD_DRILL_RECORD_OUTPUT_FILE")"
  mkdir -p "$(dirname "$output_file")"

  python3 - "$output_file" <<'PY'
import json
import os
import re
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


def read_json(path, field, required):
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


def normalize(value):
    return re.sub(r"\s+", " ", str(value).replace("`", "")).strip()


def is_placeholder(value):
    text = normalize(value)
    if not text:
        return True
    lowered = text.lower()
    return (
        text in {"-", "—"}
        or lowered in {"tbd", "todo", "pending", "待补充", "未填写"}
        or bool(re.fullmatch(r"<[^>]+>", text))
    )


def is_filled(value):
    return not is_placeholder(value)


def is_pass(value):
    text = normalize(value).upper()
    return text in {"PASS", "PASSED", "OK", "PROCEED", "ACCEPT", "TRUE", "YES"} or "通过" in text or "正常" in text


def numeric_values(value):
    return [float(match) for match in re.findall(r"-?\d+(?:\.\d+)?", str(value))]


def split_table_row(line):
    stripped = line.strip()
    if not stripped.startswith("|") or not stripped.endswith("|"):
        return None
    cells = [cell.strip() for cell in stripped.strip("|").split("|")]
    if not cells or all(re.fullmatch(r":?-{3,}:?", cell.strip()) for cell in cells):
        return None
    return cells


def table_rows(markdown):
    rows = []
    for line in markdown.splitlines():
        cells = split_table_row(line)
        if cells is not None:
            rows.append(cells)
    return rows


def find_row(rows, label):
    wanted = normalize(label)
    for cells in rows:
        if cells and normalize(cells[0]) == wanted:
            return cells
    return None


def find_row_contains(rows, label):
    wanted = normalize(label)
    for cells in rows:
        if cells and wanted in normalize(cells[0]):
            return cells
    return None


def row_value(rows, label, index=1):
    cells = find_row(rows, label)
    if cells is None or len(cells) <= index:
        return None
    return cells[index]


def row_result(rows, label):
    cells = find_row(rows, label)
    if cells is None:
        return None
    if len(cells) >= 4:
        return cells[2]
    if len(cells) >= 3:
        return cells[1]
    if len(cells) >= 2:
        return cells[1]
    return None


def row_contains_result(rows, label):
    cells = find_row_contains(rows, label)
    if cells is None:
        return None
    if len(cells) >= 4:
        return cells[2]
    if len(cells) >= 3:
        return cells[1]
    if len(cells) >= 2:
        return cells[1]
    return None


def require_section(gates, markdown, title):
    add_gate(
        gates,
        f"section:{title}",
        "PASS" if title in markdown else "HOLD",
        actual="present" if title in markdown else "missing",
        expected="present",
    )


def require_row_filled(gates, rows, label, gate_name=None, allow_na=False):
    value = row_value(rows, label)
    if value is not None and allow_na and normalize(value).upper() in {"N/A", "NA", "不适用"}:
        add_gate(gates, gate_name or f"row:{label}", "SKIP", actual=value, expected="not applicable")
        return value
    add_gate(
        gates,
        gate_name or f"row:{label}",
        "PASS" if value is not None and is_filled(value) else "HOLD",
        actual=value,
        expected="filled",
    )
    return value


def require_row_pass(gates, rows, label, result_index=None, gate_name=None):
    if result_index is None:
        value = row_result(rows, label)
    else:
        cells = find_row(rows, label)
        value = cells[result_index] if cells is not None and len(cells) > result_index else None
    add_gate(
        gates,
        gate_name or f"pass:{label}",
        "PASS" if value is not None and is_pass(value) else "HOLD",
        actual=value,
        expected="PASS",
    )
    return value


def require_contains_row_pass(gates, rows, label, gate_name=None):
    value = row_contains_result(rows, label)
    add_gate(
        gates,
        gate_name or f"pass_contains:{label}",
        "PASS" if value is not None and is_pass(value) else "HOLD",
        actual=value,
        expected="PASS",
    )
    return value


def require_numeric_zero(gates, rows, label, gate_name):
    value = row_value(rows, label)
    numbers = numeric_values(value or "")
    status = "PASS" if numbers and numbers[0] == 0 else "FAIL" if numbers else "HOLD"
    add_gate(gates, gate_name, status, actual=value, expected=0)
    return value


def require_matching(gates, name, actual, expected):
    add_gate(
        gates,
        name,
        "PASS" if actual == expected else "HOLD",
        actual=actual,
        expected=expected,
    )


record_file = Path(os.environ["PISCES_PREPROD_DRILL_RECORD_FILE"])
markdown = record_file.read_text(encoding="utf-8")
rows = table_rows(markdown)
gates = []

require_strict_package = parse_bool(
    os.environ["PISCES_PREPROD_REQUIRE_STRICT_PACKAGE_CI"],
    "PISCES_PREPROD_REQUIRE_STRICT_PACKAGE_CI",
)
require_archive = parse_bool(
    os.environ["PISCES_PREPROD_REQUIRE_EVIDENCE_ARCHIVE"],
    "PISCES_PREPROD_REQUIRE_EVIDENCE_ARCHIVE",
)
require_capacity = parse_bool(
    os.environ["PISCES_PREPROD_REQUIRE_CAPACITY_BASELINE"],
    "PISCES_PREPROD_REQUIRE_CAPACITY_BASELINE",
)
require_redis = parse_bool(
    os.environ["PISCES_PREPROD_REQUIRE_REDIS_FAULT"],
    "PISCES_PREPROD_REQUIRE_REDIS_FAULT",
)
require_observability = parse_bool(
    os.environ["PISCES_PREPROD_REQUIRE_OBSERVABILITY"],
    "PISCES_PREPROD_REQUIRE_OBSERVABILITY",
)
require_event_replay = parse_bool(
    os.environ["PISCES_PREPROD_REQUIRE_EVENT_REPLAY"],
    "PISCES_PREPROD_REQUIRE_EVENT_REPLAY",
)

for section in (
    "## Release Metadata",
    "## 1. Release Package Gate",
    "## 2. Runtime Contract Smoke",
    "## 3. Release Drill",
    "## 4. Capacity Baseline",
    "## 5. Redis Fault Injection",
    "## 6. Observability",
    "## 7. Decision",
    "## 8. Evidence Archive",
):
    require_section(gates, markdown, section)

release_id = require_row_filled(gates, rows, "Release ID", "metadata_release_id")
expected_release_id = os.environ["PISCES_RELEASE_ID"]
if expected_release_id:
    require_matching(gates, "metadata_release_id_matches_expected", normalize(release_id), expected_release_id)
git_sha = require_row_filled(gates, rows, "代码版本 Git SHA", "metadata_git_sha")
expected_git_sha = os.environ["PISCES_EXPECTED_GIT_SHA"]
if expected_git_sha:
    require_matching(gates, "metadata_git_sha_matches_expected", normalize(git_sha), expected_git_sha)

for label, gate_name in (
    ("变更摘要", "metadata_change_summary"),
    ("预发日期", "metadata_preprod_date"),
    ("操作人", "metadata_operator"),
    ("CI Run URL", "metadata_ci_run_url"),
    ("Release Package Report", "metadata_release_package_report"),
    ("预发环境", "metadata_environment"),
    ("Pisces 实例", "metadata_instances"),
    ("Redis 集群 / Channel", "metadata_redis_channel"),
    ("Runtime API Key 来源", "metadata_runtime_key_source"),
    ("Management API Key 来源", "metadata_management_key_source"),
):
    require_row_filled(gates, rows, label, gate_name)

if require_archive:
    require_row_filled(gates, rows, "Release Evidence Manifest", "metadata_release_evidence_manifest")

require_contains_row_pass(gates, rows, "CI workflow Runtime Plane Release Package", "package_ci_workflow_passed")
if require_strict_package:
    require_contains_row_pass(gates, rows, "PISCES_RELEASE_PACKAGE_RUN_TESTS=true", "package_run_tests_true")
    require_contains_row_pass(gates, rows, "PISCES_RELEASE_PACKAGE_REQUIRE_PROMTOOL=true", "package_require_promtool_true")
require_contains_row_pass(gates, rows, "report.json 已上传为 CI artifact", "package_report_artifact_archived")
require_contains_row_pass(gates, rows, "gitDirty=false 或已解释", "package_git_dirty_recorded")

for label, gate_name in (
    ("GET /api/runtime/experiments/{id}/config", "runtime_config_contract_smoke"),
    ("GET /api/runtime/experiments/{id}/config/version?knownVersion=<version>&waitMillis=1000", "runtime_version_contract_smoke"),
    ("POST /api/traffic/assign/trace", "traffic_assign_trace_smoke"),
):
    if require_observability:
        require_contains_row_pass(gates, rows, label, gate_name)

for label, gate_name in (
    ("Baseline configVersion", "release_drill_baseline_config_version"),
    ("Target configVersion", "release_drill_target_config_version"),
    ("收敛耗时", "release_drill_convergence_duration"),
    ("Assignment requests", "release_drill_assignment_requests"),
    ("Assignment concurrency", "release_drill_assignment_concurrency"),
    ("Assignment P95 / P99", "release_drill_assignment_latency"),
):
    require_row_filled(gates, rows, label, gate_name)
require_numeric_zero(gates, rows, "Assignment failed", "release_drill_assignment_failed_zero")
exceptions = row_value(rows, "异常摘要")
add_gate(
    gates,
    "release_drill_exception_summary",
    "PASS" if exceptions is not None and normalize(exceptions) in {"无", "NONE", "none", "0"} else "HOLD",
    actual=exceptions,
    expected="no exceptions",
)

if require_capacity:
    for label, gate_name in (
        ("JSONL 文件", "capacity_jsonl_file"),
        ("归档 manifest", "capacity_manifest"),
        ("Max errorRate", "capacity_max_error_rate"),
        ("Max P95 ms", "capacity_max_p95"),
        ("Max P99 ms", "capacity_max_p99"),
        ("与上一基线对比", "capacity_baseline_comparison"),
    ):
        require_row_filled(gates, rows, label, gate_name)

if require_redis:
    for label, gate_name in (
        ("baseline", "redis_fault_baseline_passed"),
        ("during-fault", "redis_fault_during_fault_passed"),
        ("recovery", "redis_fault_recovery_passed"),
    ):
        require_row_pass(gates, rows, label, result_index=2, gate_name=gate_name)

if require_observability:
    for label, gate_name in (
        ("Prometheus scrape 正常", "observability_prometheus_scrape"),
        ("Grafana runtime dashboard 已导入", "observability_grafana_dashboard"),
        ("pisces_traffic_assignment_requests_total{result=\"ERROR\"} 不增长", "observability_assignment_errors"),
        ("pisces_traffic_cache_events_total{result=\"ERROR\"} 不持续增长", "observability_cache_errors"),
        ("pisces_config_change_broadcast_published_total{result=\"ERROR\"} 不增长", "observability_broadcast_publish_errors"),
        ("pisces_config_change_broadcast_received_total{result=\"INVALID\"} 不增长", "observability_broadcast_invalid"),
        ("SDK 本地 requestFailureCount、retryCount、staleExperimentConfigFallbackCount 无异常增长", "observability_sdk_metrics"),
    ):
        require_row_pass(gates, rows, label, gate_name=gate_name)

decision = require_row_filled(gates, rows, "是否允许进入生产发布", "decision_release_allowed")
add_gate(
    gates,
    "decision_release_allowed_proceed",
    "PASS" if decision is not None and normalize(decision).upper() in {"PROCEED", "YES", "PASS", "允许"} else "HOLD",
    actual=decision,
    expected="PROCEED",
)
must_fix = row_value(rows, "必须先修复的问题")
add_gate(
    gates,
    "decision_no_must_fix_items",
    "PASS" if must_fix is not None and normalize(must_fix) in {"无", "NONE", "none", "0"} else "HOLD",
    actual=must_fix,
    expected="none",
)
for label, gate_name in (
    ("可接受风险", "decision_accepted_risk"),
    ("回滚条件", "decision_rollback_conditions"),
    ("审批人", "decision_approver"),
    ("审批时间", "decision_approved_at"),
):
    require_row_filled(gates, rows, label, gate_name)

if require_archive:
    for label, gate_name in (
        ("Archive directory", "archive_directory"),
        ("Manifest path", "archive_manifest_path"),
        ("Manifest sha256", "archive_manifest_sha256"),
    ):
        require_row_filled(gates, rows, label, gate_name)
    manifest_sha = row_value(rows, "Manifest sha256")
    add_gate(
        gates,
        "archive_manifest_sha256_shape",
        "PASS" if manifest_sha is not None and re.fullmatch(r"[0-9a-fA-F]{64}", normalize(manifest_sha)) else "HOLD",
        actual=manifest_sha,
        expected="64 hex chars",
    )

if require_event_replay:
    require_section(gates, markdown, "## 14. Event Pipeline Replay Audit")
    for label, gate_name in (
        ("Summary path", "event_replay_summary_path"),
        ("Replay scope request", "event_replay_scope_request"),
        ("Segment count", "event_replay_segment_count"),
        ("Repair segment index", "event_replay_repair_segment_index"),
        ("Max segment affected count", "event_replay_max_segment_affected_count"),
        ("Max segment unmaterialized before / after", "event_replay_segment_gap_before_after"),
        ("Before pipeline status", "event_replay_before_pipeline_status"),
        ("After pipeline status", "event_replay_after_pipeline_status"),
    ):
        require_row_filled(gates, rows, label, gate_name)
    require_row_pass(gates, rows, "Replay audit status", gate_name="event_replay_status_pass")
    require_row_filled(gates, rows, "Post-repair replay plan unmaterialized count", "event_replay_post_repair_unmaterialized_count")
    post_repair_gap = row_value(rows, "Post-repair replay plan unmaterialized count")
    gap_numbers = numeric_values(post_repair_gap or "")
    add_gate(
        gates,
        "event_replay_post_repair_unmaterialized_zero",
        "PASS" if gap_numbers and gap_numbers[0] == 0 else "FAIL" if gap_numbers else "HOLD",
        actual=post_repair_gap,
        expected=0,
    )
    require_numeric_zero(gates, rows, "Failed gates", "event_replay_failed_gates_zero")

package_report, error_message = read_json(
    os.environ["PISCES_RELEASE_PACKAGE_REPORT_FILE"],
    "PISCES_RELEASE_PACKAGE_REPORT_FILE",
    False,
)
if error_message:
    add_gate(gates, "release_package_report_json", "HOLD", actual="missing", expected="valid JSON", evidence=os.environ["PISCES_RELEASE_PACKAGE_REPORT_FILE"], reason=error_message)
elif package_report:
    add_gate(gates, "release_package_report_json", "PASS", actual="present", expected="present", evidence=os.environ["PISCES_RELEASE_PACKAGE_REPORT_FILE"])
    require_matching(gates, "release_package_report_type", package_report.get("reportType"), "pisces-runtime-plane-release-package-check")
    require_matching(gates, "release_package_report_status", package_report.get("status"), "PASS")
    if expected_git_sha:
        require_matching(gates, "release_package_report_git_sha", package_report.get("gitSha"), expected_git_sha)
    if require_strict_package:
        for field in ("runTests", "requirePromtool", "requireRuby"):
            require_matching(gates, f"release_package_report_{field}", package_report.get(field), "true")

capacity_manifest, error_message = read_json(
    os.environ["PISCES_CAPACITY_BASELINE_MANIFEST_FILE"],
    "PISCES_CAPACITY_BASELINE_MANIFEST_FILE",
    False,
)
if error_message:
    add_gate(gates, "capacity_manifest_json", "HOLD", actual="missing", expected="valid JSON", evidence=os.environ["PISCES_CAPACITY_BASELINE_MANIFEST_FILE"], reason=error_message)
elif capacity_manifest:
    add_gate(gates, "capacity_manifest_json", "PASS", actual="present", expected="present", evidence=os.environ["PISCES_CAPACITY_BASELINE_MANIFEST_FILE"])
    if expected_release_id:
        require_matching(gates, "capacity_manifest_release_id", capacity_manifest.get("releaseId"), expected_release_id)
    for field in ("environment", "experimentId", "gitSha", "maxErrorRate", "maxP95Ms", "maxP99Ms"):
        add_gate(
            gates,
            f"capacity_manifest_{field}",
            "PASS" if field in capacity_manifest else "HOLD",
            actual=capacity_manifest.get(field),
            expected="present",
        )

event_replay_summary, error_message = read_json(
    os.environ["PISCES_EVENT_REPLAY_AUDIT_SUMMARY_FILE"],
    "PISCES_EVENT_REPLAY_AUDIT_SUMMARY_FILE",
    require_event_replay,
)
if error_message:
    add_gate(gates, "event_replay_summary_json", "HOLD", actual="missing", expected="valid JSON", evidence=os.environ["PISCES_EVENT_REPLAY_AUDIT_SUMMARY_FILE"], reason=error_message)
elif event_replay_summary:
    add_gate(gates, "event_replay_summary_json", "PASS", actual="present", expected="present", evidence=os.environ["PISCES_EVENT_REPLAY_AUDIT_SUMMARY_FILE"])
    require_matching(gates, "event_replay_summary_type", event_replay_summary.get("summaryType"), "pisces-event-pipeline-replay-audit")
    require_matching(gates, "event_replay_summary_status", event_replay_summary.get("status"), "PASS")
    failed_gates = [
        gate for gate in event_replay_summary.get("gates", [])
        if isinstance(gate, dict) and gate.get("status") == "FAIL"
    ]
    add_gate(gates, "event_replay_summary_failed_gate_count", "PASS" if not failed_gates else "FAIL", actual=len(failed_gates), expected=0)
    if event_replay_summary.get("repairSegmentIndex") is not None:
        replay_plan = event_replay_summary.get("replayPlan") or {}
        replay_plan_after = event_replay_summary.get("replayPlanAfterRepair") or {}
        segments = replay_plan.get("segments")
        add_gate(
            gates,
            "event_replay_summary_segments_present",
            "PASS" if isinstance(segments, list) and segments else "HOLD",
            actual=len(segments) if isinstance(segments, list) else None,
            expected="non-empty",
        )
        add_gate(
            gates,
            "event_replay_summary_segment_gap_closed",
            "PASS" if replay_plan_after.get("maxSegmentUnmaterializedCount") == 0 else "FAIL",
            actual=replay_plan_after.get("maxSegmentUnmaterializedCount"),
            expected=0,
        )

placeholder_cells = []
for cells in rows:
    label = normalize(cells[0]) if cells else ""
    for index, cell in enumerate(cells[1:], start=1):
        if re.search(r"<[^>]+>", cell):
            placeholder_cells.append({
                "label": label,
                "columnIndex": index,
                "value": cell,
            })
add_gate(
    gates,
    "record_table_value_placeholders",
    "PASS" if not placeholder_cells else "HOLD",
    actual=len(placeholder_cells),
    expected=0,
    reason=json.dumps(placeholder_cells, ensure_ascii=False) if placeholder_cells else None,
)

if any(gate["status"] == "FAIL" for gate in gates):
    status = "FAIL"
elif any(gate["status"] == "HOLD" for gate in gates):
    status = "HOLD"
else:
    status = "PASS"

summary = {
    "summaryType": "pisces-runtime-plane-preprod-drill-record-check",
    "summaryVersion": 1,
    "status": status,
    "generatedAt": now_iso(),
    "recordFile": str(record_file),
    "releaseId": normalize(release_id) if release_id is not None else None,
    "gitSha": normalize(git_sha) if git_sha is not None else None,
    "requirements": {
        "strictPackageCi": require_strict_package,
        "evidenceArchive": require_archive,
        "capacityBaseline": require_capacity,
        "redisFault": require_redis,
        "observability": require_observability,
        "eventReplay": require_event_replay,
    },
    "evidence": {
        "releasePackageReport": os.environ["PISCES_RELEASE_PACKAGE_REPORT_FILE"] or None,
        "capacityBaselineManifest": os.environ["PISCES_CAPACITY_BASELINE_MANIFEST_FILE"] or None,
        "eventReplayAuditSummary": os.environ["PISCES_EVENT_REPLAY_AUDIT_SUMMARY_FILE"] or None,
    },
    "gates": gates,
}

with open(output_file, "w", encoding="utf-8") as target:
    json.dump(summary, target, ensure_ascii=False, indent=2, sort_keys=True)
    target.write("\n")

print(f"Preprod drill record check written: {output_file} status={status}", file=sys.stderr)
if status != "PASS":
    sys.exit(1)
PY
}

main "$@"
