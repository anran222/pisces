#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  scripts/production-infrastructure-local-frontend-evidence.sh

Environment:
  PISCES_REPO_ROOT                            Repository root. Default: inferred from this script.
  PISCES_WEB_DIR                              Frontend repository directory. Default: ../pisces-web.
  PISCES_WEB_BASE_URL                         Frontend base URL. Default: http://127.0.0.1:3040.
  PISCES_WEB_HOST                             Frontend dev server host. Default: 127.0.0.1.
  PISCES_WEB_PORT                             Frontend dev server port. Default: 3040.
  PISCES_COMPLETION_SCREENSHOT_DIR            Screenshot output directory. Default: ../pisces-web/target/screenshots/core-functions-current.
  PISCES_WEB_LAYOUT_AUDIT_FILE                Frontend layout audit JSON. Default: <screenshot-dir>/layout-audit.json.
  PISCES_LOCAL_FRONTEND_OUTPUT_FILE           JSON output. Default: target/pisces-production-infrastructure-local-frontend-evidence/summary.json.
  PISCES_LOCAL_FRONTEND_DRY_RUN               Write plan only. Default: false.
  PISCES_LOCAL_FRONTEND_START_DEV_SERVER      Start Vite server when base URL is not already reachable. Default: true.
  PISCES_LOCAL_FRONTEND_RUN_AUDIT             Run npm run audit:prod-high before capture. Default: true.
  PISCES_LOCAL_FRONTEND_MIN_SCREENSHOT_COUNT  Minimum PNG count after capture. Default: 14.
  PISCES_LOCAL_FRONTEND_REQUIRED_SCREENSHOTS  Comma-separated screenshots that must be present and pass layout audit.
                                              Default: 09-variant-lab-tongyi-model-evidence.png.

This script generates local frontend evidence required by the final completion
audit. It does not require the backend service because the frontend capture
script installs API mocks for core screens.
USAGE
}

log() {
  printf '[%s] %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*" >&2
}

die() {
  log "ERROR: $*"
  exit 1
}

is_true() {
  case "${1:-}" in
    true|TRUE|True|1|yes|YES|Yes|y|Y)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "Missing command: $1"
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

url_ready() {
  python3 - "$PISCES_WEB_BASE_URL" <<'PY'
import sys
import urllib.request

url = sys.argv[1]
try:
    with urllib.request.urlopen(url, timeout=2) as response:
        sys.exit(0 if 200 <= response.status < 500 else 1)
except Exception:
    sys.exit(1)
PY
}

count_screenshots() {
  if [[ ! -d "$PISCES_COMPLETION_SCREENSHOT_DIR_RESOLVED" ]]; then
    printf '0'
    return
  fi
  find "$PISCES_COMPLETION_SCREENSHOT_DIR_RESOLVED" -maxdepth 1 -type f -name '*.png' | wc -l | tr -d ' '
}

required_screenshots_ready() {
  python3 <<'PY'
import json
import os
import sys
from pathlib import Path

screenshot_dir = Path(os.environ["PISCES_COMPLETION_SCREENSHOT_DIR_RESOLVED"])
layout_audit_file = Path(os.environ["PISCES_WEB_LAYOUT_AUDIT_FILE_RESOLVED"])
required = [
    item.strip()
    for item in os.environ["PISCES_LOCAL_FRONTEND_REQUIRED_SCREENSHOTS"].replace("\n", ",").split(",")
    if item.strip()
]
if not required:
    sys.exit(0)
if not layout_audit_file.is_file():
    sys.exit(1)
try:
    layout_audit = json.loads(layout_audit_file.read_text(encoding="utf-8"))
except Exception:
    sys.exit(1)
records = {
    record.get("fileName"): record
    for record in layout_audit.get("records", [])
    if isinstance(record, dict)
}
for file_name in required:
    if not (screenshot_dir / file_name).is_file():
        sys.exit(1)
    record = records.get(file_name)
    if record is None or record.get("status") != "PASS":
        sys.exit(1)
sys.exit(0)
PY
}

