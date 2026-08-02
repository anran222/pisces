#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  scripts/production-infrastructure-local-finalize.sh

Environment:
  PISCES_REPO_ROOT                         Repository root. Default: inferred from this script.
  PISCES_RELEASE_ID                        Local release ID. Default: local-<utc timestamp>.
  PISCES_LOCAL_ENV_FILE                    Local env file loaded before finalization. Default: config/pisces-local.env.
  PISCES_LOCAL_STACK_ENV_FILE              Local stack env file loaded before finalization. Default: config/pisces-local-stack.env.
  PISCES_LOCAL_FINALIZE_OUTPUT_FILE        JSON output. Default: target/pisces-production-infrastructure-local-finalize/summary.json.
  PISCES_LOCAL_FINALIZE_BOOTSTRAP_ENV      Create local env from template when missing. Default: true.
  PISCES_LOCAL_FINALIZE_DRY_RUN            Write plan only, do not start backend or collect evidence. Default: false.
  PISCES_LOCAL_FINALIZE_START_DEPENDENCY_STACK
                                               Start local MySQL/Redis/Zookeeper stack before backend. Default: true.
  PISCES_LOCAL_FINALIZE_APPLY_SCHEMA       Apply local MySQL base schema before backend. Default: true.
  PISCES_LOCAL_FINALIZE_CHECK_DEPENDENCIES Strict local dependency check before backend. Default: true.
  PISCES_LOCAL_FINALIZE_START_SERVICE      Start local backend before evidence collection. Default: true.
  PISCES_LOCAL_FINALIZE_RUN_READINESS      Run local readiness before evidence collection. Default: true.
  PISCES_LOCAL_FINALIZE_RUN_AI_SMOKE       Run local TongYi text generation smoke. Default: true.
  PISCES_LOCAL_FINALIZE_CAPTURE_FRONTEND   Run frontend audit and core screenshot capture. Default: true.
  PISCES_LOCAL_FINALIZE_RUN_CLOSEOUT       Run generated local closeout after evidence collection. Default: true.
  PISCES_LOCAL_FINALIZE_RUN_COMPLETION_VERIFY
                                               Verify finalizer, closeout, and evidence manifest after closeout. Default: true.
  PISCES_LOCAL_FINALIZE_REDIS_FAULT_MODE   auto | manual | docker-pause | docker-stop. Default: auto.
  PISCES_QIANWEN_API_KEY_ENV               Runtime API key env var. Default: TONGYI_API_KEY.

This script is the single local production finalization entrypoint. If
config/pisces-local.env is missing, it creates it from the template and stops
with NEEDS_QIANWEN_API_KEY. After replacing only TONGYI_API_KEY in
config/pisces-local.env, rerun this script; it prepares the local dependency
stack and schema, starts the local service, records readiness, captures
frontend evidence, verifies the local TongYi text model, collects real local
evidence, runs the local Redis fault drill, runs the generated local closeout
wrapper, and verifies the final local completion evidence.
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

is_generated_local_redis_container() {
  [[ -n "${PISCES_LOCAL_STACK_PROJECT_NAME:-}" ]] || return 1
  [[ -n "${PISCES_REDIS_DOCKER_CONTAINER:-}" ]] || return 1
  [[ "$PISCES_REDIS_DOCKER_CONTAINER" == "${PISCES_LOCAL_STACK_PROJECT_NAME}-redis-1" ]]
}

resolve_redis_fault_mode() {
  case "$PISCES_LOCAL_FINALIZE_REDIS_FAULT_MODE" in
    auto)
      if is_generated_local_redis_container; then
        printf 'docker-stop'
        return
      fi
      printf 'manual'
      ;;
    manual|docker-pause|docker-stop)
      printf '%s' "$PISCES_LOCAL_FINALIZE_REDIS_FAULT_MODE"
      ;;
    *)
      die "Unsupported PISCES_LOCAL_FINALIZE_REDIS_FAULT_MODE: $PISCES_LOCAL_FINALIZE_REDIS_FAULT_MODE"
      ;;
  esac
}

resolve_fault_confirm() {
  case "$PISCES_LOCAL_FINALIZE_REDIS_FAULT_MODE_EFFECTIVE" in
    docker-pause|docker-stop)
      if is_generated_local_redis_container; then
        printf 'true'
        return
      fi
      printf '%s' "${PISCES_FAULT_CONFIRM:-false}"
      ;;
    *)
      printf '%s' "${PISCES_FAULT_CONFIRM:-false}"
      ;;
  esac
}

