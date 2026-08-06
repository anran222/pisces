#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  scripts/production-infrastructure-local-readiness.sh

Environment:
  PISCES_REPO_ROOT                         Repository root. Default: inferred from this script.
  PISCES_LOCAL_ENV_FILE                    Local env file loaded before readiness checks. Default: config/pisces-local.env.
  PISCES_LOCAL_STACK_ENV_FILE              Local stack env file loaded before readiness checks. Default: config/pisces-local-stack.env.
  PISCES_LOCAL_READINESS_OUTPUT_FILE       JSON output. Default: target/pisces-production-infrastructure-local-readiness/summary.json.
  PISCES_COMPLETION_SCREENSHOT_DIR         Core screenshot directory. Default: ../pisces-web/target/screenshots/core-functions-current.
  PISCES_INSTANCE_URLS                     Comma separated local service base URLs. Default: http://localhost:9990/api.
  PISCES_QIANWEN_API_KEY_ENV               Runtime API key env var to check. Default: TONGYI_API_KEY.
  PISCES_LOCAL_READINESS_CHECK_SERVICE     Check local actuator health. Default: true.
  PISCES_LOCAL_READINESS_RUN_COLLECTOR_PLAN Run collector plan-only preflight. Default: true.
  PISCES_LOCAL_READINESS_RUN_DEPENDENCY_CHECK Run local dependency preflight. Default: true.
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

load_env_file() {
  local file="$1"
  if [[ -f "$file" ]]; then
    set -a
    # shellcheck disable=SC1090
    source "$file"
    set +a
  fi
}

