#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  scripts/production-infrastructure-local-bootstrap.sh

Environment:
  PISCES_REPO_ROOT                         Repository root. Default: inferred from this script.
  PISCES_LOCAL_ENV_TEMPLATE                Local env template. Default: config/pisces-local.env.example.
  PISCES_LOCAL_ENV_FILE                    Local env file. Default: config/pisces-local.env.
  PISCES_LOCAL_BOOTSTRAP_OUTPUT_FILE       JSON output. Default: target/pisces-production-infrastructure-local-bootstrap/summary.json.
  PISCES_LOCAL_BOOTSTRAP_CREATE_ENV        Create local env from template when missing. Default: true.
  PISCES_LOCAL_BOOTSTRAP_STRICT            Exit non-zero until local env is ready to source. Default: false.
  PISCES_QIANWEN_API_KEY_ENV               Runtime API key env var. Default: TONGYI_API_KEY.
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

git_ignored_status() {
  local path="$1"
  if ! command -v git >/dev/null 2>&1 || ! git -C "$PISCES_REPO_ROOT" rev-parse --show-toplevel >/dev/null 2>&1; then
    printf 'unknown'
    return
  fi
  if git -C "$PISCES_REPO_ROOT" check-ignore -q -- "$path"; then
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
  PISCES_LOCAL_ENV_TEMPLATE="${PISCES_LOCAL_ENV_TEMPLATE:-config/pisces-local.env.example}"
  PISCES_LOCAL_ENV_FILE="${PISCES_LOCAL_ENV_FILE:-config/pisces-local.env}"
  PISCES_LOCAL_BOOTSTRAP_OUTPUT_FILE="${PISCES_LOCAL_BOOTSTRAP_OUTPUT_FILE:-target/pisces-production-infrastructure-local-bootstrap/summary.json}"
  PISCES_LOCAL_BOOTSTRAP_CREATE_ENV="${PISCES_LOCAL_BOOTSTRAP_CREATE_ENV:-true}"
  PISCES_LOCAL_BOOTSTRAP_STRICT="${PISCES_LOCAL_BOOTSTRAP_STRICT:-false}"
  PISCES_QIANWEN_API_KEY_ENV="${PISCES_QIANWEN_API_KEY_ENV:-TONGYI_API_KEY}"

  local template_file env_file output_file env_created git_ignored
  template_file="$(resolve_path "$PISCES_LOCAL_ENV_TEMPLATE")"
  env_file="$(resolve_path "$PISCES_LOCAL_ENV_FILE")"
  output_file="$(resolve_path "$PISCES_LOCAL_BOOTSTRAP_OUTPUT_FILE")"
  env_created=false

  [[ -f "$template_file" ]] || die "Missing local env template: $template_file"
  mkdir -p "$(dirname "$output_file")"

  if [[ ! -f "$env_file" ]] && is_true "$PISCES_LOCAL_BOOTSTRAP_CREATE_ENV"; then
    mkdir -p "$(dirname "$env_file")"
    cp "$template_file" "$env_file"
    chmod 600 "$env_file" 2>/dev/null || true
    env_created=true
  fi

  git_ignored="$(git_ignored_status "$env_file")"

  export PISCES_REPO_ROOT
  export PISCES_LOCAL_BOOTSTRAP_TEMPLATE_FILE="$template_file"
  export PISCES_LOCAL_BOOTSTRAP_ENV_FILE="$env_file"
  export PISCES_LOCAL_BOOTSTRAP_OUTPUT_FILE="$output_file"
  export PISCES_LOCAL_BOOTSTRAP_ENV_CREATED="$env_created"
  export PISCES_LOCAL_BOOTSTRAP_GIT_IGNORED="$git_ignored"
  export PISCES_LOCAL_BOOTSTRAP_STRICT
  export PISCES_QIANWEN_API_KEY_ENV

  set +e
  python3 <<'PY'
import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

repo_root = Path(os.environ["PISCES_REPO_ROOT"])
template_file = Path(os.environ["PISCES_LOCAL_BOOTSTRAP_TEMPLATE_FILE"])
env_file = Path(os.environ["PISCES_LOCAL_BOOTSTRAP_ENV_FILE"])
output_file = Path(os.environ["PISCES_LOCAL_BOOTSTRAP_OUTPUT_FILE"])
env_created = os.environ["PISCES_LOCAL_BOOTSTRAP_ENV_CREATED"] == "true"
git_ignored = os.environ["PISCES_LOCAL_BOOTSTRAP_GIT_IGNORED"]
strict = os.environ["PISCES_LOCAL_BOOTSTRAP_STRICT"].lower() in {"true", "1", "yes", "y"}
api_key_env = os.environ["PISCES_QIANWEN_API_KEY_ENV"]

checks = []


def display(path):
    try:
        return str(path.relative_to(repo_root))
    except ValueError:
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


def read_assignment(text, name):
    for raw_line in text.splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line[len("export "):].strip()
        if not line.startswith(f"{name}="):
            continue
        value = line.split("=", 1)[1].split("#", 1)[0].strip()
        return value.strip('"').strip("'").strip()
    return None


def is_placeholder(value):
    if value is None:
        return True
    normalized = value.strip()
    if not normalized:
        return True
    if normalized in {"<local-qianwen-api-key>", "<your-dashscope-api-key>"}:
        return True
    if normalized.startswith("<") and normalized.endswith(">"):
        return True
    if "local-qianwen-api-key" in normalized:
        return True
    return False


template_text = template_file.read_text(encoding="utf-8")
env_exists = env_file.is_file()
env_text = env_file.read_text(encoding="utf-8") if env_exists else ""
api_key_value = read_assignment(env_text, api_key_env) if env_exists else None
api_key_configured = env_exists and not is_placeholder(api_key_value)
mysql_defaults_present = all(
    marker in env_text
    for marker in (
        "MYSQL_URL=\"jdbc:mysql://localhost:3306/pisces",
        "MYSQL_USERNAME=\"root\"",
        "MYSQL_PASSWORD=\"\"",
    )
)
scoped_keys_present = "PISCES_API_KEY_SPECS=" in env_text and "runtime-key|shop-app|sdk|runtime" in env_text
tongyi_production_model_present = 'TONGYI_MODEL="qwen3.7-max"' in env_text
tongyi_production_mode_present = 'TONGYI_API_MODE="dashscope"' in env_text
tongyi_fallback_model_present = 'TONGYI_FALLBACK_MODEL="qwen3.7-max"' in env_text
tongyi_fallback_mode_present = 'TONGYI_FALLBACK_API_MODE="dashscope"' in env_text
tongyi_model_strategy_present = all((
    tongyi_production_model_present,
    tongyi_production_mode_present,
    tongyi_fallback_model_present,
    tongyi_fallback_mode_present,
))
template_single_key = "replace TONGYI_API_KEY" in template_text and "<local-qianwen-api-key>" in template_text

add_check(
    "local env template present",
    "PASS",
    display(template_file),
    "config/pisces-local.env.example",
)
add_check(
    "local env file present",
    "PASS" if env_exists else "HOLD",
    f"{display(env_file)} created={env_created}" if env_exists else "missing",
    display(env_file),
    f"Run scripts/production-infrastructure-local-bootstrap.sh to create {display(env_file)}.",
)
add_check(
    "local env file ignored by git",
    "PASS" if git_ignored == "true" else "FAIL",
    git_ignored,
    "true",
    "Keep config/*.env ignored so the local Qianwen key cannot be committed.",
)
add_check(
    "single key replacement contract",
    "PASS" if template_single_key else "FAIL",
    "documented" if template_single_key else "missing",
    "template tells users to replace only TONGYI_API_KEY for default local setup",
)
add_check(
    "qianwen api key placeholder replaced",
    "PASS" if api_key_configured else "HOLD",
    "configured" if api_key_configured else "placeholder",
    f"{api_key_env} configured in {display(env_file)}",
    f"Edit {display(env_file)} and replace only {api_key_env}; keep other defaults unless your local MySQL differs.",
)
add_check(
    "mysql defaults kept for local setup",
    "PASS" if mysql_defaults_present else "HOLD",
    "present" if mysql_defaults_present else "missing or changed",
    "localhost pisces database, root user, empty password",
    "Only change MySQL values when your local database is not using the project defaults.",
)
add_check(
    "local scoped api keys present",
    "PASS" if scoped_keys_present else "HOLD",
    "present" if scoped_keys_present else "missing",
    "runtime-key, ops-key, admin-key local scoped keys",
    "Restore PISCES_API_KEY_SPECS from config/pisces-local.env.example.",
)
add_check(
    "local tongyi production model strategy present",
    "PASS" if tongyi_model_strategy_present else "HOLD",
    "qwen3.7-max via dashscope" if tongyi_model_strategy_present else "missing or changed",
    "TONGYI_MODEL defaults to qwen3.7-max with DashScope mode",
    "Restore TONGYI_MODEL and TONGYI_FALLBACK_MODEL from config/pisces-local.env.example.",
)

failed = [check for check in checks if check["status"] == "FAIL"]
holds = [check for check in checks if check["status"] == "HOLD"]
if failed:
    status = "BLOCKED"
elif not api_key_configured:
    status = "NEEDS_QIANWEN_API_KEY"
elif holds:
    status = "NEEDS_LOCAL_ENV_REVIEW"
else:
    status = "READY_TO_SOURCE"

next_commands = []
if not api_key_configured:
    next_commands.append(f"edit {display(env_file)} and replace only {api_key_env}")
next_commands.append("bash scripts/production-infrastructure-local-finalize.sh")

summary = {
    "summaryType": "pisces-production-infrastructure-local-bootstrap",
    "summaryVersion": 1,
    "generatedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "status": status,
    "targetEnvironment": "local",
    "envFile": display(env_file),
    "templateFile": display(template_file),
    "envCreated": env_created,
    "qianwenApiKeyEnv": api_key_env,
    "qianwenApiKey": "configured" if api_key_configured else "placeholder",
    "singleReplacementField": api_key_env,
    "checks": checks,
    "nextCommands": next_commands,
}

output_file.write_text(json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
print(
    f"Production infrastructure local bootstrap written: {output_file} status={status} env={display(env_file)}",
    file=sys.stderr,
)

if strict and status != "READY_TO_SOURCE":
    sys.exit(1)
PY
  local python_status=$?
  set -e
  return "$python_status"
}

main "$@"
