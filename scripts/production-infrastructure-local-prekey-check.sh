#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  scripts/production-infrastructure-local-prekey-check.sh

Environment:
  PISCES_REPO_ROOT                         Repository root. Default: inferred from this script.
  PISCES_LOCAL_ENV_FILE                    Local env file. Default: config/pisces-local.env.
  PISCES_LOCAL_STACK_ENV_FILE              Local stack env file. Default: config/pisces-local-stack.env.
  PISCES_LOCAL_PREKEY_OUTPUT_FILE          JSON output. Default: target/pisces-production-infrastructure-local-prekey/summary.json.
  PISCES_QIANWEN_API_KEY_ENV               Runtime API key env var. Default: TONGYI_API_KEY.

This is the pre-key local production rehearsal. It proves the local env exists
and is ignored, confirms the real finalizer safely stops while the Qianwen key
is missing or placeholder, then runs the finalizer in dry-run mode with a
temporary non-secret key value to verify the complete finalization plan.
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

qianwen_key_status() {
  local value="${!PISCES_QIANWEN_API_KEY_ENV:-}"
  case "$value" in
    "" )
      printf 'missing'
      ;;
    "<local-qianwen-api-key>"|"<your-dashscope-api-key>"|*local-qianwen-api-key* )
      printf 'placeholder'
      ;;
    \<*\> )
      printf 'placeholder'
      ;;
    * )
      printf 'configured'
      ;;
  esac
}

git_ignored_status() {
  local file="$1"
  if ! command -v git >/dev/null 2>&1 || ! git -C "$PISCES_REPO_ROOT" rev-parse --show-toplevel >/dev/null 2>&1; then
    printf 'unknown'
    return
  fi
  if git -C "$PISCES_REPO_ROOT" check-ignore -q "$file"; then
    printf 'ignored'
    return
  fi
  printf 'not_ignored'
}

write_failure_summary() {
  local output_file="$1"
  local message="$2"
  local current_key_status="$3"
  local env_file="$4"
  local env_ignored="$5"
  local placeholder_summary="$6"
  local plan_summary="$7"
  export PISCES_LOCAL_PREKEY_STATUS="FAILED"
  export PISCES_LOCAL_PREKEY_MESSAGE="$message"
  export PISCES_LOCAL_PREKEY_KEY_STATUS="$current_key_status"
  export PISCES_LOCAL_PREKEY_ENV_FILE="$env_file"
  export PISCES_LOCAL_PREKEY_ENV_IGNORED="$env_ignored"
  export PISCES_LOCAL_PREKEY_PLACEHOLDER_SUMMARY="$placeholder_summary"
  export PISCES_LOCAL_PREKEY_PLAN_SUMMARY="$plan_summary"
  export PISCES_LOCAL_PREKEY_OUTPUT_FILE_RESOLVED="$output_file"
  write_summary
}