command_status() {
  if command -v "$1" >/dev/null 2>&1; then
    printf 'present'
    return
  fi
  printf 'missing'
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

first_instance_url() {
  python3 - "$PISCES_INSTANCE_URLS" <<'PY'
import sys

urls = [item.strip().rstrip("/") for item in sys.argv[1].split(",") if item.strip()]
if not urls:
    raise SystemExit("PISCES_INSTANCE_URLS is empty")
print(urls[0])
PY
}

git_dirty_status() {
  if ! command -v git >/dev/null 2>&1 || ! git -C "$PISCES_REPO_ROOT" rev-parse --show-toplevel >/dev/null 2>&1; then
    printf 'unknown'
    return
  fi
  if [[ -n "$(git -C "$PISCES_REPO_ROOT" status --porcelain)" ]]; then
    printf 'true'
    return
  fi
  printf 'false'
}

main() {
  if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
    usage
    exit 0
  fi

  command -v python3 >/dev/null 2>&1 || die "Missing command: python3"

  PISCES_REPO_ROOT="$(resolve_repo_root)"
  PISCES_LOCAL_ENV_FILE="$(resolve_path "${PISCES_LOCAL_ENV_FILE:-config/pisces-local.env}")"
  PISCES_LOCAL_STACK_ENV_FILE="$(resolve_path "${PISCES_LOCAL_STACK_ENV_FILE:-config/pisces-local-stack.env}")"
  load_env_file "$PISCES_LOCAL_STACK_ENV_FILE"
  load_env_file "$PISCES_LOCAL_ENV_FILE"

  PISCES_LOCAL_READINESS_OUTPUT_FILE="${PISCES_LOCAL_READINESS_OUTPUT_FILE:-target/pisces-production-infrastructure-local-readiness/summary.json}"
  PISCES_COMPLETION_SCREENSHOT_DIR="${PISCES_COMPLETION_SCREENSHOT_DIR:-../pisces-web/target/screenshots/core-functions-current}"
  PISCES_INSTANCE_URLS="${PISCES_INSTANCE_URLS:-http://localhost:9990/api}"
  PISCES_QIANWEN_API_KEY_ENV="${PISCES_QIANWEN_API_KEY_ENV:-TONGYI_API_KEY}"
  PISCES_LOCAL_READINESS_CHECK_SERVICE="${PISCES_LOCAL_READINESS_CHECK_SERVICE:-true}"
  PISCES_LOCAL_READINESS_RUN_COLLECTOR_PLAN="${PISCES_LOCAL_READINESS_RUN_COLLECTOR_PLAN:-true}"
  PISCES_LOCAL_READINESS_RUN_DEPENDENCY_CHECK="${PISCES_LOCAL_READINESS_RUN_DEPENDENCY_CHECK:-true}"

  local output_file audit_file secret_scan_file secret_scan_status readiness_dir screenshot_dir service_health_file
  local dependency_file dependency_status
  local service_health_url service_health_status collector_plan_file collector_plan_workspace collector_plan_status
  output_file="$(resolve_path "$PISCES_LOCAL_READINESS_OUTPUT_FILE")"
  readiness_dir="$(dirname "$output_file")"
  audit_file="$readiness_dir/completion-audit-summary.json"
  secret_scan_file="$readiness_dir/secret-scan-summary.json"
  dependency_file="$readiness_dir/dependency-summary.json"
  screenshot_dir="$(resolve_path "$PISCES_COMPLETION_SCREENSHOT_DIR")"
  service_health_file="$readiness_dir/service-health.json"
  collector_plan_file="$readiness_dir/collector-plan-summary.json"
  collector_plan_workspace="$readiness_dir/collector-plan-workspace"
  mkdir -p "$readiness_dir"

  set +e
  PISCES_COMPLETION_TARGET_ENVIRONMENT=local \
  PISCES_COMPLETION_SCREENSHOT_DIR="$PISCES_COMPLETION_SCREENSHOT_DIR" \
  PISCES_COMPLETION_AUDIT_OUTPUT_FILE="$audit_file" \
  bash "$PISCES_REPO_ROOT/scripts/production-infrastructure-completion-audit.sh" >/dev/null
  local audit_status=$?
  set -e

  set +e
  PISCES_SECRET_SCAN_OUTPUT_FILE="$secret_scan_file" \
  bash "$PISCES_REPO_ROOT/scripts/production-infrastructure-secret-scan.sh" >/dev/null
  secret_scan_status=$?
  set -e

  dependency_status=99
  if is_true "$PISCES_LOCAL_READINESS_RUN_DEPENDENCY_CHECK"; then
    set +e
    PISCES_LOCAL_DEPENDENCY_OUTPUT_FILE="$dependency_file" \
    bash "$PISCES_REPO_ROOT/scripts/production-infrastructure-local-dependency-check.sh" >/dev/null 2>&1
    dependency_status=$?
    set -e
  else
    dependency_status=0
  fi

  service_health_status=99
  service_health_url=""
  if is_true "$PISCES_LOCAL_READINESS_CHECK_SERVICE"; then
    service_health_url="$(first_instance_url)/actuator/health"
    if command -v curl >/dev/null 2>&1; then
      set +e
      curl -fsS --max-time 5 "$service_health_url" -o "$service_health_file" >/dev/null 2>&1
      service_health_status=$?
      set -e
    else
      service_health_status=127
    fi
  else
    service_health_status=0
  fi

  collector_plan_status=99
  if is_true "$PISCES_LOCAL_READINESS_RUN_COLLECTOR_PLAN"; then
    set +e
    PISCES_RELEASE_ID="local-readiness-plan" \
    PISCES_INSTANCE_URLS="$PISCES_INSTANCE_URLS" \
    PISCES_LOCAL_COLLECT_PLAN_ONLY=true \
    PISCES_LOCAL_EVIDENCE_WORKSPACE_DIR="$collector_plan_workspace" \
    PISCES_LOCAL_EVIDENCE_COLLECT_OUTPUT_FILE="$collector_plan_file" \
    bash "$PISCES_REPO_ROOT/scripts/production-infrastructure-local-evidence-collect.sh" >/dev/null 2>&1
    collector_plan_status=$?
    set -e
  else
    collector_plan_status=0
  fi

  export PISCES_REPO_ROOT
  export PISCES_LOCAL_ENV_FILE
  export PISCES_LOCAL_READINESS_OUTPUT_FILE="$output_file"
  export PISCES_LOCAL_COMPLETION_AUDIT_FILE="$audit_file"
  export PISCES_LOCAL_COMPLETION_AUDIT_STATUS="$audit_status"
  export PISCES_LOCAL_SECRET_SCAN_FILE="$secret_scan_file"
  export PISCES_LOCAL_SECRET_SCAN_STATUS="$secret_scan_status"
  export PISCES_LOCAL_DEPENDENCY_FILE="$dependency_file"
  export PISCES_LOCAL_DEPENDENCY_STATUS="$dependency_status"
  export PISCES_LOCAL_READINESS_RUN_DEPENDENCY_CHECK
  export PISCES_LOCAL_SCREENSHOT_DIR="$screenshot_dir"
  export PISCES_INSTANCE_URLS
  export PISCES_LOCAL_READINESS_CHECK_SERVICE
  export PISCES_LOCAL_SERVICE_HEALTH_FILE="$service_health_file"
  export PISCES_LOCAL_SERVICE_HEALTH_STATUS="$service_health_status"
  export PISCES_LOCAL_SERVICE_HEALTH_URL="$service_health_url"
  export PISCES_LOCAL_COLLECTOR_PLAN_FILE="$collector_plan_file"
  export PISCES_LOCAL_COLLECTOR_PLAN_WORKSPACE="$collector_plan_workspace"
  export PISCES_LOCAL_COLLECTOR_PLAN_STATUS="$collector_plan_status"
  export PISCES_LOCAL_READINESS_RUN_COLLECTOR_PLAN
  export PISCES_LOCAL_RUBY_STATUS
  export PISCES_LOCAL_PROMTOOL_STATUS
  export PISCES_LOCAL_GIT_DIRTY
  export PISCES_QIANWEN_API_KEY_ENV
  export PISCES_QIANWEN_API_KEY_STATUS

  PISCES_LOCAL_RUBY_STATUS="$(command_status ruby)"
  PISCES_LOCAL_PROMTOOL_STATUS="$(command_status promtool)"
  PISCES_LOCAL_GIT_DIRTY="$(git_dirty_status)"
  local qianwen_api_key_value
  qianwen_api_key_value="${!PISCES_QIANWEN_API_KEY_ENV:-}"
  case "$qianwen_api_key_value" in
    "" )
      PISCES_QIANWEN_API_KEY_STATUS=missing
      ;;
    "<local-qianwen-api-key>"|"<your-dashscope-api-key>"|*local-qianwen-api-key* )
      PISCES_QIANWEN_API_KEY_STATUS=placeholder
      ;;
    \<*\> )
      PISCES_QIANWEN_API_KEY_STATUS=placeholder
      ;;
    * )
      PISCES_QIANWEN_API_KEY_STATUS=configured
      ;;
  esac

  python3 <<'PY'
