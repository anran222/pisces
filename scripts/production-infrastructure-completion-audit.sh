#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  scripts/production-infrastructure-completion-audit.sh

Environment:
  PISCES_REPO_ROOT                                      Repository root. Default: inferred from this script.
  PISCES_COMPLETION_AUDIT_OUTPUT_FILE                   JSON output. Default: target/pisces-production-infrastructure-completion-audit/summary.json.
  PISCES_COMPLETION_TARGET_ENVIRONMENT                  Target runtime environment. Default: prod.
  PISCES_COMPLETION_REQUIRE_REAL_ENV_EVIDENCE           Require real environment evidence files. Default: false.
  PISCES_COMPLETION_REQUIRE_CLEAN_GIT                   Require release evidence to come from a clean worktree. Default: true.
  PISCES_COMPLETION_RELEASE_PACKAGE_REPORT_FILE         Optional strict release package report JSON.
  PISCES_COMPLETION_PREPROD_RECORD_CHECK_SUMMARY_FILE   Optional preprod record check summary JSON.
  PISCES_COMPLETION_RELEASE_EVIDENCE_MANIFEST_FILE      Optional release evidence manifest JSON.
  PISCES_COMPLETION_PRODUCTION_ACCEPTANCE_SUMMARY_FILE  Optional production acceptance summary JSON.
  PISCES_COMPLETION_SCREENSHOT_DIR                      Optional core frontend screenshot directory.
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