write_summary() {
  local status="$1"
  local exit_code="$2"
  local message="$3"
  local stack_exit="${4:-not_run}"
  local schema_exit="${5:-not_run}"
  local dependency_exit="${6:-not_run}"
  local service_exit="${7:-not_run}"
  local readiness_exit="${8:-not_run}"
  local frontend_exit="${9:-not_run}"
  local collect_exit="${10:-not_run}"
  local verify_exit="${11:-not_run}"
  local ai_smoke_exit="${PISCES_LOCAL_FINALIZE_AI_SMOKE_EXIT:-not_run}"

  export PISCES_LOCAL_FINALIZE_SUMMARY_STATUS="$status"
  export PISCES_LOCAL_FINALIZE_SUMMARY_EXIT_CODE="$exit_code"
  export PISCES_LOCAL_FINALIZE_SUMMARY_MESSAGE="$message"
  export PISCES_LOCAL_FINALIZE_STACK_EXIT="$stack_exit"
  export PISCES_LOCAL_FINALIZE_SCHEMA_EXIT="$schema_exit"
  export PISCES_LOCAL_FINALIZE_DEPENDENCY_EXIT="$dependency_exit"
  export PISCES_LOCAL_FINALIZE_SERVICE_EXIT="$service_exit"
  export PISCES_LOCAL_FINALIZE_READINESS_EXIT="$readiness_exit"
  export PISCES_LOCAL_FINALIZE_AI_SMOKE_EXIT="$ai_smoke_exit"
  export PISCES_LOCAL_FINALIZE_FRONTEND_EXIT="$frontend_exit"
  export PISCES_LOCAL_FINALIZE_COLLECT_EXIT="$collect_exit"
  export PISCES_LOCAL_FINALIZE_VERIFY_EXIT="$verify_exit"

  python3 <<'PY'
import json
import os
from datetime import datetime, timezone
from pathlib import Path

repo_root = Path(os.environ["PISCES_REPO_ROOT"]).resolve()
output_file = Path(os.environ["PISCES_LOCAL_FINALIZE_OUTPUT_FILE_RESOLVED"])
finalize_dir = Path(os.environ["PISCES_LOCAL_FINALIZE_DIR"])


def display(path_value):
    path = Path(path_value)
    try:
        return str(path.resolve().relative_to(repo_root))
    except ValueError:
        return str(path)


def step(name, exit_code, summary_file):
    item = {
        "name": name,
        "exitCode": None if exit_code == "not_run" else int(exit_code),
        "status": "NOT_RUN" if exit_code == "not_run" else ("PASS" if int(exit_code) == 0 else "FAIL"),
    }
    if summary_file:
        item["summaryFile"] = display(summary_file)
    return item


status = os.environ["PISCES_LOCAL_FINALIZE_SUMMARY_STATUS"]
start_dependency_stack = os.environ["PISCES_LOCAL_FINALIZE_START_DEPENDENCY_STACK"].lower() in {
    "true", "1", "yes", "y"
}
apply_schema = os.environ["PISCES_LOCAL_FINALIZE_APPLY_SCHEMA"].lower() in {"true", "1", "yes", "y"}
check_dependencies = os.environ["PISCES_LOCAL_FINALIZE_CHECK_DEPENDENCIES"].lower() in {
    "true", "1", "yes", "y"
}
start_service = os.environ["PISCES_LOCAL_FINALIZE_START_SERVICE"].lower() in {"true", "1", "yes", "y"}
run_readiness = os.environ["PISCES_LOCAL_FINALIZE_RUN_READINESS"].lower() in {"true", "1", "yes", "y"}
run_ai_smoke = os.environ["PISCES_LOCAL_FINALIZE_RUN_AI_SMOKE"].lower() in {"true", "1", "yes", "y"}
capture_frontend = os.environ["PISCES_LOCAL_FINALIZE_CAPTURE_FRONTEND"].lower() in {
    "true", "1", "yes", "y"
}
run_completion_verify = os.environ["PISCES_LOCAL_FINALIZE_RUN_COMPLETION_VERIFY"].lower() in {
    "true", "1", "yes", "y"
}
frontend_required_screenshots = [
    item.strip()
    for item in os.environ["PISCES_LOCAL_FRONTEND_REQUIRED_SCREENSHOTS"].replace("\n", ",").split(",")
    if item.strip()
]
collector_command = (
    "PISCES_LOCAL_COLLECT_REDIS_FAULT_MODE="
    f"{os.environ['PISCES_LOCAL_FINALIZE_REDIS_FAULT_MODE_EFFECTIVE']} "
    "bash scripts/production-infrastructure-local-evidence-collect.sh"
)
commands = []
if start_dependency_stack:
    commands.append("bash scripts/production-infrastructure-local-dependency-stack.sh up")
if apply_schema:
    commands.append("bash scripts/production-infrastructure-local-mysql-schema-apply.sh")
if check_dependencies:
    commands.append("bash scripts/production-infrastructure-local-dependency-check.sh")
if start_service:
    commands.append("bash scripts/production-infrastructure-local-service.sh start")
if run_readiness:
    commands.append("bash scripts/production-infrastructure-local-readiness.sh")
if run_ai_smoke:
    commands.append("bash scripts/production-infrastructure-local-ai-smoke.sh")
if capture_frontend:
    commands.append("bash scripts/production-infrastructure-local-frontend-evidence.sh")
commands.append(collector_command)
if run_completion_verify:
    commands.append("bash scripts/production-infrastructure-local-completion-verify.sh")


def read_json(path):
    json_path = Path(path)
    if not json_path.is_file():
        return {}
    try:
        data = json.loads(json_path.read_text(encoding="utf-8"))
    except Exception:
        return {}
    return data if isinstance(data, dict) else {}


ai_smoke_summary = read_json(os.environ["PISCES_LOCAL_FINALIZE_AI_SMOKE_SUMMARY_FILE"])

next_commands = []
if status == "NEEDS_QIANWEN_API_KEY":
    next_commands.extend([
        f"edit {display(os.environ['PISCES_LOCAL_ENV_FILE_RESOLVED'])} and replace only {os.environ['PISCES_QIANWEN_API_KEY_ENV']}",
        "bash scripts/production-infrastructure-local-finalize.sh",
    ])
elif status == "PLAN_ONLY":
    next_commands.extend(commands)
elif status in {
    "DEPENDENCY_STACK_FAILED",
    "MYSQL_SCHEMA_FAILED",
    "DEPENDENCY_CHECK_FAILED",
    "SERVICE_FAILED",
    "READINESS_FAILED",
    "AI_SMOKE_FAILED",
    "FRONTEND_EVIDENCE_FAILED",
    "EVIDENCE_COLLECT_FAILED",
    "COMPLETION_VERIFY_FAILED",
}:
    next_commands.extend([
        f"inspect {display(os.environ['PISCES_LOCAL_FINALIZE_OUTPUT_FILE_RESOLVED'])}",
        "bash scripts/production-infrastructure-local-dependency-check.sh",
        "bash scripts/production-infrastructure-local-service.sh logs",
        "bash scripts/production-infrastructure-local-readiness.sh",
        "bash scripts/production-infrastructure-local-completion-verify.sh",
    ])
elif status in {"PASS", "EVIDENCE_COLLECTED"}:
    if status == "PASS":
        next_commands.append("bash scripts/production-infrastructure-local-completion-verify.sh")
    else:
        next_commands.append("bash scripts/production-infrastructure-local-readiness.sh")

summary = {
    "summaryType": "pisces-production-infrastructure-local-finalize",
    "summaryVersion": 1,
    "generatedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "status": status,
    "exitCode": int(os.environ["PISCES_LOCAL_FINALIZE_SUMMARY_EXIT_CODE"]),
    "message": os.environ["PISCES_LOCAL_FINALIZE_SUMMARY_MESSAGE"],
    "targetEnvironment": "local",
    "releaseId": os.environ["PISCES_RELEASE_ID"],
    "apiKeyEnv": os.environ["PISCES_QIANWEN_API_KEY_ENV"],
    "apiKeyStatus": os.environ["PISCES_LOCAL_FINALIZE_QIANWEN_KEY_STATUS"],
    "dryRun": os.environ["PISCES_LOCAL_FINALIZE_DRY_RUN"].lower() in {"true", "1", "yes", "y"},
    "startDependencyStack": start_dependency_stack,
    "applySchema": apply_schema,
    "checkDependencies": check_dependencies,
    "startService": start_service,
    "runReadiness": run_readiness,
    "captureFrontend": capture_frontend,
    "runAiSmoke": run_ai_smoke,
    "frontendEvidence": {
        "captureEnabled": capture_frontend,
        "summaryFile": display(os.environ["PISCES_LOCAL_FINALIZE_FRONTEND_SUMMARY_FILE"]),
        "requiredScreenshots": frontend_required_screenshots,
    },
    "tongyiModelEnv": os.environ["PISCES_TONGYI_MODEL_ENV"],
    "tongyiModel": os.environ["PISCES_LOCAL_FINALIZE_TONGYI_MODEL"],
    "tongyiApiModeEnv": os.environ["PISCES_TONGYI_API_MODE_ENV"],
    "tongyiApiMode": os.environ["PISCES_LOCAL_FINALIZE_TONGYI_API_MODE"],
    "tongyiFallbackModelEnv": os.environ["PISCES_TONGYI_FALLBACK_MODEL_ENV"],
    "tongyiFallbackModel": os.environ["PISCES_LOCAL_FINALIZE_TONGYI_FALLBACK_MODEL"],
    "tongyiFallbackApiModeEnv": os.environ["PISCES_TONGYI_FALLBACK_API_MODE_ENV"],
    "tongyiFallbackApiMode": os.environ["PISCES_LOCAL_FINALIZE_TONGYI_FALLBACK_API_MODE"],
    "modelStrategy": "production-dashscope-qwen3.7-max-with-token-plan-preview-opt-in",
    "tongyiSelectedModel": ai_smoke_summary.get("tongyiSelectedModel"),
    "tongyiSelectedApiMode": ai_smoke_summary.get("tongyiSelectedApiMode"),
    "tongyiFallbackUsed": ai_smoke_summary.get("tongyiFallbackUsed"),
    "tongyiAttemptedModels": ai_smoke_summary.get("tongyiAttemptedModels") or [],
    "runCloseout": os.environ["PISCES_LOCAL_FINALIZE_RUN_CLOSEOUT"].lower() in {"true", "1", "yes", "y"},
    "runCompletionVerify": run_completion_verify,
    "bootstrapEnv": os.environ["PISCES_LOCAL_FINALIZE_BOOTSTRAP_ENV"].lower() in {"true", "1", "yes", "y"},
    "envCreatedByFinalize": os.environ["PISCES_LOCAL_FINALIZE_ENV_CREATED_BY_FINALIZE"].lower()
    in {"true", "1", "yes", "y"},
    "redisFault": {
        "requestedMode": os.environ["PISCES_LOCAL_FINALIZE_REDIS_FAULT_MODE"],
        "effectiveMode": os.environ["PISCES_LOCAL_FINALIZE_REDIS_FAULT_MODE_EFFECTIVE"],
        "dockerContainer": os.environ.get("PISCES_REDIS_DOCKER_CONTAINER") or None,
        "faultConfirm": os.environ["PISCES_LOCAL_FINALIZE_FAULT_CONFIRM_EFFECTIVE"].lower()
        in {"true", "1", "yes", "y"},
        "autoConfirmedLocalStackContainer": os.environ[
            "PISCES_LOCAL_FINALIZE_AUTO_CONFIRMED_REDIS_CONTAINER"
        ].lower() in {"true", "1", "yes", "y"},
    },
    "finalizeDir": display(finalize_dir),
    "envFile": display(os.environ["PISCES_LOCAL_ENV_FILE_RESOLVED"]),
    "stackEnvFile": display(os.environ["PISCES_LOCAL_STACK_ENV_FILE_RESOLVED"]),
    "stackEnvFilePresent": Path(os.environ["PISCES_LOCAL_STACK_ENV_FILE_RESOLVED"]).is_file(),
    "steps": [
        step("local dependency stack up", os.environ["PISCES_LOCAL_FINALIZE_STACK_EXIT"], os.environ["PISCES_LOCAL_FINALIZE_STACK_SUMMARY_FILE"]),
        step("local MySQL schema apply", os.environ["PISCES_LOCAL_FINALIZE_SCHEMA_EXIT"], os.environ["PISCES_LOCAL_FINALIZE_SCHEMA_SUMMARY_FILE"]),
        step("local dependency check", os.environ["PISCES_LOCAL_FINALIZE_DEPENDENCY_EXIT"], os.environ["PISCES_LOCAL_FINALIZE_DEPENDENCY_SUMMARY_FILE"]),
        step("local service start", os.environ["PISCES_LOCAL_FINALIZE_SERVICE_EXIT"], os.environ["PISCES_LOCAL_FINALIZE_SERVICE_SUMMARY_FILE"]),
        step("local readiness", os.environ["PISCES_LOCAL_FINALIZE_READINESS_EXIT"], os.environ["PISCES_LOCAL_FINALIZE_READINESS_SUMMARY_FILE"]),
        step("local AI smoke", os.environ["PISCES_LOCAL_FINALIZE_AI_SMOKE_EXIT"], os.environ["PISCES_LOCAL_FINALIZE_AI_SMOKE_SUMMARY_FILE"]),
        step("local frontend evidence", os.environ["PISCES_LOCAL_FINALIZE_FRONTEND_EXIT"], os.environ["PISCES_LOCAL_FINALIZE_FRONTEND_SUMMARY_FILE"]),
        step("local evidence collect", os.environ["PISCES_LOCAL_FINALIZE_COLLECT_EXIT"], os.environ["PISCES_LOCAL_FINALIZE_COLLECT_SUMMARY_FILE"]),
        step("local completion verify", os.environ["PISCES_LOCAL_FINALIZE_VERIFY_EXIT"], os.environ["PISCES_LOCAL_FINALIZE_COMPLETION_VERIFY_SUMMARY_FILE"]),
    ],
    "outputs": {
        "dependencyStackSummary": display(os.environ["PISCES_LOCAL_FINALIZE_STACK_SUMMARY_FILE"]),
        "schemaSummary": display(os.environ["PISCES_LOCAL_FINALIZE_SCHEMA_SUMMARY_FILE"]),
        "dependencySummary": display(os.environ["PISCES_LOCAL_FINALIZE_DEPENDENCY_SUMMARY_FILE"]),
        "serviceSummary": display(os.environ["PISCES_LOCAL_FINALIZE_SERVICE_SUMMARY_FILE"]),
        "readinessSummary": display(os.environ["PISCES_LOCAL_FINALIZE_READINESS_SUMMARY_FILE"]),
        "aiSmokeSummary": display(os.environ["PISCES_LOCAL_FINALIZE_AI_SMOKE_SUMMARY_FILE"]),
        "frontendSummary": display(os.environ["PISCES_LOCAL_FINALIZE_FRONTEND_SUMMARY_FILE"]),
        "collectionSummary": display(os.environ["PISCES_LOCAL_FINALIZE_COLLECT_SUMMARY_FILE"]),
        "completionVerifySummary": display(os.environ["PISCES_LOCAL_FINALIZE_COMPLETION_VERIFY_SUMMARY_FILE"]),
        "evidenceWorkspace": display(os.environ["PISCES_LOCAL_FINALIZE_EVIDENCE_WORKSPACE"]),
        "envBootstrapSummary": display(os.environ["PISCES_LOCAL_FINALIZE_BOOTSTRAP_SUMMARY_FILE"]),
    },
    "commands": commands,
    "nextCommands": next_commands,
}
output_file.write_text(json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
print(
    f"Production infrastructure local finalize written: {output_file} status={status}",
    file=os.sys.stderr,
)
PY
}

main() {
  if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
    usage
    exit 0
  fi

  require_command python3
  require_command bash

  local env_redis_docker_container env_fault_confirm env_stack_project env_redis_fault_mode
  env_redis_docker_container="${PISCES_REDIS_DOCKER_CONTAINER-}"
  env_fault_confirm="${PISCES_FAULT_CONFIRM-}"
  env_stack_project="${PISCES_LOCAL_STACK_PROJECT_NAME-}"
  env_redis_fault_mode="${PISCES_LOCAL_FINALIZE_REDIS_FAULT_MODE-}"

  PISCES_REPO_ROOT="$(resolve_repo_root)"
  PISCES_RELEASE_ID="${PISCES_RELEASE_ID:-local-$(date -u '+%Y%m%dT%H%M%SZ')}"
  PISCES_LOCAL_ENV_FILE="$(resolve_path "${PISCES_LOCAL_ENV_FILE:-config/pisces-local.env}")"
  PISCES_LOCAL_STACK_ENV_FILE="$(resolve_path "${PISCES_LOCAL_STACK_ENV_FILE:-config/pisces-local-stack.env}")"
  PISCES_LOCAL_FINALIZE_OUTPUT_FILE="${PISCES_LOCAL_FINALIZE_OUTPUT_FILE:-target/pisces-production-infrastructure-local-finalize/summary.json}"
  PISCES_LOCAL_FINALIZE_BOOTSTRAP_ENV="${PISCES_LOCAL_FINALIZE_BOOTSTRAP_ENV:-true}"
  PISCES_QIANWEN_API_KEY_ENV="${PISCES_QIANWEN_API_KEY_ENV:-TONGYI_API_KEY}"

  local output_file finalize_dir stack_summary schema_summary dependency_summary ai_smoke_summary frontend_summary
  local service_summary readiness_summary collect_summary completion_verify_summary evidence_workspace pid_file log_file
  local bootstrap_summary env_created_by_finalize
  output_file="$(resolve_path "$PISCES_LOCAL_FINALIZE_OUTPUT_FILE")"
  finalize_dir="$(dirname "$output_file")"
  bootstrap_summary="$finalize_dir/bootstrap-summary.json"
  stack_summary="$finalize_dir/dependency-stack-summary.json"
  schema_summary="$finalize_dir/mysql-schema-summary.json"
  dependency_summary="$finalize_dir/dependency-summary.json"
  ai_smoke_summary="$finalize_dir/ai-smoke-summary.json"
  frontend_summary="$finalize_dir/frontend-summary.json"
  service_summary="$finalize_dir/service-summary.json"
  readiness_summary="$finalize_dir/readiness-summary.json"
  collect_summary="$finalize_dir/collection-summary.json"
  completion_verify_summary="$finalize_dir/completion-verify-summary.json"
  evidence_workspace="$finalize_dir/evidence-workspace"
  pid_file="$finalize_dir/backend.pid"
  log_file="$finalize_dir/backend.log"
  env_created_by_finalize=false
  mkdir -p "$finalize_dir"

  if [[ ! -f "$PISCES_LOCAL_ENV_FILE" ]] && is_true "$PISCES_LOCAL_FINALIZE_BOOTSTRAP_ENV"; then
    log "Creating local env file via bootstrap"
    set +e
    env \
      PISCES_REPO_ROOT="$PISCES_REPO_ROOT" \
      PISCES_LOCAL_ENV_FILE="$PISCES_LOCAL_ENV_FILE" \
      PISCES_LOCAL_BOOTSTRAP_OUTPUT_FILE="$bootstrap_summary" \
      PISCES_LOCAL_BOOTSTRAP_CREATE_ENV=true \
      PISCES_QIANWEN_API_KEY_ENV="$PISCES_QIANWEN_API_KEY_ENV" \
      bash "$PISCES_REPO_ROOT/scripts/production-infrastructure-local-bootstrap.sh"
    bootstrap_exit=$?
    set -e
    if [[ "$bootstrap_exit" -ne 0 ]]; then
      die "Local env bootstrap failed; inspect $bootstrap_summary"
    fi
    if [[ -f "$PISCES_LOCAL_ENV_FILE" ]]; then
      env_created_by_finalize=true
    fi
  fi

  load_env_file "$PISCES_LOCAL_ENV_FILE"
  load_env_file "$PISCES_LOCAL_STACK_ENV_FILE"
  [[ -n "$env_redis_docker_container" ]] && PISCES_REDIS_DOCKER_CONTAINER="$env_redis_docker_container"
  [[ -n "$env_fault_confirm" ]] && PISCES_FAULT_CONFIRM="$env_fault_confirm"
  [[ -n "$env_stack_project" ]] && PISCES_LOCAL_STACK_PROJECT_NAME="$env_stack_project"
  [[ -n "$env_redis_fault_mode" ]] && PISCES_LOCAL_FINALIZE_REDIS_FAULT_MODE="$env_redis_fault_mode"

  PISCES_LOCAL_FINALIZE_DRY_RUN="${PISCES_LOCAL_FINALIZE_DRY_RUN:-false}"
  PISCES_LOCAL_FINALIZE_START_DEPENDENCY_STACK="${PISCES_LOCAL_FINALIZE_START_DEPENDENCY_STACK:-true}"
  PISCES_LOCAL_FINALIZE_APPLY_SCHEMA="${PISCES_LOCAL_FINALIZE_APPLY_SCHEMA:-true}"
  PISCES_LOCAL_FINALIZE_CHECK_DEPENDENCIES="${PISCES_LOCAL_FINALIZE_CHECK_DEPENDENCIES:-true}"
  PISCES_LOCAL_FINALIZE_START_SERVICE="${PISCES_LOCAL_FINALIZE_START_SERVICE:-true}"
  PISCES_LOCAL_FINALIZE_RUN_READINESS="${PISCES_LOCAL_FINALIZE_RUN_READINESS:-true}"
  PISCES_LOCAL_FINALIZE_RUN_AI_SMOKE="${PISCES_LOCAL_FINALIZE_RUN_AI_SMOKE:-true}"
  PISCES_LOCAL_FINALIZE_CAPTURE_FRONTEND="${PISCES_LOCAL_FINALIZE_CAPTURE_FRONTEND:-true}"
  PISCES_LOCAL_FINALIZE_RUN_CLOSEOUT="${PISCES_LOCAL_FINALIZE_RUN_CLOSEOUT:-true}"
  PISCES_LOCAL_FINALIZE_RUN_COMPLETION_VERIFY="${PISCES_LOCAL_FINALIZE_RUN_COMPLETION_VERIFY:-true}"
  PISCES_LOCAL_FINALIZE_REDIS_FAULT_MODE="${PISCES_LOCAL_FINALIZE_REDIS_FAULT_MODE:-auto}"
  PISCES_LOCAL_FRONTEND_REQUIRED_SCREENSHOTS="${PISCES_LOCAL_FRONTEND_REQUIRED_SCREENSHOTS:-09-variant-lab-tongyi-model-evidence.png}"
  PISCES_TONGYI_MODEL_ENV="${PISCES_TONGYI_MODEL_ENV:-TONGYI_MODEL}"
  PISCES_TONGYI_API_MODE_ENV="${PISCES_TONGYI_API_MODE_ENV:-TONGYI_API_MODE}"
  PISCES_TONGYI_FALLBACK_MODEL_ENV="${PISCES_TONGYI_FALLBACK_MODEL_ENV:-TONGYI_FALLBACK_MODEL}"
  PISCES_TONGYI_FALLBACK_API_MODE_ENV="${PISCES_TONGYI_FALLBACK_API_MODE_ENV:-TONGYI_FALLBACK_API_MODE}"

  PISCES_LOCAL_FINALIZE_QIANWEN_KEY_STATUS="$(qianwen_key_status)"
  PISCES_LOCAL_FINALIZE_TONGYI_MODEL="${!PISCES_TONGYI_MODEL_ENV:-qwen3.7-max}"
  PISCES_LOCAL_FINALIZE_TONGYI_API_MODE="${!PISCES_TONGYI_API_MODE_ENV:-dashscope}"
  PISCES_LOCAL_FINALIZE_TONGYI_FALLBACK_MODEL="${!PISCES_TONGYI_FALLBACK_MODEL_ENV:-qwen3.7-max}"
  PISCES_LOCAL_FINALIZE_TONGYI_FALLBACK_API_MODE="${!PISCES_TONGYI_FALLBACK_API_MODE_ENV:-dashscope}"
  PISCES_LOCAL_FINALIZE_REDIS_FAULT_MODE_EFFECTIVE="$(resolve_redis_fault_mode)"
  PISCES_LOCAL_FINALIZE_FAULT_CONFIRM_EFFECTIVE="$(resolve_fault_confirm)"
  if is_generated_local_redis_container \
    && [[ "$PISCES_LOCAL_FINALIZE_REDIS_FAULT_MODE_EFFECTIVE" == docker-* ]]; then
    PISCES_LOCAL_FINALIZE_AUTO_CONFIRMED_REDIS_CONTAINER=true
  else
    PISCES_LOCAL_FINALIZE_AUTO_CONFIRMED_REDIS_CONTAINER=false
  fi

  export PISCES_REPO_ROOT
  export PISCES_RELEASE_ID
  export PISCES_LOCAL_ENV_FILE_RESOLVED="$PISCES_LOCAL_ENV_FILE"
  export PISCES_LOCAL_STACK_ENV_FILE_RESOLVED="$PISCES_LOCAL_STACK_ENV_FILE"
  export PISCES_LOCAL_FINALIZE_OUTPUT_FILE_RESOLVED="$output_file"
  export PISCES_LOCAL_FINALIZE_DIR="$finalize_dir"
  export PISCES_LOCAL_FINALIZE_STACK_SUMMARY_FILE="$stack_summary"
  export PISCES_LOCAL_FINALIZE_SCHEMA_SUMMARY_FILE="$schema_summary"
  export PISCES_LOCAL_FINALIZE_DEPENDENCY_SUMMARY_FILE="$dependency_summary"
  export PISCES_LOCAL_FINALIZE_AI_SMOKE_SUMMARY_FILE="$ai_smoke_summary"
  export PISCES_LOCAL_FINALIZE_FRONTEND_SUMMARY_FILE="$frontend_summary"
  export PISCES_LOCAL_FINALIZE_SERVICE_SUMMARY_FILE="$service_summary"
  export PISCES_LOCAL_FINALIZE_READINESS_SUMMARY_FILE="$readiness_summary"
  export PISCES_LOCAL_FINALIZE_COLLECT_SUMMARY_FILE="$collect_summary"
  export PISCES_LOCAL_FINALIZE_COMPLETION_VERIFY_SUMMARY_FILE="$completion_verify_summary"
  export PISCES_LOCAL_FINALIZE_EVIDENCE_WORKSPACE="$evidence_workspace"
  export PISCES_LOCAL_FINALIZE_BOOTSTRAP_SUMMARY_FILE="$bootstrap_summary"
  export PISCES_LOCAL_FINALIZE_QIANWEN_KEY_STATUS
  export PISCES_LOCAL_FINALIZE_BOOTSTRAP_ENV
  export PISCES_LOCAL_FINALIZE_ENV_CREATED_BY_FINALIZE="$env_created_by_finalize"
  export PISCES_LOCAL_FINALIZE_DRY_RUN
  export PISCES_LOCAL_FINALIZE_START_DEPENDENCY_STACK
  export PISCES_LOCAL_FINALIZE_APPLY_SCHEMA
  export PISCES_LOCAL_FINALIZE_CHECK_DEPENDENCIES
  export PISCES_LOCAL_FINALIZE_START_SERVICE
  export PISCES_LOCAL_FINALIZE_RUN_READINESS
  export PISCES_LOCAL_FINALIZE_RUN_AI_SMOKE
  export PISCES_LOCAL_FINALIZE_CAPTURE_FRONTEND
  export PISCES_LOCAL_FINALIZE_RUN_CLOSEOUT
  export PISCES_LOCAL_FINALIZE_RUN_COMPLETION_VERIFY
  export PISCES_LOCAL_FRONTEND_REQUIRED_SCREENSHOTS
  export PISCES_LOCAL_FINALIZE_REDIS_FAULT_MODE
  export PISCES_LOCAL_FINALIZE_REDIS_FAULT_MODE_EFFECTIVE
  export PISCES_LOCAL_FINALIZE_FAULT_CONFIRM_EFFECTIVE
  export PISCES_LOCAL_FINALIZE_AUTO_CONFIRMED_REDIS_CONTAINER
  export PISCES_LOCAL_FINALIZE_AI_SMOKE_EXIT="not_run"
  export PISCES_LOCAL_FINALIZE_TONGYI_MODEL
  export PISCES_LOCAL_FINALIZE_TONGYI_API_MODE
  export PISCES_LOCAL_FINALIZE_TONGYI_FALLBACK_MODEL
  export PISCES_LOCAL_FINALIZE_TONGYI_FALLBACK_API_MODE
  export PISCES_TONGYI_MODEL_ENV
  export PISCES_TONGYI_API_MODE_ENV
  export PISCES_TONGYI_FALLBACK_MODEL_ENV
  export PISCES_TONGYI_FALLBACK_API_MODE_ENV
  export PISCES_QIANWEN_API_KEY_ENV

  if [[ "$PISCES_LOCAL_FINALIZE_QIANWEN_KEY_STATUS" != "configured" ]]; then
    write_summary "NEEDS_QIANWEN_API_KEY" 1 \
      "Refusing to finalize local production infrastructure with missing or placeholder Qianwen API key."
    return 1
  fi

  if is_true "$PISCES_LOCAL_FINALIZE_DRY_RUN"; then
    write_summary "PLAN_ONLY" 0 "Dry run only; no local service or evidence command was executed."
    return
  fi

  local stack_exit="not_run"
  if is_true "$PISCES_LOCAL_FINALIZE_START_DEPENDENCY_STACK"; then
    log "Ensuring local dependency stack"
    set +e
    env \
      PISCES_LOCAL_ENV_FILE="$PISCES_LOCAL_ENV_FILE" \
      PISCES_LOCAL_STACK_ENV_FILE="$PISCES_LOCAL_STACK_ENV_FILE" \
      PISCES_LOCAL_STACK_OUTPUT_FILE="$stack_summary" \
      bash "$PISCES_REPO_ROOT/scripts/production-infrastructure-local-dependency-stack.sh" up
    stack_exit=$?
    set -e
    if [[ "$stack_exit" -ne 0 ]]; then
      write_summary "DEPENDENCY_STACK_FAILED" "$stack_exit" "Local dependency stack start failed." \
        "$stack_exit"
      return "$stack_exit"
    fi

    load_env_file "$PISCES_LOCAL_STACK_ENV_FILE"
    [[ -n "$env_redis_docker_container" ]] && PISCES_REDIS_DOCKER_CONTAINER="$env_redis_docker_container"
    [[ -n "$env_fault_confirm" ]] && PISCES_FAULT_CONFIRM="$env_fault_confirm"
    [[ -n "$env_stack_project" ]] && PISCES_LOCAL_STACK_PROJECT_NAME="$env_stack_project"
    PISCES_LOCAL_FINALIZE_REDIS_FAULT_MODE_EFFECTIVE="$(resolve_redis_fault_mode)"
    PISCES_LOCAL_FINALIZE_FAULT_CONFIRM_EFFECTIVE="$(resolve_fault_confirm)"
    if is_generated_local_redis_container \
      && [[ "$PISCES_LOCAL_FINALIZE_REDIS_FAULT_MODE_EFFECTIVE" == docker-* ]]; then
      PISCES_LOCAL_FINALIZE_AUTO_CONFIRMED_REDIS_CONTAINER=true
    else
      PISCES_LOCAL_FINALIZE_AUTO_CONFIRMED_REDIS_CONTAINER=false
    fi
    export PISCES_LOCAL_FINALIZE_REDIS_FAULT_MODE_EFFECTIVE
    export PISCES_LOCAL_FINALIZE_FAULT_CONFIRM_EFFECTIVE
    export PISCES_LOCAL_FINALIZE_AUTO_CONFIRMED_REDIS_CONTAINER
  fi

  local schema_exit="not_run"
  if is_true "$PISCES_LOCAL_FINALIZE_APPLY_SCHEMA"; then
    log "Applying local MySQL schema"
    set +e
    env \
      PISCES_LOCAL_ENV_FILE="$PISCES_LOCAL_ENV_FILE" \
      PISCES_LOCAL_STACK_ENV_FILE="$PISCES_LOCAL_STACK_ENV_FILE" \
      PISCES_LOCAL_MYSQL_SCHEMA_OUTPUT_FILE="$schema_summary" \
      bash "$PISCES_REPO_ROOT/scripts/production-infrastructure-local-mysql-schema-apply.sh"
    schema_exit=$?
    set -e
    if [[ "$schema_exit" -ne 0 ]]; then
      write_summary "MYSQL_SCHEMA_FAILED" "$schema_exit" "Local MySQL schema apply failed." \
        "$stack_exit" "$schema_exit"
      return "$schema_exit"
    fi
  fi

  local dependency_exit="not_run"
  if is_true "$PISCES_LOCAL_FINALIZE_CHECK_DEPENDENCIES"; then
    log "Checking local runtime dependencies"
    set +e
    env \
      PISCES_LOCAL_ENV_FILE="$PISCES_LOCAL_ENV_FILE" \
      PISCES_LOCAL_STACK_ENV_FILE="$PISCES_LOCAL_STACK_ENV_FILE" \
      PISCES_LOCAL_DEPENDENCY_OUTPUT_FILE="$dependency_summary" \
      PISCES_LOCAL_DEPENDENCY_STRICT=true \
      bash "$PISCES_REPO_ROOT/scripts/production-infrastructure-local-dependency-check.sh"
    dependency_exit=$?
    set -e
    if [[ "$dependency_exit" -ne 0 ]]; then
      write_summary "DEPENDENCY_CHECK_FAILED" "$dependency_exit" "Local dependency check failed." \
        "$stack_exit" "$schema_exit" "$dependency_exit"
      return "$dependency_exit"
    fi
  fi

  local service_exit="not_run"
  if is_true "$PISCES_LOCAL_FINALIZE_START_SERVICE"; then
    log "Starting local service"
    set +e
    env \
      PISCES_LOCAL_ENV_FILE="$PISCES_LOCAL_ENV_FILE" \
      PISCES_LOCAL_STACK_ENV_FILE="$PISCES_LOCAL_STACK_ENV_FILE" \
      PISCES_LOCAL_SERVICE_OUTPUT_FILE="$service_summary" \
      PISCES_LOCAL_SERVICE_PID_FILE="$pid_file" \
      PISCES_LOCAL_SERVICE_LOG_FILE="$log_file" \
      bash "$PISCES_REPO_ROOT/scripts/production-infrastructure-local-service.sh" start
    service_exit=$?
    set -e
    if [[ "$service_exit" -ne 0 ]]; then
      write_summary "SERVICE_FAILED" "$service_exit" "Local service start failed." \
        "$stack_exit" "$schema_exit" "$dependency_exit" "$service_exit"
      return "$service_exit"
    fi
  fi

  local readiness_exit="not_run"
  if is_true "$PISCES_LOCAL_FINALIZE_RUN_READINESS"; then
    log "Running local readiness preflight"
    set +e
    env \
      PISCES_LOCAL_ENV_FILE="$PISCES_LOCAL_ENV_FILE" \
      PISCES_LOCAL_STACK_ENV_FILE="$PISCES_LOCAL_STACK_ENV_FILE" \
      PISCES_LOCAL_READINESS_OUTPUT_FILE="$readiness_summary" \
      bash "$PISCES_REPO_ROOT/scripts/production-infrastructure-local-readiness.sh"
    readiness_exit=$?
    set -e
    if [[ "$readiness_exit" -ne 0 ]]; then
      write_summary "READINESS_FAILED" "$readiness_exit" "Local readiness preflight failed." \
        "$stack_exit" "$schema_exit" "$dependency_exit" "$service_exit" "$readiness_exit"
      return "$readiness_exit"
    fi
  fi

  local ai_smoke_exit="not_run"
  if is_true "$PISCES_LOCAL_FINALIZE_RUN_AI_SMOKE"; then
    log "Running local TongYi AI smoke"
    set +e
    env \
      PISCES_LOCAL_ENV_FILE="$PISCES_LOCAL_ENV_FILE" \
      PISCES_LOCAL_STACK_ENV_FILE="$PISCES_LOCAL_STACK_ENV_FILE" \
      PISCES_LOCAL_SERVICE_SUMMARY_FILE="$service_summary" \
      PISCES_LOCAL_AI_SMOKE_OUTPUT_FILE="$ai_smoke_summary" \
      PISCES_QIANWEN_API_KEY_ENV="$PISCES_QIANWEN_API_KEY_ENV" \
      PISCES_TONGYI_MODEL_ENV="$PISCES_TONGYI_MODEL_ENV" \
      PISCES_TONGYI_API_MODE_ENV="$PISCES_TONGYI_API_MODE_ENV" \
      PISCES_TONGYI_FALLBACK_MODEL_ENV="$PISCES_TONGYI_FALLBACK_MODEL_ENV" \
      PISCES_TONGYI_FALLBACK_API_MODE_ENV="$PISCES_TONGYI_FALLBACK_API_MODE_ENV" \
      bash "$PISCES_REPO_ROOT/scripts/production-infrastructure-local-ai-smoke.sh"
    ai_smoke_exit=$?
    set -e
    PISCES_LOCAL_FINALIZE_AI_SMOKE_EXIT="$ai_smoke_exit"
    export PISCES_LOCAL_FINALIZE_AI_SMOKE_EXIT
    if [[ "$ai_smoke_exit" -ne 0 ]]; then
      write_summary "AI_SMOKE_FAILED" "$ai_smoke_exit" "Local TongYi AI smoke failed." \
        "$stack_exit" "$schema_exit" "$dependency_exit" "$service_exit" "$readiness_exit"
      return "$ai_smoke_exit"
    fi
  fi

  local frontend_exit="not_run"
  if is_true "$PISCES_LOCAL_FINALIZE_CAPTURE_FRONTEND"; then
    log "Capturing local frontend evidence"
    set +e
    env \
      PISCES_LOCAL_FRONTEND_OUTPUT_FILE="$frontend_summary" \
      PISCES_LOCAL_FRONTEND_REQUIRED_SCREENSHOTS="$PISCES_LOCAL_FRONTEND_REQUIRED_SCREENSHOTS" \
      bash "$PISCES_REPO_ROOT/scripts/production-infrastructure-local-frontend-evidence.sh"
    frontend_exit=$?
    set -e
    if [[ "$frontend_exit" -ne 0 ]]; then
      write_summary "FRONTEND_EVIDENCE_FAILED" "$frontend_exit" "Local frontend evidence capture failed." \
        "$stack_exit" "$schema_exit" "$dependency_exit" "$service_exit" "$readiness_exit" "$frontend_exit"
      return "$frontend_exit"
    fi
  fi

  log "Collecting local production evidence"
  set +e
  env \
    PISCES_LOCAL_ENV_FILE="$PISCES_LOCAL_ENV_FILE" \
    PISCES_LOCAL_STACK_ENV_FILE="$PISCES_LOCAL_STACK_ENV_FILE" \
    PISCES_RELEASE_ID="$PISCES_RELEASE_ID" \
    PISCES_LOCAL_SERVICE_SUMMARY_FILE="$service_summary" \
    PISCES_LOCAL_EVIDENCE_WORKSPACE_DIR="$evidence_workspace" \
    PISCES_LOCAL_EVIDENCE_COLLECT_OUTPUT_FILE="$collect_summary" \
    PISCES_LOCAL_COLLECT_RUN_CLOSEOUT="$PISCES_LOCAL_FINALIZE_RUN_CLOSEOUT" \
    PISCES_LOCAL_COLLECT_REDIS_FAULT_MODE="$PISCES_LOCAL_FINALIZE_REDIS_FAULT_MODE_EFFECTIVE" \
    PISCES_REDIS_DOCKER_CONTAINER="${PISCES_REDIS_DOCKER_CONTAINER:-}" \
    PISCES_FAULT_CONFIRM="$PISCES_LOCAL_FINALIZE_FAULT_CONFIRM_EFFECTIVE" \
    bash "$PISCES_REPO_ROOT/scripts/production-infrastructure-local-evidence-collect.sh"
  local collect_exit=$?
  set -e
  if [[ "$collect_exit" -ne 0 ]]; then
    write_summary "EVIDENCE_COLLECT_FAILED" "$collect_exit" "Local evidence collection failed." \
      "$stack_exit" "$schema_exit" "$dependency_exit" "$service_exit" "$readiness_exit" "$frontend_exit" "$collect_exit"
    return "$collect_exit"
  fi

  if is_true "$PISCES_LOCAL_FINALIZE_RUN_CLOSEOUT"; then
    write_summary "PASS" 0 "Local production evidence collection and closeout completed; final verification pending." \
      "$stack_exit" "$schema_exit" "$dependency_exit" "$service_exit" "$readiness_exit" "$frontend_exit" "$collect_exit"

    local verify_exit="not_run"
    if is_true "$PISCES_LOCAL_FINALIZE_RUN_COMPLETION_VERIFY"; then
      log "Verifying local production completion evidence"
      set +e
      env \
        PISCES_LOCAL_ENV_FILE="$PISCES_LOCAL_ENV_FILE" \
        PISCES_LOCAL_STACK_ENV_FILE="$PISCES_LOCAL_STACK_ENV_FILE" \
        PISCES_LOCAL_FINALIZE_OUTPUT_FILE="$output_file" \
        PISCES_LOCAL_READINESS_OUTPUT_FILE="$readiness_summary" \
        PISCES_LOCAL_COMPLETION_VERIFY_OUTPUT_FILE="$completion_verify_summary" \
        bash "$PISCES_REPO_ROOT/scripts/production-infrastructure-local-completion-verify.sh"
      verify_exit=$?
      set -e
      if [[ "$verify_exit" -ne 0 ]]; then
        write_summary "COMPLETION_VERIFY_FAILED" "$verify_exit" "Local completion verification failed after closeout." \
          "$stack_exit" "$schema_exit" "$dependency_exit" "$service_exit" "$readiness_exit" "$frontend_exit" "$collect_exit" "$verify_exit"
        return "$verify_exit"
      fi
    fi

    write_summary "PASS" 0 "Local production evidence collection, closeout, and completion verification completed." \
      "$stack_exit" "$schema_exit" "$dependency_exit" "$service_exit" "$readiness_exit" "$frontend_exit" "$collect_exit" "$verify_exit"
  else
    write_summary "EVIDENCE_COLLECTED" 0 "Local production evidence collection completed; closeout was skipped by configuration." \
      "$stack_exit" "$schema_exit" "$dependency_exit" "$service_exit" "$readiness_exit" "$frontend_exit" "$collect_exit"
  fi
}

main "$@"