import json
import os
from datetime import datetime, timezone
from pathlib import Path

repo_root = Path(os.environ["PISCES_REPO_ROOT"])
output_file = Path(os.environ["PISCES_LOCAL_READINESS_OUTPUT_FILE"])
local_env_file = Path(os.environ["PISCES_LOCAL_ENV_FILE"])
audit_file = Path(os.environ["PISCES_LOCAL_COMPLETION_AUDIT_FILE"])
secret_scan_file = Path(os.environ["PISCES_LOCAL_SECRET_SCAN_FILE"])
dependency_file = Path(os.environ["PISCES_LOCAL_DEPENDENCY_FILE"])
screenshot_dir = Path(os.environ["PISCES_LOCAL_SCREENSHOT_DIR"])
service_health_file = Path(os.environ["PISCES_LOCAL_SERVICE_HEALTH_FILE"])
collector_plan_file = Path(os.environ["PISCES_LOCAL_COLLECTOR_PLAN_FILE"])
collector_plan_workspace = Path(os.environ["PISCES_LOCAL_COLLECTOR_PLAN_WORKSPACE"])
audit = json.loads(audit_file.read_text(encoding="utf-8"))
secret_scan = json.loads(secret_scan_file.read_text(encoding="utf-8"))
dependency_check = (
    json.loads(dependency_file.read_text(encoding="utf-8"))
    if dependency_file.is_file()
    else {}
)

checks = []


def display(path):
    try:
        return str(path.resolve().relative_to(repo_root.resolve()))
    except Exception:
        return str(path)


def add_check(name, status, actual, expected, action=None):
    item = {
        "name": name,
        "status": status,
        "actual": actual,
        "expected": expected,
    }
    if action:
        item["action"] = action
    checks.append(item)


ruby_status = os.environ["PISCES_LOCAL_RUBY_STATUS"]
promtool_status = os.environ["PISCES_LOCAL_PROMTOOL_STATUS"]
git_dirty = os.environ["PISCES_LOCAL_GIT_DIRTY"]
api_key_status = os.environ["PISCES_QIANWEN_API_KEY_STATUS"]
api_key_present = api_key_status == "configured"
api_key_env = os.environ["PISCES_QIANWEN_API_KEY_ENV"]
finalize_command = "bash scripts/production-infrastructure-local-finalize.sh"
local_env_exists = local_env_file.is_file()
local_env_display = display(local_env_file)
if local_env_exists:
    key_setup_action = (
        f"Replace only {api_key_env} in {local_env_display}, then rerun "
        f"{finalize_command}; do not commit local env files."
    )
    key_setup_stage = "replace-key-only"