resolve_output_file() {
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
  PISCES_COMPLETION_AUDIT_OUTPUT_FILE="${PISCES_COMPLETION_AUDIT_OUTPUT_FILE:-target/pisces-production-infrastructure-completion-audit/summary.json}"
  PISCES_COMPLETION_TARGET_ENVIRONMENT="${PISCES_COMPLETION_TARGET_ENVIRONMENT:-prod}"
  PISCES_COMPLETION_REQUIRE_REAL_ENV_EVIDENCE="${PISCES_COMPLETION_REQUIRE_REAL_ENV_EVIDENCE:-false}"
  PISCES_COMPLETION_REQUIRE_CLEAN_GIT="${PISCES_COMPLETION_REQUIRE_CLEAN_GIT:-true}"
  PISCES_COMPLETION_RELEASE_PACKAGE_REPORT_FILE="${PISCES_COMPLETION_RELEASE_PACKAGE_REPORT_FILE:-}"
  PISCES_COMPLETION_PREPROD_RECORD_CHECK_SUMMARY_FILE="${PISCES_COMPLETION_PREPROD_RECORD_CHECK_SUMMARY_FILE:-}"
  PISCES_COMPLETION_RELEASE_EVIDENCE_MANIFEST_FILE="${PISCES_COMPLETION_RELEASE_EVIDENCE_MANIFEST_FILE:-}"
  PISCES_COMPLETION_PRODUCTION_ACCEPTANCE_SUMMARY_FILE="${PISCES_COMPLETION_PRODUCTION_ACCEPTANCE_SUMMARY_FILE:-}"
  PISCES_COMPLETION_SCREENSHOT_DIR="${PISCES_COMPLETION_SCREENSHOT_DIR:-}"

  export PISCES_REPO_ROOT
  export PISCES_COMPLETION_TARGET_ENVIRONMENT
  export PISCES_COMPLETION_REQUIRE_REAL_ENV_EVIDENCE
  export PISCES_COMPLETION_REQUIRE_CLEAN_GIT
  export PISCES_COMPLETION_RELEASE_PACKAGE_REPORT_FILE
  export PISCES_COMPLETION_PREPROD_RECORD_CHECK_SUMMARY_FILE
  export PISCES_COMPLETION_RELEASE_EVIDENCE_MANIFEST_FILE
  export PISCES_COMPLETION_PRODUCTION_ACCEPTANCE_SUMMARY_FILE
  export PISCES_COMPLETION_SCREENSHOT_DIR

  local output_file
  output_file="$(resolve_output_file "$PISCES_COMPLETION_AUDIT_OUTPUT_FILE")"
  mkdir -p "$(dirname "$output_file")"

  python3 - "$output_file" <<'PY'
import json
import os
import re
import struct
import sys
import zlib
from datetime import datetime, timezone
from pathlib import Path

output_file = Path(sys.argv[1])
repo_root = Path(os.environ["PISCES_REPO_ROOT"])


def now_iso():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_bool(value, field):
    normalized = str(value).strip().lower()
    if normalized in {"1", "true", "yes", "y"}:
        return True
    if normalized in {"0", "false", "no", "n"}:
        return False
    raise SystemExit(f"{field} must be boolean: {value}")


def rel_path(path):
    candidate = Path(path)
    if candidate.is_absolute():
        return candidate
    return repo_root / candidate


def read_png_quality(path):
    try:
        data = path.read_bytes()
    except Exception as exc:
        return None, f"cannot read PNG: {exc}"
    if not data.startswith(b"\x89PNG\r\n\x1a\n"):
        return None, "invalid PNG signature"

    offset = 8
    ihdr = None
    idat_parts = []
    while offset + 8 <= len(data):
        length = struct.unpack(">I", data[offset:offset + 4])[0]
        chunk_type = data[offset + 4:offset + 8]
        chunk_data_start = offset + 8
        chunk_data_end = chunk_data_start + length
        crc_end = chunk_data_end + 4
        if crc_end > len(data):
            return None, "truncated PNG chunk"
        chunk_data = data[chunk_data_start:chunk_data_end]
        if chunk_type == b"IHDR":
            if length != 13:
                return None, "invalid IHDR length"
            width, height, bit_depth, color_type, compression, filter_method, interlace = struct.unpack(
                ">IIBBBBB",
                chunk_data,
            )
            ihdr = {
                "width": width,
                "height": height,
                "bitDepth": bit_depth,
                "colorType": color_type,
                "compression": compression,
                "filterMethod": filter_method,
                "interlace": interlace,
            }
        elif chunk_type == b"IDAT":
            idat_parts.append(chunk_data)
        elif chunk_type == b"IEND":
            break
        offset = crc_end

    if not ihdr:
        return None, "missing IHDR"
    if not idat_parts:
        return None, "missing IDAT"

    quality = {
        **ihdr,
        "sizeBytes": path.stat().st_size,
        "sampledColorCount": None,
    }
    if ihdr["bitDepth"] != 8 or ihdr["colorType"] not in {0, 2, 4, 6} or ihdr["interlace"] != 0:
        return quality, "unsupported PNG format for visual sampling"

    bytes_per_pixel = {
        0: 1,
        2: 3,
        4: 2,
        6: 4,
    }[ihdr["colorType"]]
    row_length = ihdr["width"] * bytes_per_pixel

    def paeth_predictor(a, b, c):
        p = a + b - c
        pa = abs(p - a)
        pb = abs(p - b)
        pc = abs(p - c)
        if pa <= pb and pa <= pc:
            return a
        if pb <= pc:
            return b
        return c

    try:
        raw = zlib.decompress(b"".join(idat_parts))
    except Exception as exc:
        return quality, f"cannot decompress PNG IDAT: {exc}"

    expected_raw_length = (row_length + 1) * ihdr["height"]
    if len(raw) < expected_raw_length:
        return quality, "truncated PNG pixel data"

    previous = bytearray(row_length)
    unique_colors = set()
    row_step = max(1, ihdr["height"] // 24)
    col_step = max(1, ihdr["width"] // 32)
    cursor = 0
    for y in range(ihdr["height"]):
        filter_type = raw[cursor]
        cursor += 1
        current = bytearray(raw[cursor:cursor + row_length])
        cursor += row_length

        if filter_type == 1:
            for index in range(bytes_per_pixel, row_length):
                current[index] = (current[index] + current[index - bytes_per_pixel]) & 0xFF
        elif filter_type == 2:
            for index in range(row_length):
                current[index] = (current[index] + previous[index]) & 0xFF
        elif filter_type == 3:
            for index in range(row_length):
                left = current[index - bytes_per_pixel] if index >= bytes_per_pixel else 0
                up = previous[index]
                current[index] = (current[index] + ((left + up) // 2)) & 0xFF
        elif filter_type == 4:
            for index in range(row_length):
                left = current[index - bytes_per_pixel] if index >= bytes_per_pixel else 0
                up = previous[index]
                upper_left = previous[index - bytes_per_pixel] if index >= bytes_per_pixel else 0
                current[index] = (current[index] + paeth_predictor(left, up, upper_left)) & 0xFF
        elif filter_type != 0:
            return quality, f"unsupported PNG filter type: {filter_type}"

        if y % row_step == 0:
            for x in range(0, ihdr["width"], col_step):
                pixel_start = x * bytes_per_pixel
                if ihdr["colorType"] == 0:
                    sample = bytes([current[pixel_start]])
                else:
                    sample = bytes(current[pixel_start:pixel_start + min(3, bytes_per_pixel)])
                unique_colors.add(sample)
                if len(unique_colors) >= 16:
                    break
        if len(unique_colors) >= 16:
            break
        previous = current

    quality["sampledColorCount"] = len(unique_colors)
    return quality, None


def read_text(relative_path):
    path = rel_path(relative_path)
    try:
        return path.read_text(encoding="utf-8")
    except Exception:
        return None


def read_json(path, field, required):
    if not path:
        if required:
            return None, f"{field} is required"
        return None, None
    candidate = rel_path(path)
    if not candidate.is_file():
        if required:
            return None, f"{field} not found: {path}"
        return None, None
    try:
        return json.loads(candidate.read_text(encoding="utf-8")), None
    except Exception as exc:
        return None, f"Invalid JSON file {path}: {exc}"


def add_gate(gates, plane, name, status, actual=None, expected=None, evidence=None, reason=None, gate_type="static"):
    gate = {
        "plane": plane,
        "name": name,
        "status": status,
        "type": gate_type,
        "actual": actual,
        "expected": expected,
    }
    if evidence is not None:
        gate["evidence"] = evidence
    if reason:
        gate["reason"] = reason
    gates.append(gate)


def add_file_gate(gates, plane, name, relative_path):
    path = rel_path(relative_path)
    add_gate(
        gates,
        plane,
        name,
        "PASS" if path.is_file() else "FAIL",
        actual="present" if path.is_file() else "missing",
        expected="present",
        evidence=relative_path,
    )


def add_pattern_gate(gates, plane, name, relative_path, pattern):
    text = read_text(relative_path)
    if text is None:
        add_gate(gates, plane, name, "FAIL", actual="missing file", expected=pattern, evidence=relative_path)
        return
    add_gate(
        gates,
        plane,
        name,
        "PASS" if re.search(pattern, text, re.MULTILINE | re.DOTALL) else "FAIL",
        actual="matched" if re.search(pattern, text, re.MULTILINE | re.DOTALL) else "not matched",
        expected=pattern,
        evidence=relative_path,
    )


def add_json_status_gate(gates, plane, name, payload, path, expected_type_field, expected_type, status_field, expected_status):
    if payload is None:
        add_gate(gates, plane, name, "HOLD", actual="missing", expected="valid JSON", evidence=path, gate_type="real_environment")
        return
    actual_type = payload.get(expected_type_field)
    actual_status = payload.get(status_field)
    add_gate(
        gates,
        plane,
        f"{name}_type",
        "PASS" if actual_type == expected_type else "FAIL",
        actual=actual_type,
        expected=expected_type,
        evidence=path,
        gate_type="real_environment",
    )
    add_gate(
        gates,
        plane,
        f"{name}_status",
        "PASS" if actual_status == expected_status else "FAIL",
        actual=actual_status,
        expected=expected_status,
        evidence=path,
        gate_type="real_environment",
    )


def add_json_field_gate(gates, plane, name, payload, path, field_path, expected):
    if payload is None:
        add_gate(gates, plane, name, "HOLD", actual="missing", expected=expected, evidence=path, gate_type="real_environment")
        return
    value = payload
    for part in field_path.split("."):
        if not isinstance(value, dict) or part not in value:
            value = None
            break
        value = value[part]
    add_gate(
        gates,
        plane,
        name,
        "PASS" if value == expected else "FAIL",
        actual=value,
        expected=expected,
        evidence=path,
        gate_type="real_environment",
    )


def read_field(payload, field_path):
    value = payload
    for part in field_path.split("."):
        if not isinstance(value, dict) or part not in value:
            return None
        value = value[part]
    return value


def bool_field_matches(value, expected):
    if isinstance(value, bool):
        return value is expected
    normalized = str(value).strip().lower()
    return normalized == ("true" if expected else "false")


def bool_field_present(value):
    if isinstance(value, bool):
        return True
    return str(value).strip().lower() in {"true", "false"}


def add_json_bool_gate(gates, plane, name, payload, path, field_path, expected):
    if payload is None:
        add_gate(gates, plane, name, "HOLD", actual="missing", expected=expected, evidence=path, gate_type="real_environment")
        return
    value = read_field(payload, field_path)
    add_gate(
        gates,
        plane,
        name,
        "PASS" if bool_field_matches(value, expected) else "FAIL",
        actual=value,
        expected=expected,
        evidence=path,
        gate_type="real_environment",
    )


def add_json_git_dirty_gate(gates, plane, name, payload, path, field_path, require_clean_git):
    if payload is None:
        add_gate(gates, plane, name, "HOLD", actual="missing", expected=False, evidence=path, gate_type="real_environment")
        return
    value = read_field(payload, field_path)
    if require_clean_git:
        add_gate(
            gates,
            plane,
            name,
            "PASS" if bool_field_matches(value, False) else "FAIL",
            actual=value,
            expected=False,
            evidence=path,
            gate_type="real_environment",
        )
        return
    add_gate(
        gates,
        plane,
        name,
        "PASS" if bool_field_present(value) else "FAIL",
        actual=value,
        expected="recorded; clean git not required for this local closeout",
        evidence=path,
        reason="PISCES_COMPLETION_REQUIRE_CLEAN_GIT=false",
        gate_type="real_environment",
    )


def add_json_non_empty_gate(gates, plane, name, payload, path, field_path):
    if payload is None:
        add_gate(gates, plane, name, "HOLD", actual="missing", expected="non-empty value", evidence=path, gate_type="real_environment")
        return
    value = read_field(payload, field_path)
    add_gate(
        gates,
        plane,
        name,
        "PASS" if value not in {None, ""} else "FAIL",
        actual=value,
        expected="non-empty value",
        evidence=path,
        gate_type="real_environment",
    )


def add_json_numeric_max_gate(gates, plane, name, payload, path, field_path, maximum):
    if payload is None:
        add_gate(gates, plane, name, "HOLD", actual="missing", expected=f"<= {maximum}", evidence=path, gate_type="real_environment")
        return
    value = read_field(payload, field_path)
    try:
        numeric_value = float(value)
    except (TypeError, ValueError):
        numeric_value = None
    add_gate(
        gates,
        plane,
        name,
        "PASS" if numeric_value is not None and numeric_value <= maximum else "FAIL",
        actual=value,
        expected=f"<= {maximum}",
        evidence=path,
        gate_type="real_environment",
    )


def add_all_gate_status_gate(gates, plane, name, payload, path, field_path="gates", expected="PASS"):
    if payload is None:
        add_gate(gates, plane, name, "HOLD", actual="missing", expected=f"all {expected}", evidence=path, gate_type="real_environment")
        return
    gate_list = read_field(payload, field_path)
    if not isinstance(gate_list, list) or not gate_list:
        add_gate(gates, plane, name, "FAIL", actual="missing gates", expected=f"all {expected}", evidence=path, gate_type="real_environment")
        return
    bad_gates = [
        str(gate.get("name") or index)
        for index, gate in enumerate(gate_list)
        if not isinstance(gate, dict) or gate.get("status") != expected
    ]
    add_gate(
        gates,
        plane,
        name,
        "PASS" if not bad_gates else "FAIL",
        actual="all pass" if not bad_gates else ", ".join(bad_gates[:10]),
        expected=f"all {expected}",
        evidence=path,
        gate_type="real_environment",
    )


def add_cross_field_gate(gates, plane, name, left_payload, left_path, left_field, right_payload, right_path, right_field):
    if left_payload is None or right_payload is None:
        return
    left_value = read_field(left_payload, left_field)
    right_value = read_field(right_payload, right_field)
    add_gate(
        gates,
        plane,
        name,
        "PASS" if left_value not in {None, ""} and left_value == right_value else "FAIL",
        actual=f"{left_value} vs {right_value}",
        expected="same value",
        evidence=f"{left_path} :: {right_path}",
        gate_type="real_environment",
    )


gates = []
require_real = parse_bool(
    os.environ["PISCES_COMPLETION_REQUIRE_REAL_ENV_EVIDENCE"],
    "PISCES_COMPLETION_REQUIRE_REAL_ENV_EVIDENCE",
)
require_clean_git = parse_bool(
    os.environ["PISCES_COMPLETION_REQUIRE_CLEAN_GIT"],
    "PISCES_COMPLETION_REQUIRE_CLEAN_GIT",
)
target_environment = os.environ["PISCES_COMPLETION_TARGET_ENVIRONMENT"].strip() or "prod"

static_files = [
    ("Control Plane", "api key scope enforcement", "pisces-service/src/main/java/com/pisces/service/annotation/ApiKeyScopeRequired.java"),
    ("Control Plane", "api key principal parser", "pisces-service/src/main/java/com/pisces/service/config/ApiKeyProperties.java"),
    ("Control Plane", "application space controller", "pisces-api/src/main/java/com/pisces/api/application/ApplicationSpaceController.java"),
    ("Control Plane", "experiment controller", "pisces-api/src/main/java/com/pisces/api/experiment/ExperimentController.java"),
    ("Control Plane", "audit log SQL", "pisces-service/src/main/resources/sql/mysql/pisces_audit_log.sql"),
    ("Control Plane", "application space SQL", "pisces-service/src/main/resources/sql/mysql/pisces_application_space.sql"),
    ("Control Plane", "config version SQL", "pisces-service/src/main/resources/sql/mysql/pisces_experiment_config_version.sql"),
    ("Control Plane", "config draft SQL", "pisces-service/src/main/resources/sql/mysql/pisces_experiment_config_draft.sql"),
    ("Control Plane", "approval vote SQL", "pisces-service/src/main/resources/sql/mysql/pisces_experiment_approval_vote.sql"),
    ("Control Plane", "approval escalation SQL", "pisces-service/src/main/resources/sql/mysql/pisces_experiment_approval_escalation.sql"),
    ("Data Plane", "runtime config controller", "pisces-api/src/main/java/com/pisces/api/runtime/RuntimeConfigController.java"),
    ("Data Plane", "traffic controller", "pisces-api/src/main/java/com/pisces/api/traffic/TrafficController.java"),
    ("Data Plane", "runtime config service", "pisces-service/src/main/java/com/pisces/service/service/impl/RuntimeConfigServiceImpl.java"),
    ("Data Plane", "traffic service", "pisces-service/src/main/java/com/pisces/service/service/impl/TrafficServiceImpl.java"),
    ("Data Plane", "redis config broadcaster", "pisces-service/src/main/java/com/pisces/service/config/RedisExperimentConfigChangeBroadcaster.java"),
    ("Data Plane", "traffic metrics", "pisces-service/src/main/java/com/pisces/service/metrics/TrafficAssignmentMetrics.java"),
    ("Data Plane", "java sdk", "pisces-sdk-java/src/main/java/com/pisces/sdk/PiscesClient.java"),
    ("Data Plane", "js sdk", "pisces-sdk-js/pisces-sdk.js"),
    ("Event Plane", "data controller", "pisces-api/src/main/java/com/pisces/api/data/DataController.java"),
    ("Event Plane", "event inbox consumer", "pisces-service/src/main/java/com/pisces/service/service/impl/EventInboxConsumer.java"),
    ("Event Plane", "event materializer", "pisces-service/src/main/java/com/pisces/service/service/impl/EventInboxMaterializer.java"),
    ("Event Plane", "event materialization SQL", "pisces-service/src/main/resources/sql/mysql/pisces_event_materialization.sql"),
    ("Event Plane", "event replay job SQL", "pisces-service/src/main/resources/sql/mysql/pisces_event_replay_job.sql"),
    ("Event Plane", "event replay audit script", "scripts/event-pipeline-replay-audit.sh"),
    ("Decision Plane", "AI decision evidence response", "pisces-common/src/main/java/com/pisces/common/response/AIDecisionEvidenceResponse.java"),
    ("Decision Plane", "decision context builder", "pisces-service/src/main/java/com/pisces/service/ai/ExperimentDecisionContextBuilder.java"),
    ("Decision Plane", "prompt template builder", "pisces-service/src/main/java/com/pisces/service/ai/PromptTemplateBuilder.java"),
    ("Decision Plane", "manual conclusion request", "pisces-common/src/main/java/com/pisces/common/request/ExperimentConclusionStatusUpdateRequest.java"),
    ("Decision Plane", "AI decision service", "pisces-service/src/main/java/com/pisces/service/service/impl/AIDecisionServiceImpl.java"),
    ("Operations", "release package workflow", ".github/workflows/runtime-plane-release-package.yml"),
    ("Operations", "release package check", "scripts/runtime-plane-release-package-check.sh"),
    ("Operations", "secret scan", "scripts/production-infrastructure-secret-scan.sh"),
    ("Operations", "preprod drill record check", "scripts/runtime-plane-preprod-drill-record-check.sh"),
    ("Operations", "release evidence archive", "scripts/runtime-plane-release-evidence-archive.sh"),
    ("Operations", "production acceptance check", "scripts/runtime-plane-production-acceptance-check.sh"),
    ("Operations", "runtime alerts", "docs/observability/prometheus/pisces-runtime-plane-alerts.yml"),
    ("Operations", "runtime dashboard", "docs/observability/grafana/pisces-runtime-plane-dashboard.json"),
    ("Operations", "release checklist", "docs/operations/runtime-plane-release-checklist.md"),
]

for plane, name, path in static_files:
    add_file_gate(gates, plane, name, path)

static_patterns = [
    ("Control Plane", "management APIs require management scope", "pisces-api/src/main/java/com/pisces/api/experiment/ExperimentController.java", r"@ApiKeyScopeRequired\(ApiKeyScope\.MANAGEMENT\)"),
    ("Control Plane", "audit logs exposed", "pisces-api/src/main/java/com/pisces/api/experiment/ExperimentController.java", r"audit-logs"),
    ("Control Plane", "config version publish and rollback exposed", "pisces-api/src/main/java/com/pisces/api/experiment/ExperimentController.java", r"config-versions/publish.*config-versions/rollback"),
    ("Control Plane", "config draft approval flow exposed", "pisces-api/src/main/java/com/pisces/api/experiment/ExperimentController.java", r"config-draft.*approvals"),
    ("Control Plane", "approval tasks exposed", "pisces-api/src/main/java/com/pisces/api/experiment/ExperimentController.java", r"approval-tasks"),
    ("Control Plane", "application dictionary exposed", "pisces-api/src/main/java/com/pisces/api/application/ApplicationSpaceController.java", r"/applications.*dictionary"),
    ("Control Plane", "environment api key specs", "pisces-service/src/main/resources/application.yml", r"PISCES_API_KEY_SPECS"),
    ("Control Plane", "no fixed mysql password", "pisces-service/src/main/resources/application.yml", r"MYSQL_PASSWORD:"),
    ("Control Plane", "AI key explicitly externalized", "pisces-service/src/main/resources/application.yml", r"TONGYI_API_KEY:"),
    ("Data Plane", "runtime scope enforced", "pisces-api/src/main/java/com/pisces/api/runtime/RuntimeConfigController.java", r"@ApiKeyScopeRequired\(ApiKeyScope\.RUNTIME\)"),
    ("Data Plane", "runtime config long poll", "pisces-api/src/main/java/com/pisces/api/runtime/RuntimeConfigController.java", r"knownVersion.*waitMillis"),
    ("Data Plane", "traffic trace endpoint", "pisces-api/src/main/java/com/pisces/api/traffic/TrafficController.java", r"assign/trace"),
    ("Data Plane", "config broadcast metrics", "pisces-service/src/main/java/com/pisces/service/metrics/ConfigChangeBroadcastMetrics.java", r"pisces\.config\.change\.broadcast"),
    ("Data Plane", "SDK stale fallback metrics", "pisces-sdk-java/src/main/java/com/pisces/sdk/PiscesClient.java", r"getMetricsSnapshot.*stale"),
    ("Data Plane", "JS SDK retry and metrics", "pisces-sdk-js/pisces-sdk.js", r"getMetricsSnapshot.*retry"),
    ("Event Plane", "data APIs require runtime scope", "pisces-api/src/main/java/com/pisces/api/data/DataController.java", r"@ApiKeyScopeRequired\(ApiKeyScope\.RUNTIME\)"),
    ("Event Plane", "event inbox writes accepted", "pisces-service/src/main/java/com/pisces/service/service/impl/DataServiceImpl.java", r"EventInboxRecord|eventInbox"),
    ("Event Plane", "materialization ledger used", "pisces-service/src/main/java/com/pisces/service/service/impl/EventInboxMaterializer.java", r"EventMaterialization|materialization"),
    ("Event Plane", "replay plan segments", "pisces-service/src/main/java/com/pisces/service/service/impl/AnalysisServiceImpl.java", r"buildReplayPlanSegments"),
    ("Event Plane", "segmented repair endpoint", "pisces-api/src/main/java/com/pisces/api/analysis/AnalysisController.java", r"events/replay/materialization/repair/segments"),
    ("Event Plane", "replay progress reporter", "pisces-service/src/main/java/com/pisces/service/event/EventReplayProgressReporter.java", r"EventReplayProgressReporter"),
    ("Decision Plane", "AI evidence has data quality fields", "pisces-common/src/main/java/com/pisces/common/response/AIDecisionEvidenceResponse.java", r"analysisReady.*blockingIssues.*latestReportSnapshotVersion"),
    ("Decision Plane", "AI binds report snapshots", "pisces-service/src/main/java/com/pisces/service/ai/ExperimentDecisionContextBuilder.java", r"listReportSnapshots"),
    ("Decision Plane", "prompt includes report snapshot facts", "pisces-service/src/main/java/com/pisces/service/ai/PromptTemplateBuilder.java", r"reportSnapshotFacts"),
    ("Decision Plane", "manual conclusion requires config and snapshot", "pisces-common/src/main/java/com/pisces/common/request/ExperimentConclusionStatusUpdateRequest.java", r"expectedConfigVersion.*reportSnapshotVersion"),
    ("Decision Plane", "manual conclusion resets on config change", "pisces-service/src/main/java/com/pisces/service/service/impl/ExperimentServiceImpl.java", r"resetConclusionAfterConfigChange"),
    ("Operations", "release package strict CI workflow", ".github/workflows/runtime-plane-release-package.yml", r"PISCES_RELEASE_PACKAGE_RUN_TESTS:\s*['\"]true['\"].*PISCES_RELEASE_PACKAGE_REQUIRE_PROMTOOL:\s*['\"]true['\"]"),
    ("Operations", "secret scan summary contract", "scripts/production-infrastructure-secret-scan.sh", r"pisces-production-infrastructure-secret-scan"),
    ("Operations", "preprod record checker in checklist", "docs/operations/runtime-plane-release-checklist.md", r"runtime-plane-preprod-drill-record-check\.sh"),
    ("Operations", "production acceptance in checklist", "docs/operations/runtime-plane-release-checklist.md", r"runtime-plane-production-acceptance-check\.sh"),
    ("Operations", "release evidence archive has segment summary", "scripts/runtime-plane-release-evidence-archive.sh", r"segmentSummary"),
]

for plane, name, path, pattern in static_patterns:
    add_pattern_gate(gates, plane, name, path, pattern)

test_evidence = [
    ("Control Plane", "application space service tests", "pisces-service/src/test/java/com/pisces/service/service/impl/ApplicationSpaceServiceImplTest.java"),
    ("Control Plane", "experiment service governance tests", "pisces-service/src/test/java/com/pisces/service/service/impl/ExperimentServiceImplTest.java"),
    ("Data Plane", "runtime config contract tests", "pisces-api/src/test/java/com/pisces/api/runtime/RuntimeConfigControllerContractTest.java"),
    ("Data Plane", "runtime config service tests", "pisces-service/src/test/java/com/pisces/service/service/impl/RuntimeConfigServiceImplTest.java"),
    ("Event Plane", "event pipeline service tests", "pisces-service/src/test/java/com/pisces/service/service/impl/AnalysisServiceImplEventPipelineTest.java"),
    ("Event Plane", "event materializer tests", "pisces-service/src/test/java/com/pisces/service/service/impl/EventInboxMaterializerTest.java"),
    ("Decision Plane", "AI decision service tests", "pisces-service/src/test/java/com/pisces/service/service/impl/AIDecisionServiceImplTest.java"),
    ("Decision Plane", "AI bridge tests", "pisces-service/src/test/java/com/pisces/service/service/impl/AnalysisServiceImplAIBridgeTest.java"),
    ("Operations", "production flow smoke test", "pisces-service/src/test/java/com/pisces/service/service/impl/ProductionExperimentFlowSmokeTest.java"),
]
for plane, name, path in test_evidence:
    add_file_gate(gates, plane, name, path)

frontend_evidence = [
    ("Control Plane", "frontend application governance", "../pisces-web/src/pages/ApplicationSpaces.jsx"),
    ("Event Plane", "frontend event pipeline status", "../pisces-web/src/components/DataPipelineStatus.jsx"),
    ("Event Plane", "frontend replay segment rows", "../pisces-web/src/utils/eventReplayPlan.js"),
    ("Operations", "frontend core screenshot capture command", "../pisces-web/package.json"),
    ("Operations", "frontend core screenshot capture script", "../pisces-web/scripts/capture-core-functions.cjs"),
]
for plane, name, path in frontend_evidence:
    add_file_gate(gates, plane, name, path)

add_pattern_gate(
    gates,
    "Operations",
    "frontend stable core screenshot capture command",
    "../pisces-web/package.json",
    r"capture:core",
)
add_pattern_gate(
    gates,
    "Operations",
    "frontend production high severity audit command",
    "../pisces-web/package.json",
    r"audit:prod-high",
)
add_pattern_gate(
    gates,
    "Operations",
    "frontend screenshot capture declares playwright",
    "../pisces-web/package.json",
    r"playwright",
)
add_pattern_gate(
    gates,
    "Operations",
    "frontend screenshot output directory configurable",
    "../pisces-web/scripts/capture-core-functions.cjs",
    r"PISCES_WEB_SCREENSHOT_DIR",
)
add_pattern_gate(
    gates,
    "Operations",
    "frontend layout audit output configurable",
    "../pisces-web/scripts/capture-core-functions.cjs",
    r"PISCES_WEB_LAYOUT_AUDIT_FILE",
)
add_pattern_gate(
    gates,
    "Operations",
    "frontend layout audit summary contract",
    "../pisces-web/scripts/capture-core-functions.cjs",
    r"pisces-web-core-layout-audit",
)

release_report_path = os.environ["PISCES_COMPLETION_RELEASE_PACKAGE_REPORT_FILE"]
preprod_summary_path = os.environ["PISCES_COMPLETION_PREPROD_RECORD_CHECK_SUMMARY_FILE"]
manifest_path = os.environ["PISCES_COMPLETION_RELEASE_EVIDENCE_MANIFEST_FILE"]
production_acceptance_path = os.environ["PISCES_COMPLETION_PRODUCTION_ACCEPTANCE_SUMMARY_FILE"]

release_report, error = read_json(release_report_path, "PISCES_COMPLETION_RELEASE_PACKAGE_REPORT_FILE", require_real)
if error:
    add_gate(gates, "Operations", "real release package report available", "HOLD", actual="missing", expected="valid JSON", evidence=release_report_path, reason=error, gate_type="real_environment")
elif release_report:
    add_json_status_gate(gates, "Operations", "real release package", release_report, release_report_path, "reportType", "pisces-runtime-plane-release-package-check", "status", "PASS")
    add_json_non_empty_gate(gates, "Operations", "real release package gitSha present", release_report, release_report_path, "gitSha")
    add_json_numeric_max_gate(gates, "Operations", "real release package warnings", release_report, release_report_path, "warnings", 0)
    add_json_bool_gate(gates, "Operations", "real release package runTests", release_report, release_report_path, "runTests", True)
    add_json_bool_gate(gates, "Operations", "real release package requirePromtool", release_report, release_report_path, "requirePromtool", True)
    add_json_bool_gate(gates, "Operations", "real release package requireRuby", release_report, release_report_path, "requireRuby", True)
    add_json_git_dirty_gate(gates, "Operations", "real release package gitDirty", release_report, release_report_path, "gitDirty", require_clean_git)
else:
    add_gate(gates, "Operations", "real release package report available", "HOLD", actual="not provided", expected="strict CI report", gate_type="real_environment")

preprod_summary, error = read_json(preprod_summary_path, "PISCES_COMPLETION_PREPROD_RECORD_CHECK_SUMMARY_FILE", require_real)
if error:
    add_gate(gates, "Operations", "real preprod record check available", "HOLD", actual="missing", expected="valid JSON", evidence=preprod_summary_path, reason=error, gate_type="real_environment")
elif preprod_summary:
    add_json_status_gate(gates, "Operations", "real preprod record check", preprod_summary, preprod_summary_path, "summaryType", "pisces-runtime-plane-preprod-drill-record-check", "status", "PASS")
    add_json_non_empty_gate(gates, "Operations", "real preprod releaseId present", preprod_summary, preprod_summary_path, "releaseId")
    add_json_non_empty_gate(gates, "Operations", "real preprod gitSha present", preprod_summary, preprod_summary_path, "gitSha")
    add_all_gate_status_gate(gates, "Operations", "real preprod gates all pass", preprod_summary, preprod_summary_path)
else:
    add_gate(gates, "Operations", "real preprod record check available", "HOLD", actual="not provided", expected="PASS summary", gate_type="real_environment")

manifest, error = read_json(manifest_path, "PISCES_COMPLETION_RELEASE_EVIDENCE_MANIFEST_FILE", require_real)
if error:
    add_gate(gates, "Operations", "real release evidence manifest available", "HOLD", actual="missing", expected="valid JSON", evidence=manifest_path, reason=error, gate_type="real_environment")
elif manifest:
    add_json_field_gate(gates, "Operations", "real release evidence manifest type", manifest, manifest_path, "manifestType", "pisces-runtime-plane-release-evidence")
    add_json_field_gate(gates, "Operations", "real release package status in manifest", manifest, manifest_path, "releasePackage.status", "PASS")
    add_json_bool_gate(gates, "Operations", "real release package runTests in manifest", manifest, manifest_path, "releasePackage.runTests", True)
    add_json_bool_gate(gates, "Operations", "real release package requirePromtool in manifest", manifest, manifest_path, "releasePackage.requirePromtool", True)
    add_json_bool_gate(gates, "Operations", "real release package requireRuby in manifest", manifest, manifest_path, "releasePackage.requireRuby", True)
    add_json_git_dirty_gate(gates, "Operations", "real release package gitDirty in manifest", manifest, manifest_path, "releasePackage.gitDirty", require_clean_git)
    add_json_field_gate(gates, "Operations", "real release evidence environment", manifest, manifest_path, "environment", target_environment)
    add_json_non_empty_gate(gates, "Operations", "real release evidence releaseId present", manifest, manifest_path, "releaseId")
    add_json_non_empty_gate(gates, "Operations", "real release evidence gitSha present", manifest, manifest_path, "releasePackage.gitSha")
    add_json_field_gate(gates, "Operations", "real capacity baseline environment", manifest, manifest_path, "capacityBaseline.environment", target_environment)
    evidence = manifest.get("evidence") or {}
    for key in ("releasePackageReport", "preprodDrillRecord", "capacityBaselineManifest", "redisFaultRecord", "eventPipelineReplayAuditSummary"):
        add_gate(gates, "Operations", f"real evidence manifest has {key}", "PASS" if isinstance(evidence.get(key), dict) else "HOLD", actual="present" if isinstance(evidence.get(key), dict) else "missing", expected="present", evidence=manifest_path, gate_type="real_environment")
    event_audit = manifest.get("eventPipelineReplayAudit")
    if isinstance(event_audit, dict):
        add_gate(gates, "Event Plane", "real event replay audit status", "PASS" if event_audit.get("status") == "PASS" else "FAIL", actual=event_audit.get("status"), expected="PASS", evidence=manifest_path, gate_type="real_environment")
        add_gate(gates, "Event Plane", "real event replay failed gate count", "PASS" if event_audit.get("failedGateCount") == 0 else "FAIL", actual=event_audit.get("failedGateCount"), expected=0, evidence=manifest_path, gate_type="real_environment")
    else:
        add_gate(gates, "Event Plane", "real event replay audit status", "HOLD", actual="missing", expected="PASS when event plane changed", evidence=manifest_path, gate_type="real_environment")
else:
    add_gate(gates, "Operations", "real release evidence manifest available", "HOLD", actual="not provided", expected="release evidence manifest", gate_type="real_environment")

acceptance, error = read_json(production_acceptance_path, "PISCES_COMPLETION_PRODUCTION_ACCEPTANCE_SUMMARY_FILE", require_real)
if error:
    add_gate(gates, "Operations", "real production acceptance available", "HOLD", actual="missing", expected="valid JSON", evidence=production_acceptance_path, reason=error, gate_type="real_environment")
elif acceptance:
    add_json_status_gate(gates, "Operations", "real production acceptance", acceptance, production_acceptance_path, "summaryType", "pisces-runtime-plane-production-acceptance-check", "decision", "ACCEPT")
    add_json_field_gate(gates, "Operations", "real production acceptance status", acceptance, production_acceptance_path, "status", "PASS")
    add_json_field_gate(gates, "Operations", "real production acceptance environment", acceptance, production_acceptance_path, "environment", target_environment)
    add_json_field_gate(gates, "Operations", "real production acceptance full stage", acceptance, production_acceptance_path, "stage", "full")
    add_json_non_empty_gate(gates, "Operations", "real production acceptance releaseId present", acceptance, production_acceptance_path, "releaseId")
    add_all_gate_status_gate(gates, "Operations", "real production acceptance gates all pass", acceptance, production_acceptance_path)
else:
    add_gate(gates, "Operations", "real production acceptance available", "HOLD", actual="not provided", expected="ACCEPT summary", gate_type="real_environment")

add_cross_field_gate(gates, "Operations", "release package gitSha matches manifest", release_report, release_report_path, "gitSha", manifest, manifest_path, "releasePackage.gitSha")
add_cross_field_gate(gates, "Operations", "release package gitSha matches preprod", release_report, release_report_path, "gitSha", preprod_summary, preprod_summary_path, "gitSha")
add_cross_field_gate(gates, "Operations", "manifest gitSha matches capacity baseline", manifest, manifest_path, "releasePackage.gitSha", manifest, manifest_path, "capacityBaseline.gitSha")
add_cross_field_gate(gates, "Operations", "manifest releaseId matches preprod", manifest, manifest_path, "releaseId", preprod_summary, preprod_summary_path, "releaseId")
add_cross_field_gate(gates, "Operations", "manifest releaseId matches production acceptance", manifest, manifest_path, "releaseId", acceptance, production_acceptance_path, "releaseId")
add_cross_field_gate(gates, "Operations", "manifest releaseId matches capacity baseline", manifest, manifest_path, "releaseId", manifest, manifest_path, "capacityBaseline.releaseId")

screenshot_dir = os.environ["PISCES_COMPLETION_SCREENSHOT_DIR"]
layout_audit_evidence_path = None
if screenshot_dir:
    screenshot_path = rel_path(screenshot_dir)
    screenshot_names = sorted(path.name.lower() for path in screenshot_path.glob("*.png")) if screenshot_path.is_dir() else []
    layout_audit_file = screenshot_path / "layout-audit.json"
    layout_audit_evidence_path = str(layout_audit_file) if layout_audit_file.is_file() else str(layout_audit_file)
    required_screenshots = [
        ("ai center workspace", [r"ai[-_]?center", r"workspace|priority"]),
        ("experiment list", [r"experiment", r"list|workbench"]),
        ("experiment detail data navigation", [r"experiment", r"detail", r"data[-_]?nav"]),
        ("experiment config governance", [r"experiment", r"config", r"version|governance"]),
        ("experiment conclusion", [r"experiment", r"conclusion"]),
        ("experiment approval", [r"experiment", r"approval"]),
        ("experiment runtime structure", [r"experiment", r"runtime", r"structure"]),
        ("experiment statistics", [r"experiment", r"statistics|mab"]),
        ("data pipeline status", [r"data[-_]?pipeline", r"status|running"]),
        ("event replay plan", [r"replay[-_]?plan"]),
        ("event replay segment repair", [r"segment[-_]?repair"]),
        ("ai decision workspace", [r"ai[-_]?decision|decision[-_]?workspace"]),
        ("application governance", [r"application", r"space|governance"]),
        ("ai design draft", [r"ai[-_]?design|structured[-_]?draft"]),
    ]
    add_gate(
        gates,
        "Operations",
        "core frontend screenshot count",
        "PASS" if len(screenshot_names) >= len(required_screenshots) else "HOLD",
        actual=len(screenshot_names),
        expected=f">= {len(required_screenshots)} png files",
        evidence=screenshot_dir,
        gate_type="real_environment",
    )
    layout_audit = None
    layout_audit_parse_error = None
    if layout_audit_file.is_file():
        try:
            layout_audit = json.loads(layout_audit_file.read_text(encoding="utf-8"))
        except Exception as exc:
            layout_audit_parse_error = str(exc)
    add_gate(
        gates,
        "Operations",
        "core frontend layout audit file",
        "PASS" if layout_audit_file.is_file() else "HOLD",
        actual="present" if layout_audit_file.is_file() else "missing",
        expected="layout-audit.json",
        evidence=str(layout_audit_file),
        gate_type="real_environment",
    )
    if layout_audit_file.is_file():
        add_gate(
            gates,
            "Operations",
            "core frontend layout audit parses",
            "PASS" if layout_audit_parse_error is None else "FAIL",
            actual="valid JSON" if layout_audit_parse_error is None else layout_audit_parse_error,
            expected="valid JSON",
            evidence=str(layout_audit_file),
            gate_type="real_environment",
        )
    if layout_audit is not None:
        add_gate(
            gates,
            "Operations",
            "core frontend layout audit contract",
            "PASS" if layout_audit.get("summaryType") == "pisces-web-core-layout-audit" else "FAIL",
            actual=layout_audit.get("summaryType"),
            expected="pisces-web-core-layout-audit",
            evidence=str(layout_audit_file),
            gate_type="real_environment",
        )
        add_gate(
            gates,
            "Operations",
            "core frontend layout audit status",
            "PASS" if layout_audit.get("status") == "PASS" else "HOLD",
            actual=layout_audit.get("status"),
            expected="PASS",
            evidence=str(layout_audit_file),
            gate_type="real_environment",
        )
        add_gate(
            gates,
            "Operations",
            "core frontend layout audit failed count",
            "PASS" if layout_audit.get("failedCount") == 0 else "HOLD",
            actual=layout_audit.get("failedCount"),
            expected=0,
            evidence=str(layout_audit_file),
            gate_type="real_environment",
        )
        enforced_count = layout_audit.get("enforcedCount")
        add_gate(
            gates,
            "Operations",
            "core frontend layout audit enforced screens",
            "PASS" if isinstance(enforced_count, int) and enforced_count >= 8 else "HOLD",
            actual=enforced_count,
            expected=">= 8",
            evidence=str(layout_audit_file),
            gate_type="real_environment",
        )
    for screenshot_name, patterns in required_screenshots:
        matches = [
            name for name in screenshot_names
            if all(re.search(pattern, name) for pattern in patterns)
        ]
        matched_name = matches[0] if matches else None
        add_gate(
            gates,
            "Operations",
            f"core frontend screenshot: {screenshot_name}",
            "PASS" if matches else "HOLD",
            actual=matched_name if matched_name else "missing",
            expected="filename matching " + " + ".join(patterns),
            evidence=screenshot_dir,
            gate_type="real_environment",
        )
        if not matched_name:
            continue
        matched_path = screenshot_path / matched_name
        quality, quality_error = read_png_quality(matched_path)
        quality_pass = (
            quality_error is None
            and quality["width"] >= 1366
            and quality["height"] >= 768
            and quality["width"] > quality["height"]
            and quality["sampledColorCount"] is not None
            and quality["sampledColorCount"] >= 16
        )
        if quality:
            actual_quality = (
                f"{matched_name} {quality['width']}x{quality['height']} "
                f"size={quality['sizeBytes']} sampledColors={quality['sampledColorCount']}"
            )
            if quality_error:
                actual_quality = f"{actual_quality}; {quality_error}"
        else:
            actual_quality = f"{matched_name}; {quality_error}"
        add_gate(
            gates,
            "Operations",
            f"core frontend screenshot quality: {screenshot_name}",
            "PASS" if quality_pass else "HOLD",
            actual=actual_quality,
            expected="valid PNG, width>=1366, height>=768, landscape, sampledColors>=16",
            evidence=str(matched_path),
            reason=None if quality_pass else quality_error,
            gate_type="real_environment",
        )
else:
    add_gate(gates, "Operations", "core frontend screenshots", "HOLD", actual="not provided", expected="core function screenshot directory", gate_type="real_environment")

planes = []
for plane in ("Control Plane", "Data Plane", "Event Plane", "Decision Plane", "Operations"):
    plane_gates = [gate for gate in gates if gate["plane"] == plane]
    fail_count = sum(1 for gate in plane_gates if gate["status"] == "FAIL")
    hold_count = sum(1 for gate in plane_gates if gate["status"] == "HOLD")
    pass_count = sum(1 for gate in plane_gates if gate["status"] == "PASS")
    if fail_count:
        plane_status = "FAIL"
    elif hold_count:
        plane_status = "HOLD"
    else:
        plane_status = "PASS"
    planes.append({
        "plane": plane,
        "status": plane_status,
        "passed": pass_count,
        "holds": hold_count,
        "failed": fail_count,
        "total": len(plane_gates),
    })

static_failures = [gate for gate in gates if gate["type"] == "static" and gate["status"] == "FAIL"]
static_holds = [gate for gate in gates if gate["type"] == "static" and gate["status"] == "HOLD"]
real_failures = [gate for gate in gates if gate["type"] == "real_environment" and gate["status"] == "FAIL"]
real_holds = [gate for gate in gates if gate["type"] == "real_environment" and gate["status"] == "HOLD"]

static_status = "FAIL" if static_failures else "HOLD" if static_holds else "PASS"
real_status = "FAIL" if real_failures else "HOLD" if real_holds else "PASS"
completion_status = "COMPLETE" if static_status == "PASS" and real_status == "PASS" else "INCOMPLETE"
status = "FAIL" if static_status == "FAIL" or real_status == "FAIL" else "HOLD" if completion_status != "COMPLETE" else "PASS"

summary = {
    "summaryType": "pisces-production-infrastructure-completion-audit",
    "summaryVersion": 1,
    "generatedAt": now_iso(),
    "status": status,
    "completionStatus": completion_status,
    "staticStatus": static_status,
    "realEnvironmentStatus": real_status,
    "requireRealEnvironmentEvidence": require_real,
    "requireCleanGit": require_clean_git,
    "targetEnvironment": target_environment,
    "repoRoot": str(repo_root),
    "planes": planes,
    "evidence": {
        "releasePackageReport": release_report_path or None,
        "preprodRecordCheckSummary": preprod_summary_path or None,
        "releaseEvidenceManifest": manifest_path or None,
        "productionAcceptanceSummary": production_acceptance_path or None,
        "screenshotDir": screenshot_dir or None,
        "layoutAudit": layout_audit_evidence_path,
    },
    "gates": gates,
}

output_file.write_text(json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
print(f"Production infrastructure completion audit written: {output_file} status={status} completion={completion_status}", file=sys.stderr)

if status == "PASS":
    sys.exit(0)
if status == "HOLD":
    sys.exit(1)
sys.exit(2)
PY
}

main "$@"