write_summary() {
  python3 <<'PY'
import json
import os
from datetime import datetime, timezone
from pathlib import Path

repo_root = Path(os.environ["PISCES_REPO_ROOT"]).resolve()
output_file = Path(os.environ["PISCES_LOCAL_PREKEY_OUTPUT_FILE_RESOLVED"])
env_file = Path(os.environ["PISCES_LOCAL_PREKEY_ENV_FILE"])
stack_env_file = Path(os.environ["PISCES_LOCAL_PREKEY_STACK_ENV_FILE"])
placeholder_summary = Path(os.environ["PISCES_LOCAL_PREKEY_PLACEHOLDER_SUMMARY"])
plan_summary = Path(os.environ["PISCES_LOCAL_PREKEY_PLAN_SUMMARY"])


def display(path_value):
    path = Path(path_value)
    try:
        return str(path.resolve().relative_to(repo_root))
    except Exception:
        return str(path)


def load_json(path):
    if path.is_file():
        return json.loads(path.read_text(encoding="utf-8"))
    return {}


placeholder = load_json(placeholder_summary)
plan = load_json(plan_summary)
api_key_env = os.environ["PISCES_QIANWEN_API_KEY_ENV"]
status = os.environ["PISCES_LOCAL_PREKEY_STATUS"]
next_commands = [
    f"edit {display(env_file)} and replace only {api_key_env}",
    "bash scripts/production-infrastructure-local-finalize.sh",
]
if os.environ["PISCES_LOCAL_PREKEY_KEY_STATUS"] == "configured":
    next_commands = ["bash scripts/production-infrastructure-local-finalize.sh"]

summary = {
    "summaryType": "pisces-production-infrastructure-local-prekey",
    "summaryVersion": 1,
    "generatedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "status": status,
    "message": os.environ["PISCES_LOCAL_PREKEY_MESSAGE"],
    "targetEnvironment": "local",
    "apiKeyEnv": api_key_env,
    "apiKeyStatus": os.environ["PISCES_LOCAL_PREKEY_KEY_STATUS"],
    "localEnv": {
        "file": display(env_file),
        "exists": env_file.is_file(),
        "gitIgnored": os.environ["PISCES_LOCAL_PREKEY_ENV_IGNORED"],
    },
    "stackEnv": {
        "file": display(stack_env_file),
        "exists": stack_env_file.is_file(),
    },
    "finalizerRefusal": {
        "summaryFile": display(placeholder_summary),
        "status": placeholder.get("status"),
        "apiKeyStatus": placeholder.get("apiKeyStatus"),
        "stepsAllNotRun": all(
            step.get("status") == "NOT_RUN" for step in placeholder.get("steps", [])
        ),
    },
    "finalizerDryRunPlan": {
        "summaryFile": display(plan_summary),
        "status": plan.get("status"),
        "apiKeyStatus": plan.get("apiKeyStatus"),
        "dryRun": plan.get("dryRun"),
        "captureFrontend": plan.get("captureFrontend"),
        "frontendEvidence": plan.get("frontendEvidence") or {},
        "runAiSmoke": plan.get("runAiSmoke"),
        "runCloseout": plan.get("runCloseout"),
        "runCompletionVerify": plan.get("runCompletionVerify"),
        "redisFault": plan.get("redisFault"),
        "commands": plan.get("commands", []),
        "stepsAllNotRun": all(step.get("status") == "NOT_RUN" for step in plan.get("steps", [])),
    },
    "nextCommands": next_commands,
}
output_file.write_text(json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
print(
    f"Production infrastructure local pre-key check written: {output_file} status={status}",
    file=os.sys.stderr,
)
PY
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
  PISCES_LOCAL_PREKEY_OUTPUT_FILE="${PISCES_LOCAL_PREKEY_OUTPUT_FILE:-target/pisces-production-infrastructure-local-prekey/summary.json}"
  PISCES_QIANWEN_API_KEY_ENV="${PISCES_QIANWEN_API_KEY_ENV:-TONGYI_API_KEY}"

  local output_file prekey_dir placeholder_summary plan_summary dry_run_env env_ignored current_key_status
  output_file="$(resolve_path "$PISCES_LOCAL_PREKEY_OUTPUT_FILE")"
  prekey_dir="$(dirname "$output_file")"
  placeholder_summary="$prekey_dir/finalizer-placeholder-summary.json"
  plan_summary="$prekey_dir/finalizer-dry-run-plan-summary.json"
  dry_run_env="$prekey_dir/prekey-dry-run.env"
  mkdir -p "$prekey_dir"

  export PISCES_REPO_ROOT
  export PISCES_QIANWEN_API_KEY_ENV
  export PISCES_LOCAL_PREKEY_OUTPUT_FILE_RESOLVED="$output_file"
  export PISCES_LOCAL_PREKEY_ENV_FILE="$PISCES_LOCAL_ENV_FILE"
  export PISCES_LOCAL_PREKEY_STACK_ENV_FILE="$PISCES_LOCAL_STACK_ENV_FILE"
  export PISCES_LOCAL_PREKEY_PLACEHOLDER_SUMMARY="$placeholder_summary"
  export PISCES_LOCAL_PREKEY_PLAN_SUMMARY="$plan_summary"

  if [[ ! -f "$PISCES_LOCAL_ENV_FILE" ]]; then
    log "Local env is missing; invoking finalizer once to bootstrap it"
    set +e
    PISCES_LOCAL_ENV_FILE="$PISCES_LOCAL_ENV_FILE" \
    PISCES_LOCAL_STACK_ENV_FILE="$PISCES_LOCAL_STACK_ENV_FILE" \
    PISCES_LOCAL_FINALIZE_OUTPUT_FILE="$placeholder_summary" \
    PISCES_QIANWEN_API_KEY_ENV="$PISCES_QIANWEN_API_KEY_ENV" \
    bash "$PISCES_REPO_ROOT/scripts/production-infrastructure-local-finalize.sh" >/dev/null
    local bootstrap_exit=$?
    set -e
    if [[ "$bootstrap_exit" -eq 0 || ! -f "$PISCES_LOCAL_ENV_FILE" ]]; then
      env_ignored="$(git_ignored_status "$PISCES_LOCAL_ENV_FILE")"
      write_failure_summary "$output_file" "Local env bootstrap did not stop at key replacement." \
        "missing" "$PISCES_LOCAL_ENV_FILE" "$env_ignored" "$placeholder_summary" "$plan_summary"
      return 1
    fi
  fi

  env_ignored="$(git_ignored_status "$PISCES_LOCAL_ENV_FILE")"
  if [[ "$env_ignored" != "ignored" ]]; then
    write_failure_summary "$output_file" "Local env file is not ignored by git." \
      "unknown" "$PISCES_LOCAL_ENV_FILE" "$env_ignored" "$placeholder_summary" "$plan_summary"
    return 1
  fi

  load_env_file "$PISCES_LOCAL_ENV_FILE"
  current_key_status="$(qianwen_key_status)"

  if [[ "$current_key_status" == "configured" ]]; then
    export PISCES_LOCAL_PREKEY_STATUS="READY_FOR_FINALIZER"
    export PISCES_LOCAL_PREKEY_MESSAGE="Qianwen key is already configured; run the local finalizer."
    export PISCES_LOCAL_PREKEY_KEY_STATUS="$current_key_status"
    export PISCES_LOCAL_PREKEY_ENV_IGNORED="$env_ignored"
    write_summary
    return
  fi

  set +e
  (
    unset PISCES_LOCAL_STACK_PROJECT_NAME PISCES_REDIS_DOCKER_CONTAINER PISCES_FAULT_CONFIRM PISCES_LOCAL_FINALIZE_REDIS_FAULT_MODE
    PISCES_LOCAL_ENV_FILE="$PISCES_LOCAL_ENV_FILE" \
    PISCES_LOCAL_STACK_ENV_FILE="$PISCES_LOCAL_STACK_ENV_FILE" \
    PISCES_LOCAL_FINALIZE_OUTPUT_FILE="$placeholder_summary" \
    PISCES_QIANWEN_API_KEY_ENV="$PISCES_QIANWEN_API_KEY_ENV" \
    bash "$PISCES_REPO_ROOT/scripts/production-infrastructure-local-finalize.sh" >/dev/null
  )
  local placeholder_exit=$?
  set -e
  if [[ "$placeholder_exit" -eq 0 ]]; then
    write_failure_summary "$output_file" "Finalizer did not refuse the missing or placeholder key." \
      "$current_key_status" "$PISCES_LOCAL_ENV_FILE" "$env_ignored" "$placeholder_summary" "$plan_summary"
    return 1
  fi

  python3 - "$PISCES_LOCAL_ENV_FILE" "$dry_run_env" "$PISCES_QIANWEN_API_KEY_ENV" <<'PY'
import re
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
api_key_env = sys.argv[3]
text = source.read_text(encoding="utf-8")
replacement = f'export {api_key_env}="prekey-dry-run-token"\n'
pattern = re.compile(rf"^\s*(?:export\s+)?{re.escape(api_key_env)}=.*$", re.MULTILINE)
if pattern.search(text):
    text = pattern.sub(replacement.rstrip("\n"), text)
else:
    text = text.rstrip() + "\n" + replacement
target.write_text(text, encoding="utf-8")
PY

  (
    unset PISCES_LOCAL_STACK_PROJECT_NAME PISCES_REDIS_DOCKER_CONTAINER PISCES_FAULT_CONFIRM PISCES_LOCAL_FINALIZE_REDIS_FAULT_MODE
    PISCES_RELEASE_ID="local-prekey-dry-run-$(date -u '+%Y%m%dT%H%M%SZ')" \
    PISCES_LOCAL_ENV_FILE="$dry_run_env" \
    PISCES_LOCAL_STACK_ENV_FILE="$PISCES_LOCAL_STACK_ENV_FILE" \
    PISCES_LOCAL_FINALIZE_OUTPUT_FILE="$plan_summary" \
    PISCES_LOCAL_FINALIZE_DRY_RUN=true \
    PISCES_QIANWEN_API_KEY_ENV="$PISCES_QIANWEN_API_KEY_ENV" \
    bash "$PISCES_REPO_ROOT/scripts/production-infrastructure-local-finalize.sh" >/dev/null
  )

  python3 - "$placeholder_summary" "$plan_summary" <<'PY'
import json
import sys
from pathlib import Path

placeholder = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
plan_file = Path(sys.argv[2])
plan_text = plan_file.read_text(encoding="utf-8")
plan = json.loads(plan_text)

if placeholder.get("status") != "NEEDS_QIANWEN_API_KEY":
    raise SystemExit("placeholder finalizer summary did not stop at NEEDS_QIANWEN_API_KEY")
if any(step.get("status") != "NOT_RUN" for step in placeholder.get("steps") or []):
    raise SystemExit("placeholder finalizer summary ran a step")
if plan.get("status") != "PLAN_ONLY":
    raise SystemExit("dry-run finalizer summary is not PLAN_ONLY")
if plan.get("apiKeyStatus") != "configured":
    raise SystemExit("dry-run finalizer did not see a configured temporary key")
if "prekey-dry-run-token" in plan_text:
    raise SystemExit("dry-run finalizer summary leaked the temporary key")
if plan.get("dryRun") is not True:
    raise SystemExit("dry-run finalizer summary did not mark dryRun=true")
if (
    plan.get("captureFrontend") is not True
    or plan.get("runAiSmoke") is not True
    or plan.get("runCloseout") is not True
    or plan.get("runCompletionVerify") is not True
):
    raise SystemExit("dry-run finalizer did not plan AI smoke, frontend capture, closeout, and completion verification")
frontend_evidence = plan.get("frontendEvidence") or {}
if frontend_evidence.get("requiredScreenshots") != ["09-variant-lab-tongyi-model-evidence.png"]:
    raise SystemExit("dry-run finalizer did not expose the required variant model evidence screenshot")
if any(step.get("status") != "NOT_RUN" for step in plan.get("steps") or []):
    raise SystemExit("dry-run finalizer executed a step")

commands = plan.get("commands") or []
expected_commands = [
    "bash scripts/production-infrastructure-local-dependency-stack.sh up",
    "bash scripts/production-infrastructure-local-mysql-schema-apply.sh",
    "bash scripts/production-infrastructure-local-dependency-check.sh",
    "bash scripts/production-infrastructure-local-service.sh start",
    "bash scripts/production-infrastructure-local-readiness.sh",
    "bash scripts/production-infrastructure-local-ai-smoke.sh",
    "bash scripts/production-infrastructure-local-frontend-evidence.sh",
]
missing = [command for command in expected_commands if command not in commands]
if missing:
    raise SystemExit(f"dry-run finalizer plan is missing commands: {missing}")
if not any("production-infrastructure-local-evidence-collect.sh" in command for command in commands):
    raise SystemExit("dry-run finalizer plan is missing evidence collection")
if "bash scripts/production-infrastructure-local-completion-verify.sh" not in commands:
    raise SystemExit("dry-run finalizer plan is missing completion verification")
PY

  export PISCES_LOCAL_PREKEY_STATUS="READY_FOR_API_KEY"
  export PISCES_LOCAL_PREKEY_MESSAGE="Local finalization flow is rehearsed; replace only the Qianwen API key and rerun finalizer."
  export PISCES_LOCAL_PREKEY_KEY_STATUS="$current_key_status"
  export PISCES_LOCAL_PREKEY_ENV_IGNORED="$env_ignored"
  write_summary
}

main "$@"