else:
    key_setup_action = (
        f"Run {finalize_command}. It will create {local_env_display} and return "
        f"NEEDS_QIANWEN_API_KEY; replace only {api_key_env}, then rerun "
        f"{finalize_command}; do not commit local env files."
    )
    key_setup_stage = "bootstrap-env-then-replace-key"
secret_scan_status = secret_scan.get("status")
dependency_status_code = int(os.environ["PISCES_LOCAL_DEPENDENCY_STATUS"])
run_dependency_check = os.environ["PISCES_LOCAL_READINESS_RUN_DEPENDENCY_CHECK"].lower() in {"true", "1", "yes", "y"}
service_health_status = int(os.environ["PISCES_LOCAL_SERVICE_HEALTH_STATUS"])
service_health_url = os.environ["PISCES_LOCAL_SERVICE_HEALTH_URL"]
check_service = os.environ["PISCES_LOCAL_READINESS_CHECK_SERVICE"].lower() in {"true", "1", "yes", "y"}
collector_plan_status = int(os.environ["PISCES_LOCAL_COLLECTOR_PLAN_STATUS"])
run_collector_plan = os.environ["PISCES_LOCAL_READINESS_RUN_COLLECTOR_PLAN"].lower() in {"true", "1", "yes", "y"}

image_extensions = {".png", ".jpg", ".jpeg", ".webp"}
screenshot_count = (
    sum(1 for path in screenshot_dir.iterdir() if path.is_file() and path.suffix.lower() in image_extensions)
    if screenshot_dir.is_dir()
    else 0
)
layout_audit_file = screenshot_dir / "layout-audit.json"
layout_audit = None
layout_audit_error = None
if layout_audit_file.is_file():
    try:
        layout_audit = json.loads(layout_audit_file.read_text(encoding="utf-8"))
    except Exception as exc:
        layout_audit_error = str(exc)

add_check(
    "ruby available",
    "PASS" if ruby_status == "present" else "FAIL",
    ruby_status,
    "present",
    "Install ruby or disable strict YAML checks only for non-final dry runs.",
)
add_check(
    "promtool available",
    "PASS" if promtool_status == "present" else "FAIL",
    promtool_status,
    "present",
    "Install Prometheus promtool, for example via Homebrew prometheus package.",
)
add_check(
    "git worktree clean for final closeout",
    "PASS" if git_dirty == "false" else "HOLD",
    git_dirty,
    "false",
    "Commit or intentionally exclude current changes before final strict closeout.",
)
add_check(
    "qianwen api key provided by environment",
    "PASS" if api_key_present else "HOLD",
    api_key_status,
    f"{api_key_env} configured with a non-placeholder value",
    key_setup_action,
)
add_check(
    "local runtime env template",
    "PASS" if (Path(os.environ["PISCES_REPO_ROOT"]) / "config/pisces-local.env.example").is_file() else "HOLD",
    "present" if (Path(os.environ["PISCES_REPO_ROOT"]) / "config/pisces-local.env.example").is_file() else "missing",
    "config/pisces-local.env.example",
    "Restore the local env template so final local closeout has one supported key injection path.",
)
add_check(
    "repository secret scan",
    "PASS" if secret_scan_status == "PASS" else "FAIL",
    f"{secret_scan_status} findings={secret_scan.get('findingCount')}",
    "PASS findings=0",
    "Remove committed secrets or replace them with environment-variable placeholders.",
)

if run_dependency_check:
    dependency_summary_status = dependency_check.get("status") or f"exit {dependency_status_code}"
    dependency_holds = [
        check.get("name")
        for check in dependency_check.get("checks", [])
        if check.get("mandatory") and check.get("status") in {"HOLD", "FAIL"}
    ]
    if dependency_status_code != 0:
        dependency_readiness_status = "FAIL"
    elif dependency_summary_status == "READY_FOR_LOCAL_SERVICE_START":
        dependency_readiness_status = "PASS"
    else:
        dependency_readiness_status = "HOLD"
    add_check(
        "local runtime dependencies",
        dependency_readiness_status,
        dependency_summary_status if not dependency_holds else f"{dependency_summary_status}: {dependency_holds[:6]}",
        "READY_FOR_LOCAL_SERVICE_START",
        "Run scripts/production-infrastructure-local-dependency-stack.sh up, apply schema, then fix any remaining MySQL/Redis holds before starting backend.",
    )
else:
    add_check(
        "local runtime dependencies",
        "PASS",
        "skipped",
        "skipped",
    )