write_summary() {
  local status="$1"
  local exit_code="$2"
  local message="$3"
  local audit_exit="${4:-not_run}"
  local server_exit="${5:-not_run}"
  local capture_exit="${6:-not_run}"

  export PISCES_LOCAL_FRONTEND_SUMMARY_STATUS="$status"
  export PISCES_LOCAL_FRONTEND_SUMMARY_EXIT_CODE="$exit_code"
  export PISCES_LOCAL_FRONTEND_SUMMARY_MESSAGE="$message"
  export PISCES_LOCAL_FRONTEND_AUDIT_EXIT="$audit_exit"
  export PISCES_LOCAL_FRONTEND_SERVER_EXIT="$server_exit"
  export PISCES_LOCAL_FRONTEND_CAPTURE_EXIT="$capture_exit"
  export PISCES_LOCAL_FRONTEND_SCREENSHOT_COUNT="$(count_screenshots)"

  python3 <<'PY'
import json
import os
from datetime import datetime, timezone
from pathlib import Path

repo_root = Path(os.environ["PISCES_REPO_ROOT"]).resolve()
output_file = Path(os.environ["PISCES_LOCAL_FRONTEND_OUTPUT_FILE_RESOLVED"])


def display(path_value):
    path = Path(path_value)
    try:
        return str(path.resolve().relative_to(repo_root))
    except ValueError:
        return str(path)


def step(name, exit_code, evidence=None):
    item = {
        "name": name,
        "exitCode": None if exit_code == "not_run" else int(exit_code),
        "status": "NOT_RUN" if exit_code == "not_run" else ("PASS" if int(exit_code) == 0 else "FAIL"),
    }
    if evidence:
        item["evidence"] = evidence
    return item


status = os.environ["PISCES_LOCAL_FRONTEND_SUMMARY_STATUS"]
web_dir = Path(os.environ["PISCES_WEB_DIR_RESOLVED"])
screenshot_dir = Path(os.environ["PISCES_COMPLETION_SCREENSHOT_DIR_RESOLVED"])
layout_audit_file = Path(os.environ["PISCES_WEB_LAYOUT_AUDIT_FILE_RESOLVED"])
next_commands = []
if status in {"FRONTEND_WEB_DIR_MISSING", "FRONTEND_PACKAGE_MISSING"}:
    next_commands.extend([
        "cd ../pisces-web",
        "npm install",
        "npm run capture:core",
    ])
elif status == "FRONTEND_AUDIT_FAILED":
    next_commands.extend([
        "cd ../pisces-web",
        "npm run audit:prod-high",
    ])
elif status == "FRONTEND_SERVER_FAILED":
    next_commands.extend([
        f"cd {display(web_dir)}",
        f"npm run dev -- --host {os.environ['PISCES_WEB_HOST']} --port {os.environ['PISCES_WEB_PORT']}",
    ])
elif status == "FRONTEND_CAPTURE_FAILED":
    next_commands.extend([
        f"cd {display(web_dir)}",
        f"PISCES_WEB_BASE_URL=\"{os.environ['PISCES_WEB_BASE_URL']}\" "
        f"PISCES_WEB_SCREENSHOT_DIR=\"{screenshot_dir}\" "
        f"PISCES_WEB_LAYOUT_AUDIT_FILE=\"{layout_audit_file}\" npm run capture:core",
    ])
elif status == "PLAN_ONLY":
    next_commands.extend([
        f"cd {display(web_dir)}",
        "npm run audit:prod-high",
        f"npm run dev -- --host {os.environ['PISCES_WEB_HOST']} --port {os.environ['PISCES_WEB_PORT']}",
        f"PISCES_WEB_BASE_URL=\"{os.environ['PISCES_WEB_BASE_URL']}\" "
        f"PISCES_WEB_SCREENSHOT_DIR=\"{screenshot_dir}\" "
        f"PISCES_WEB_LAYOUT_AUDIT_FILE=\"{layout_audit_file}\" npm run capture:core",
    ])

layout_audit = {
    "summaryFile": display(layout_audit_file),
    "present": layout_audit_file.is_file(),
    "status": None,
    "enforcedCount": None,
    "failedCount": None,
}
layout_records = {}
if layout_audit_file.is_file():
    try:
        layout_audit_summary = json.loads(layout_audit_file.read_text(encoding="utf-8"))
        layout_audit["status"] = layout_audit_summary.get("status")
        layout_audit["enforcedCount"] = layout_audit_summary.get("enforcedCount")
        layout_audit["failedCount"] = layout_audit_summary.get("failedCount")
        layout_records = {
            item.get("fileName"): item
            for item in layout_audit_summary.get("records", [])
            if isinstance(item, dict)
        }
    except Exception as exc:
        layout_audit["status"] = "INVALID_JSON"
        layout_audit["error"] = str(exc)

required_screenshots = [
    item.strip()
    for item in os.environ["PISCES_LOCAL_FRONTEND_REQUIRED_SCREENSHOTS"].replace("\n", ",").split(",")
    if item.strip()
]
required_screenshot_results = []
for file_name in required_screenshots:
    screenshot_file = screenshot_dir / file_name
    layout_record = layout_records.get(file_name)
    required_screenshot_results.append({
        "fileName": file_name,
        "present": screenshot_file.is_file(),
        "path": display(screenshot_file),
        "inLayoutAudit": layout_record is not None,
        "layoutStatus": layout_record.get("status") if layout_record else None,
    })
missing_required_screenshots = [
    item["fileName"]
    for item in required_screenshot_results
    if not item["present"] or not item["inLayoutAudit"] or item["layoutStatus"] != "PASS"
]

summary = {
    "summaryType": "pisces-production-infrastructure-local-frontend-evidence",
    "summaryVersion": 1,
    "generatedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "status": status,
    "exitCode": int(os.environ["PISCES_LOCAL_FRONTEND_SUMMARY_EXIT_CODE"]),
    "message": os.environ["PISCES_LOCAL_FRONTEND_SUMMARY_MESSAGE"],
    "targetEnvironment": "local",
    "dryRun": os.environ["PISCES_LOCAL_FRONTEND_DRY_RUN"].lower() in {"true", "1", "yes", "y"},
    "webDir": display(web_dir),
    "baseUrl": os.environ["PISCES_WEB_BASE_URL"],
    "host": os.environ["PISCES_WEB_HOST"],
    "port": int(os.environ["PISCES_WEB_PORT"]),
    "startDevServer": os.environ["PISCES_LOCAL_FRONTEND_START_DEV_SERVER"].lower()
    in {"true", "1", "yes", "y"},
    "runAudit": os.environ["PISCES_LOCAL_FRONTEND_RUN_AUDIT"].lower() in {"true", "1", "yes", "y"},
    "serverAlreadyRunning": os.environ["PISCES_LOCAL_FRONTEND_SERVER_ALREADY_RUNNING"].lower()
    in {"true", "1", "yes", "y"},
    "startedServer": os.environ["PISCES_LOCAL_FRONTEND_STARTED_SERVER"].lower() in {"true", "1", "yes", "y"},
    "minScreenshotCount": int(os.environ["PISCES_LOCAL_FRONTEND_MIN_SCREENSHOT_COUNT"]),
    "screenshotCount": int(os.environ["PISCES_LOCAL_FRONTEND_SCREENSHOT_COUNT"]),
    "layoutAudit": layout_audit,
    "requiredScreenshots": required_screenshot_results,
    "missingRequiredScreenshots": missing_required_screenshots,
    "steps": [
        step("frontend production high severity audit", os.environ["PISCES_LOCAL_FRONTEND_AUDIT_EXIT"]),
        step("frontend dev server", os.environ["PISCES_LOCAL_FRONTEND_SERVER_EXIT"], display(os.environ["PISCES_LOCAL_FRONTEND_SERVER_LOG_FILE"])),
        step("frontend core screenshot capture", os.environ["PISCES_LOCAL_FRONTEND_CAPTURE_EXIT"], display(screenshot_dir)),
    ],
    "outputs": {
        "summary": display(output_file),
        "serverLog": display(os.environ["PISCES_LOCAL_FRONTEND_SERVER_LOG_FILE"]),
        "screenshotDir": display(screenshot_dir),
        "layoutAudit": display(layout_audit_file),
    },
    "commands": [
        f"cd {display(web_dir)} && npm run audit:prod-high",
        f"cd {display(web_dir)} && npm run dev -- --host {os.environ['PISCES_WEB_HOST']} --port {os.environ['PISCES_WEB_PORT']}",
        (
            f"cd {display(web_dir)} && PISCES_WEB_BASE_URL=\"{os.environ['PISCES_WEB_BASE_URL']}\" "
            f"PISCES_WEB_SCREENSHOT_DIR=\"{screenshot_dir}\" "
            f"PISCES_WEB_LAYOUT_AUDIT_FILE=\"{layout_audit_file}\" npm run capture:core"
        ),
    ],
    "nextCommands": next_commands,
}
output_file.write_text(json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
print(f"Production infrastructure local frontend evidence written: {output_file} status={status}", file=os.sys.stderr)
PY
}

PISCES_LOCAL_FRONTEND_STARTED_PID=""

cleanup() {
  if [[ -n "${PISCES_LOCAL_FRONTEND_STARTED_PID:-}" ]] \
    && kill -0 "$PISCES_LOCAL_FRONTEND_STARTED_PID" >/dev/null 2>&1; then
    kill "$PISCES_LOCAL_FRONTEND_STARTED_PID" >/dev/null 2>&1 || true
    wait "$PISCES_LOCAL_FRONTEND_STARTED_PID" >/dev/null 2>&1 || true
  fi
}

main() {
  if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
    usage
    exit 0
  fi

  require_command python3
  require_command bash
  require_command npm

  PISCES_REPO_ROOT="$(resolve_repo_root)"
  PISCES_WEB_DIR="$(resolve_path "${PISCES_WEB_DIR:-../pisces-web}")"
  PISCES_WEB_HOST="${PISCES_WEB_HOST:-127.0.0.1}"
  PISCES_WEB_PORT="${PISCES_WEB_PORT:-3040}"
  PISCES_WEB_BASE_URL="${PISCES_WEB_BASE_URL:-http://${PISCES_WEB_HOST}:${PISCES_WEB_PORT}}"
  PISCES_COMPLETION_SCREENSHOT_DIR="$(resolve_path "${PISCES_COMPLETION_SCREENSHOT_DIR:-../pisces-web/target/screenshots/core-functions-current}")"
  PISCES_WEB_LAYOUT_AUDIT_FILE="$(resolve_path "${PISCES_WEB_LAYOUT_AUDIT_FILE:-${PISCES_COMPLETION_SCREENSHOT_DIR}/layout-audit.json}")"
  PISCES_LOCAL_FRONTEND_OUTPUT_FILE="$(resolve_path "${PISCES_LOCAL_FRONTEND_OUTPUT_FILE:-target/pisces-production-infrastructure-local-frontend-evidence/summary.json}")"
  PISCES_LOCAL_FRONTEND_DRY_RUN="${PISCES_LOCAL_FRONTEND_DRY_RUN:-false}"
  PISCES_LOCAL_FRONTEND_START_DEV_SERVER="${PISCES_LOCAL_FRONTEND_START_DEV_SERVER:-true}"
  PISCES_LOCAL_FRONTEND_RUN_AUDIT="${PISCES_LOCAL_FRONTEND_RUN_AUDIT:-true}"
  PISCES_LOCAL_FRONTEND_MIN_SCREENSHOT_COUNT="${PISCES_LOCAL_FRONTEND_MIN_SCREENSHOT_COUNT:-14}"
  PISCES_LOCAL_FRONTEND_REQUIRED_SCREENSHOTS="${PISCES_LOCAL_FRONTEND_REQUIRED_SCREENSHOTS:-09-variant-lab-tongyi-model-evidence.png}"

  local output_dir
  output_dir="$(dirname "$PISCES_LOCAL_FRONTEND_OUTPUT_FILE")"
  mkdir -p "$output_dir"
  PISCES_LOCAL_FRONTEND_SERVER_LOG_FILE="${PISCES_LOCAL_FRONTEND_SERVER_LOG_FILE:-$output_dir/frontend-dev-server.log}"

  export PISCES_REPO_ROOT
  export PISCES_WEB_DIR_RESOLVED="$PISCES_WEB_DIR"
  export PISCES_WEB_HOST
  export PISCES_WEB_PORT
  export PISCES_WEB_BASE_URL
  export PISCES_COMPLETION_SCREENSHOT_DIR_RESOLVED="$PISCES_COMPLETION_SCREENSHOT_DIR"
  export PISCES_WEB_LAYOUT_AUDIT_FILE_RESOLVED="$PISCES_WEB_LAYOUT_AUDIT_FILE"
  export PISCES_LOCAL_FRONTEND_OUTPUT_FILE_RESOLVED="$PISCES_LOCAL_FRONTEND_OUTPUT_FILE"
  export PISCES_LOCAL_FRONTEND_DRY_RUN
  export PISCES_LOCAL_FRONTEND_START_DEV_SERVER
  export PISCES_LOCAL_FRONTEND_RUN_AUDIT
  export PISCES_LOCAL_FRONTEND_MIN_SCREENSHOT_COUNT
  export PISCES_LOCAL_FRONTEND_REQUIRED_SCREENSHOTS
  export PISCES_LOCAL_FRONTEND_SERVER_LOG_FILE
  export PISCES_LOCAL_FRONTEND_SERVER_ALREADY_RUNNING=false
  export PISCES_LOCAL_FRONTEND_STARTED_SERVER=false

  if [[ ! -d "$PISCES_WEB_DIR" ]]; then
    write_summary "FRONTEND_WEB_DIR_MISSING" 1 "Frontend repository directory is missing."
    return 1
  fi
  if [[ ! -f "$PISCES_WEB_DIR/package.json" ]]; then
    write_summary "FRONTEND_PACKAGE_MISSING" 1 "Frontend package.json is missing."
    return 1
  fi

  if is_true "$PISCES_LOCAL_FRONTEND_DRY_RUN"; then
    write_summary "PLAN_ONLY" 0 "Dry run only; no frontend command was executed."
    return
  fi

  local audit_exit="not_run"
  if is_true "$PISCES_LOCAL_FRONTEND_RUN_AUDIT"; then
    log "Running frontend production high severity audit"
    set +e
    (cd "$PISCES_WEB_DIR" && npm run audit:prod-high)
    audit_exit=$?
    set -e
    if [[ "$audit_exit" -ne 0 ]]; then
      write_summary "FRONTEND_AUDIT_FAILED" "$audit_exit" "Frontend production high severity audit failed." \
        "$audit_exit"
      return "$audit_exit"
    fi
  fi

  local server_exit="not_run"
  if url_ready; then
    PISCES_LOCAL_FRONTEND_SERVER_ALREADY_RUNNING=true
    server_exit=0
  elif is_true "$PISCES_LOCAL_FRONTEND_START_DEV_SERVER"; then
    log "Starting frontend dev server"
    : >"$PISCES_LOCAL_FRONTEND_SERVER_LOG_FILE"
    (
      cd "$PISCES_WEB_DIR"
      npm run dev -- --host "$PISCES_WEB_HOST" --port "$PISCES_WEB_PORT"
    ) >"$PISCES_LOCAL_FRONTEND_SERVER_LOG_FILE" 2>&1 &
    PISCES_LOCAL_FRONTEND_STARTED_PID="$!"
    PISCES_LOCAL_FRONTEND_STARTED_SERVER=true
    export PISCES_LOCAL_FRONTEND_STARTED_SERVER
    trap cleanup EXIT

    local deadline
    deadline=$((SECONDS + ${PISCES_LOCAL_FRONTEND_READY_TIMEOUT_SECONDS:-60}))
    server_exit=1
    while [[ "$SECONDS" -lt "$deadline" ]]; do
      if url_ready; then
        server_exit=0
        break
      fi
      if ! kill -0 "$PISCES_LOCAL_FRONTEND_STARTED_PID" >/dev/null 2>&1; then
        server_exit=1
        break
      fi
      sleep 1
    done
  else
    server_exit=1
  fi

  if [[ "$server_exit" -ne 0 ]]; then
    write_summary "FRONTEND_SERVER_FAILED" "$server_exit" "Frontend dev server did not become reachable." \
      "$audit_exit" "$server_exit"
    return "$server_exit"
  fi

  log "Capturing core frontend screenshots"
  mkdir -p "$PISCES_COMPLETION_SCREENSHOT_DIR"
  set +e
  (
    cd "$PISCES_WEB_DIR"
    PISCES_WEB_BASE_URL="$PISCES_WEB_BASE_URL" \
      PISCES_WEB_SCREENSHOT_DIR="$PISCES_COMPLETION_SCREENSHOT_DIR" \
      PISCES_WEB_LAYOUT_AUDIT_FILE="$PISCES_WEB_LAYOUT_AUDIT_FILE" \
      PISCES_WEB_LAYOUT_AUDIT_STRICT=true \
      npm run capture:core
  )
  local capture_exit=$?
  set -e

  local screenshot_count
  screenshot_count="$(count_screenshots)"
  if [[ "$capture_exit" -ne 0 || "$screenshot_count" -lt "$PISCES_LOCAL_FRONTEND_MIN_SCREENSHOT_COUNT" ]] \
    || ! required_screenshots_ready; then
    local failed_capture_exit="$capture_exit"
    if [[ "$failed_capture_exit" -eq 0 ]]; then
      failed_capture_exit=1
    fi
    write_summary "FRONTEND_CAPTURE_FAILED" "$failed_capture_exit" "Frontend core screenshot capture failed or produced too few screenshots." \
      "$audit_exit" "$server_exit" "$capture_exit"
    return 1
  fi

  write_summary "PASS" 0 "Frontend production audit and core screenshot capture completed." \
    "$audit_exit" "$server_exit" "$capture_exit"
}

main "$@"
