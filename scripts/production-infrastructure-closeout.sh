#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  scripts/production-infrastructure-closeout.sh

Environment:
  PISCES_REPO_ROOT                                      Repository root. Default: inferred from this script.
  PISCES_PRODUCTION_CLOSEOUT_DIR                       Output directory. Default: target/pisces-production-infrastructure-closeout.
  PISCES_PRODUCTION_CLOSEOUT_ALLOW_INCOMPLETE          Exit 0 when incomplete, for dry-run reporting. Default: false.
  PISCES_COMPLETION_TARGET_ENVIRONMENT                 Target runtime environment, for example prod or local. Default: prod.
  PISCES_COMPLETION_REQUIRE_CLEAN_GIT                  Require release evidence to come from a clean worktree. Default: true.
  PISCES_COMPLETION_RELEASE_PACKAGE_REPORT_FILE        Required strict release package report JSON.
  PISCES_COMPLETION_PREPROD_RECORD_CHECK_SUMMARY_FILE  Required preprod record check summary JSON.
  PISCES_COMPLETION_RELEASE_EVIDENCE_MANIFEST_FILE     Required release evidence manifest JSON.
  PISCES_COMPLETION_PRODUCTION_ACCEPTANCE_SUMMARY_FILE Required production acceptance summary JSON.
  PISCES_COMPLETION_SCREENSHOT_DIR                     Required core frontend screenshot directory.

Output:
  <closeout-dir>/completion-summary.json
  <closeout-dir>/closeout-report.md
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

parse_bool() {
  case "$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')" in
    1|true|yes|y)
      printf 'true'
      ;;
    0|false|no|n)
      printf 'false'
      ;;
    *)
      die "$2 must be boolean: $1"
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
  PISCES_PRODUCTION_CLOSEOUT_DIR="${PISCES_PRODUCTION_CLOSEOUT_DIR:-target/pisces-production-infrastructure-closeout}"
  PISCES_PRODUCTION_CLOSEOUT_ALLOW_INCOMPLETE="$(parse_bool "${PISCES_PRODUCTION_CLOSEOUT_ALLOW_INCOMPLETE:-false}" "PISCES_PRODUCTION_CLOSEOUT_ALLOW_INCOMPLETE")"

  local closeout_dir summary_file report_file
  closeout_dir="$(resolve_path "$PISCES_PRODUCTION_CLOSEOUT_DIR")"
  summary_file="$closeout_dir/completion-summary.json"
  report_file="$closeout_dir/closeout-report.md"
  mkdir -p "$closeout_dir"

  export PISCES_REPO_ROOT
  export PISCES_COMPLETION_REQUIRE_REAL_ENV_EVIDENCE=true
  export PISCES_COMPLETION_AUDIT_OUTPUT_FILE="$summary_file"

  local audit_status
  set +e
  (cd "$PISCES_REPO_ROOT" && bash scripts/production-infrastructure-completion-audit.sh)
  audit_status=$?
  set -e

  python3 - "$summary_file" "$report_file" <<'PY'
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

summary_file = Path(sys.argv[1])
report_file = Path(sys.argv[2])
summary = json.loads(summary_file.read_text(encoding="utf-8"))


def escape_cell(value):
    return str(value if value is not None else "").replace("|", "\\|").replace("\n", " ")


def table(headers, rows):
    lines = [
        "| " + " | ".join(headers) + " |",
        "| " + " | ".join("---" for _ in headers) + " |",
    ]
    for row in rows:
        lines.append("| " + " | ".join(escape_cell(value) for value in row) + " |")
    return "\n".join(lines)


status = summary.get("status")
completion = summary.get("completionStatus")
verdict = "COMPLETE" if completion == "COMPLETE" else "INCOMPLETE"
generated_at = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

planes = summary.get("planes", [])
blocking_gates = [
    gate for gate in summary.get("gates", [])
    if gate.get("status") in {"HOLD", "FAIL"}
]

evidence = summary.get("evidence") or {}
lines = [
    "# Pisces Production Infrastructure Closeout",
    "",
    f"Generated at: `{generated_at}`",
    "",
    f"Verdict: **{verdict}**",
    "",
    table(
        ["Field", "Value"],
        [
            ["status", status],
            ["completionStatus", completion],
            ["staticStatus", summary.get("staticStatus")],
            ["realEnvironmentStatus", summary.get("realEnvironmentStatus")],
            ["repoRoot", summary.get("repoRoot")],
        ],
    ),
    "",
    "## Evidence",
    "",
    table(
        ["Evidence", "Path"],
        [
            ["releasePackageReport", evidence.get("releasePackageReport")],
            ["preprodRecordCheckSummary", evidence.get("preprodRecordCheckSummary")],
            ["releaseEvidenceManifest", evidence.get("releaseEvidenceManifest")],
            ["productionAcceptanceSummary", evidence.get("productionAcceptanceSummary")],
            ["screenshotDir", evidence.get("screenshotDir")],
        ],
    ),
    "",
    "## Planes",
    "",
    table(
        ["Plane", "Status", "Passed", "Holds", "Failed", "Total"],
        [
            [
                plane.get("plane"),
                plane.get("status"),
                plane.get("passed"),
                plane.get("holds"),
                plane.get("failed"),
                plane.get("total"),
            ]
            for plane in planes
        ],
    ),
    "",
    "## Blocking Gates",
    "",
]

if blocking_gates:
    lines.append(table(
        ["Plane", "Type", "Name", "Status", "Actual", "Expected", "Evidence"],
        [
            [
                gate.get("plane"),
                gate.get("type"),
                gate.get("name"),
                gate.get("status"),
                gate.get("actual"),
                gate.get("expected"),
                gate.get("evidence"),
            ]
            for gate in blocking_gates
        ],
    ))
else:
    lines.append("No blocking gates remain.")

lines.extend([
    "",
    "## Closeout Rule",
    "",
    "Only `completionStatus=COMPLETE` is sufficient to close the production infrastructure goal.",
    "",
])

report_file.write_text("\n".join(lines), encoding="utf-8")
print(f"Production infrastructure closeout report written: {report_file} verdict={verdict}", file=sys.stderr)
PY

  if [[ "$audit_status" -eq 0 ]]; then
    exit 0
  fi

  if [[ "$PISCES_PRODUCTION_CLOSEOUT_ALLOW_INCOMPLETE" == "true" ]]; then
    exit 0
  fi

  exit "$audit_status"
}

main "$@"