add_check(
    "core screenshot evidence directory",
    "PASS" if screenshot_count > 0 else "HOLD",
    f"{screenshot_count} image files at {screenshot_dir}" if screenshot_dir.exists() else "missing",
    "at least one core screenshot image",
    "Run scripts/production-infrastructure-local-frontend-evidence.sh or the full local finalizer before final closeout.",
)
if not layout_audit_file.is_file():
    add_check(
        "core screenshot layout audit",
        "HOLD",
        "missing",
        "layout-audit.json with status=PASS, failedCount=0, enforcedCount>=8",
        "Run scripts/production-infrastructure-local-frontend-evidence.sh or npm run capture:core from ../pisces-web.",
    )
elif layout_audit_error:
    add_check(
        "core screenshot layout audit",
        "FAIL",
        layout_audit_error,
        "valid layout-audit.json",
        "Regenerate core frontend screenshots and inspect layout-audit.json.",
    )
else:
    layout_failed_count = layout_audit.get("failedCount")
    layout_enforced_count = layout_audit.get("enforcedCount")
    layout_ok = (
        layout_audit.get("summaryType") == "pisces-web-core-layout-audit"
        and layout_audit.get("status") == "PASS"
        and layout_failed_count == 0
        and isinstance(layout_enforced_count, int)
        and layout_enforced_count >= 8
    )
    add_check(
        "core screenshot layout audit",
        "PASS" if layout_ok else "HOLD",
        (
            f"type={layout_audit.get('summaryType')} "
            f"status={layout_audit.get('status')} "
            f"failedCount={layout_failed_count} "
            f"enforcedCount={layout_enforced_count}"
        ),
        "layout-audit.json with status=PASS, failedCount=0, enforcedCount>=8",
        "Fix horizontal layout regressions and rerun scripts/production-infrastructure-local-frontend-evidence.sh.",
    )

if check_service:
    service_actual = f"curl exit {service_health_status}"
    service_status = "HOLD"
    if service_health_status == 0 and service_health_file.is_file():
        try:
            health = json.loads(service_health_file.read_text(encoding="utf-8"))
            service_actual = health.get("status") or service_actual
            service_status = "PASS" if service_actual == "UP" else "HOLD"
        except Exception as exc:
            service_actual = f"invalid health JSON: {exc}"
            service_status = "HOLD"
    add_check(
        "local service health",
        service_status,
        service_actual,
        "UP",
        f"Run scripts/production-infrastructure-local-finalize.sh or scripts/production-infrastructure-local-service.sh start and verify {service_health_url}.",
    )
else:
    add_check(
        "local service health",
        "PASS",
        "skipped",
        "skipped",
    )

if run_collector_plan:
    collector_actual = f"exit {collector_plan_status}"
    collector_status = "FAIL"
    collector_next_commands = []
    collector_local_service = {}
    if collector_plan_status == 0 and collector_plan_file.is_file():
        try:
            collector_plan = json.loads(collector_plan_file.read_text(encoding="utf-8"))
            closeout_wrapper = collector_plan.get("closeoutWrapper")
            validate_wrapper = collector_plan.get("validateWrapper")
            collector_next_commands = collector_plan.get("nextCommands") or []
            collector_local_service = collector_plan.get("localService") or {}
            local_service_problems = []
            if not collector_local_service.get("summaryFile"):
                local_service_problems.append("summaryFile")
            if collector_local_service.get("requiredBeforeCollection") is not True:
                local_service_problems.append("requiredBeforeCollection")
            if collector_local_service.get("requiredStatus") != "HEALTHY":
                local_service_problems.append("requiredStatus")
            if collector_local_service.get("requiredApiKeyStatus") != "configured":
                local_service_problems.append("requiredApiKeyStatus")
            if collector_local_service.get("requiredHealthStatus") != "UP":
                local_service_problems.append("requiredHealthStatus")
            if closeout_wrapper and validate_wrapper and collector_next_commands and not local_service_problems:
                collector_status = "PASS"
                collector_actual = (
                    f"closeoutWrapper={closeout_wrapper}; "
                    f"localServiceSummary={collector_local_service.get('summaryFile')}"
                )
            else:
                wrapper_problems = []
                if not closeout_wrapper:
                    wrapper_problems.append("closeoutWrapper")
                if not validate_wrapper:
                    wrapper_problems.append("validateWrapper")
                if not collector_next_commands:
                    wrapper_problems.append("nextCommands")
                collector_actual = "missing " + ", ".join(wrapper_problems + local_service_problems)
        except Exception as exc:
            collector_actual = f"invalid collector plan JSON: {exc}"
    add_check(
        "collector plan-only preflight",
        collector_status,
        collector_actual,
        "collector plan includes closeout wrapper, next commands, and local service summary gate",
        "Fix scripts/production-infrastructure-local-evidence-collect.sh before running final closeout.",
    )
else:
    collector_next_commands = []
    collector_local_service = {}
    add_check(
        "collector plan-only preflight",
        "PASS",
        "skipped",
        "skipped",
    )

for plane in audit.get("planes", []):
    add_check(
        f"{plane.get('plane')} audit status",
        "PASS" if plane.get("status") == "PASS" else "HOLD",
        plane.get("status"),
        "PASS",
    )

blocking_completion_gates = [
    gate for gate in audit.get("gates", [])
    if gate.get("status") in {"HOLD", "FAIL"}
]

failed = [check for check in checks if check["status"] == "FAIL"]
holds = [check for check in checks if check["status"] == "HOLD"]

if failed:
    readiness = "BLOCKED"
    estimate = "after installing missing local tooling, rerun; likely same day"
    next_commands = ["fix failed checks in this summary", "bash scripts/production-infrastructure-local-readiness.sh"]
elif holds:
    readiness = "NEEDS_LOCAL_EVIDENCE"
    estimate = (
        "roughly 0.5-1 day after replacing the local Qianwen API key and starting the backend: "
        "run scripts/production-infrastructure-local-finalize.sh, review generated evidence, "
        "then address any final local closeout holds; the project Docker stack uses an automatic Redis fault drill"
    )
    if not api_key_present:
        next_commands = [
            f"edit {local_env_display} and replace only {api_key_env}",
            finalize_command,
        ]
        if not local_env_exists:
            next_commands.insert(0, finalize_command)
    else:
        next_commands = [finalize_command]
else:
    readiness = "READY_FOR_LOCAL_CLOSEOUT"
    estimate = "roughly 1-2 hours to run scripts/production-infrastructure-local-closeout.sh"
    next_commands = ["bash scripts/production-infrastructure-local-closeout.sh"]

summary = {
    "summaryType": "pisces-production-infrastructure-local-readiness",
    "summaryVersion": 1,
    "generatedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "readiness": readiness,
    "estimatedRemaining": estimate,
    "targetEnvironment": "local",
    "finalizeCommand": finalize_command,
    "nextCommands": next_commands,
    "localEnv": {
        "file": local_env_display,
        "exists": local_env_exists,
        "keySetupStage": key_setup_stage,
    },
    "qianwenApiKey": {
        "env": api_key_env,
        "status": api_key_status,
        "configured": api_key_present,
        "setupAction": key_setup_action,
    },
    "completionAudit": {
        "file": str(audit_file),
        "status": audit.get("status"),
        "completionStatus": audit.get("completionStatus"),
        "staticStatus": audit.get("staticStatus"),
        "realEnvironmentStatus": audit.get("realEnvironmentStatus"),
    },
    "secretScan": {
        "file": str(secret_scan_file),
        "status": secret_scan.get("status"),
        "findingCount": secret_scan.get("findingCount"),
    },
    "dependencyCheck": {
        "file": str(dependency_file),
        "statusCode": dependency_status_code,
        "status": dependency_check.get("status"),
        "warningCount": dependency_check.get("warningCount"),
    },
    "serviceHealth": {
        "file": str(service_health_file),
        "statusCode": service_health_status,
        "url": service_health_url,
    },
    "collectorPlan": {
        "file": str(collector_plan_file),
        "statusCode": collector_plan_status,
        "workspace": str(collector_plan_workspace),
        "nextCommands": collector_next_commands,
        "localService": collector_local_service,
    },
    "screenshots": {
        "directory": str(screenshot_dir),
        "imageCount": screenshot_count,
        "layoutAudit": {
            "file": str(layout_audit_file),
            "present": layout_audit_file.is_file(),
            "status": layout_audit.get("status") if layout_audit else None,
            "failedCount": layout_audit.get("failedCount") if layout_audit else None,
            "enforcedCount": layout_audit.get("enforcedCount") if layout_audit else None,
        },
    },
    "checks": checks,
    "completionBlockingGates": blocking_completion_gates,
}

output_file.write_text(json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
print(f"Production infrastructure local readiness written: {output_file} readiness={readiness}", file=os.sys.stderr)
PY
}

main "$@"
