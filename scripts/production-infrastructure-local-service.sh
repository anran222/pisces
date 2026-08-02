#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  scripts/production-infrastructure-local-service.sh [start|stop|status|health|logs]

Environment:
  PISCES_REPO_ROOT                         Repository root. Default: inferred from this script.
  PISCES_LOCAL_ENV_FILE                    Local env file. Default: config/pisces-local.env.
  PISCES_LOCAL_STACK_ENV_FILE              Local dependency stack env file. Default: config/pisces-local-stack.env.
  PISCES_LOCAL_SERVICE_OUTPUT_FILE         JSON output. Default: target/pisces-production-infrastructure-local-service/summary.json.
  PISCES_LOCAL_SERVICE_PID_FILE            Backend pid file. Default: target/pisces-production-infrastructure-local-service/backend.pid.
  PISCES_LOCAL_SERVICE_LOG_FILE            Backend log file. Default: target/pisces-production-infrastructure-local-service/backend.log.
  PISCES_LOCAL_SERVICE_WAIT_SECONDS        Health wait timeout after start. Default: 120.
  PISCES_LOCAL_SERVICE_START_STACK         Start Docker dependencies before backend. Default: true.
  PISCES_LOCAL_SERVICE_APPLY_SCHEMA        Apply local MySQL schema before backend. Default: true.
  PISCES_LOCAL_SERVICE_CHECK_DEPENDENCIES  Run strict dependency preflight before backend. Default: true.
  PISCES_LOCAL_SERVICE_INSTALL_MODULES     Build current executable backend jar before start. Default: true.
  PISCES_LOCAL_SERVICE_APP_JAR             Backend jar path. Default: pisces-api/target/pisces-api-1.0.0.jar.
  PISCES_LOCAL_SERVICE_JAVA_OPTS           Extra Java options for local backend. Default: empty.
  PISCES_LOCAL_SERVICE_LAUNCH_LABEL        macOS launchd label. Default: com.pisces.local-service.
  PISCES_LOCAL_SERVICE_RUNNER_FILE         Generated backend runner. Default: target/.../backend-runner.sh.
  PISCES_LOCAL_SERVICE_PLIST_FILE          Generated launchd plist. Default: target/.../com.pisces.local-service.plist.
  PISCES_LOCAL_SERVICE_DRY_RUN             Write plan only, do not start backend. Default: false.
  PISCES_LOCAL_SERVICE_ALLOW_PLACEHOLDER_QIANWEN
                                           Allow placeholder key for non-final debugging. Default: false.
  PISCES_INSTANCE_URLS                     Service base URL. Default: http://localhost:9990/api.
  PISCES_QIANWEN_API_KEY_ENV               Runtime API key env var. Default: TONGYI_API_KEY.

The start action refuses missing or placeholder Qianwen keys by default. This
keeps local closeout evidence aligned with the final production-grade target.
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

first_instance_url() {
  python3 - "$PISCES_INSTANCE_URLS" <<'PY'
import sys

urls = [item.strip().rstrip("/") for item in sys.argv[1].split(",") if item.strip()]
if not urls:
    raise SystemExit("PISCES_INSTANCE_URLS is empty")
print(urls[0])
PY
}

display_path() {
  python3 - "$PISCES_REPO_ROOT" "$1" <<'PY'
import sys
from pathlib import Path

repo_root = Path(sys.argv[1]).resolve()
path = Path(sys.argv[2]).resolve()
try:
    print(path.relative_to(repo_root))
except ValueError:
    print(path)
PY
}

use_launchctl_supervisor() {
  [[ "$(uname -s)" == "Darwin" ]] && command -v launchctl >/dev/null 2>&1
}

launch_domain() {
  printf 'gui/%s' "$(id -u)"
}

launchctl_pid() {
  use_launchctl_supervisor || return 1
  launchctl print "$(launch_domain)/$PISCES_LOCAL_SERVICE_LAUNCH_LABEL" 2>/dev/null \
    | awk -F'= ' '/^[[:space:]]*pid = / {print $2; exit}'
}

sync_supervisor_pid() {
  local supervisor_pid
  supervisor_pid="$(launchctl_pid || true)"
  if [[ "$supervisor_pid" =~ ^[0-9]+$ ]] && kill -0 "$supervisor_pid" >/dev/null 2>&1; then
    printf '%s\n' "$supervisor_pid" >"$PISCES_LOCAL_SERVICE_PID_FILE_RESOLVED"
    return 0
  fi
  return 1
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

pid_status() {
  if [[ -f "$PISCES_LOCAL_SERVICE_PID_FILE_RESOLVED" ]]; then
    local pid
    pid="$(cat "$PISCES_LOCAL_SERVICE_PID_FILE_RESOLVED" 2>/dev/null || true)"
    if [[ -n "$pid" ]] && kill -0 "$pid" >/dev/null 2>&1; then
      printf 'running'
      return
    fi
  fi
  if sync_supervisor_pid; then
    printf 'running'
    return
  fi
  printf 'not_running'
}

health_status() {
  local response_file="$PISCES_LOCAL_SERVICE_DIR/health.json"
  if ! command -v curl >/dev/null 2>&1; then
    printf 'curl_missing'
    return
  fi
  set +e
  curl -fsS --max-time 5 "$PISCES_LOCAL_SERVICE_HEALTH_URL" -o "$response_file" >/dev/null 2>&1
  local curl_status=$?
  set -e
  if [[ "$curl_status" -ne 0 ]]; then
    printf 'curl_exit_%s' "$curl_status"
    return
  fi
  python3 - "$response_file" <<'PY'
import json
import sys

try:
    payload = json.load(open(sys.argv[1], encoding="utf-8"))
except Exception as exc:
    print(f"invalid_json:{exc}")
    raise SystemExit(0)
print(payload.get("status") or "missing_status")
PY
}

write_summary() {
  local status="$1"
  local action="$2"
  local exit_code="$3"
  local message="$4"
  local service_pid=""
  local process_status
  local current_health
  process_status="$(pid_status)"
  current_health="$(health_status)"
  if [[ -f "$PISCES_LOCAL_SERVICE_PID_FILE_RESOLVED" ]]; then
    service_pid="$(cat "$PISCES_LOCAL_SERVICE_PID_FILE_RESOLVED" 2>/dev/null || true)"
  fi

  export PISCES_LOCAL_SERVICE_SUMMARY_STATUS="$status"
  export PISCES_LOCAL_SERVICE_SUMMARY_ACTION="$action"
  export PISCES_LOCAL_SERVICE_SUMMARY_EXIT_CODE="$exit_code"
  export PISCES_LOCAL_SERVICE_SUMMARY_MESSAGE="$message"
  export PISCES_LOCAL_SERVICE_PROCESS_STATUS="$process_status"
  export PISCES_LOCAL_SERVICE_HEALTH_STATUS="$current_health"
  export PISCES_LOCAL_SERVICE_PID_VALUE="$service_pid"

  python3 <<'PY'
import json
import os
from datetime import datetime, timezone
from pathlib import Path

repo_root = Path(os.environ["PISCES_REPO_ROOT"]).resolve()
output_file = Path(os.environ["PISCES_LOCAL_SERVICE_OUTPUT_FILE_RESOLVED"])
env_file = Path(os.environ["PISCES_LOCAL_ENV_FILE_RESOLVED"])
stack_env_file = Path(os.environ["PISCES_LOCAL_STACK_ENV_FILE_RESOLVED"])
pid_file = Path(os.environ["PISCES_LOCAL_SERVICE_PID_FILE_RESOLVED"])
log_file = Path(os.environ["PISCES_LOCAL_SERVICE_LOG_FILE_RESOLVED"])
app_jar = Path(os.environ["PISCES_LOCAL_SERVICE_APP_JAR_RESOLVED"])


def display(path):
    try:
        return str(path.resolve().relative_to(repo_root))
    except ValueError:
        return str(path)


status = os.environ["PISCES_LOCAL_SERVICE_SUMMARY_STATUS"]
action = os.environ["PISCES_LOCAL_SERVICE_SUMMARY_ACTION"]
next_commands = []
if status == "NEEDS_QIANWEN_API_KEY":
    next_commands.extend([
        "edit config/pisces-local.env and replace only TONGYI_API_KEY",
        "bash scripts/production-infrastructure-local-service.sh start",
        "bash scripts/production-infrastructure-local-finalize.sh",
    ])
elif status in {"STARTED", "HEALTHY"}:
    next_commands.extend([
        "bash scripts/production-infrastructure-local-readiness.sh",
        "PISCES_RELEASE_ID=\"local-$(date +%Y%m%d)-runtime-plane\" bash scripts/production-infrastructure-local-evidence-collect.sh",
    ])
elif action == "start":
    next_commands.extend([
        "inspect target/pisces-production-infrastructure-local-service/backend.log",
        "bash scripts/production-infrastructure-local-service.sh status",
    ])
elif action == "stop":
    next_commands.append("bash scripts/production-infrastructure-local-service.sh start")

summary = {
    "summaryType": "pisces-production-infrastructure-local-service",
    "summaryVersion": 1,
    "generatedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "status": status,
    "targetEnvironment": "local",
    "action": action,
    "exitCode": int(os.environ["PISCES_LOCAL_SERVICE_SUMMARY_EXIT_CODE"]),
    "message": os.environ["PISCES_LOCAL_SERVICE_SUMMARY_MESSAGE"],
    "apiKeyEnv": os.environ["PISCES_QIANWEN_API_KEY_ENV"],
    "apiKeyStatus": os.environ["PISCES_LOCAL_SERVICE_QIANWEN_KEY_STATUS"],
    "dryRun": os.environ["PISCES_LOCAL_SERVICE_DRY_RUN"].lower() in {"true", "1", "yes", "y"},
    "processStatus": os.environ["PISCES_LOCAL_SERVICE_PROCESS_STATUS"],
    "pid": os.environ["PISCES_LOCAL_SERVICE_PID_VALUE"] or None,
    "pidFile": display(pid_file),
    "logFile": display(log_file),
    "envFile": display(env_file),
    "stackEnvFile": display(stack_env_file),
    "stackEnvFilePresent": stack_env_file.is_file(),
    "healthUrl": os.environ["PISCES_LOCAL_SERVICE_HEALTH_URL"],
    "healthStatus": os.environ["PISCES_LOCAL_SERVICE_HEALTH_STATUS"],
    "startStack": os.environ["PISCES_LOCAL_SERVICE_START_STACK"].lower() in {"true", "1", "yes", "y"},
    "applySchema": os.environ["PISCES_LOCAL_SERVICE_APPLY_SCHEMA"].lower() in {"true", "1", "yes", "y"},
    "checkDependencies": os.environ["PISCES_LOCAL_SERVICE_CHECK_DEPENDENCIES"].lower() in {"true", "1", "yes", "y"},
    "installModules": os.environ["PISCES_LOCAL_SERVICE_INSTALL_MODULES"].lower() in {"true", "1", "yes", "y"},
    "waitSeconds": int(os.environ["PISCES_LOCAL_SERVICE_WAIT_SECONDS"]),
    "appJar": display(app_jar),
    "launchLabel": os.environ["PISCES_LOCAL_SERVICE_LAUNCH_LABEL"],
    "supervisor": os.environ["PISCES_LOCAL_SERVICE_SUPERVISOR"],
    "prepareCommand": "mvn -pl pisces-api -am -DskipTests package",
    "startCommand": f"java -jar {display(app_jar)}",
    "nextCommands": next_commands,
}
output_file.write_text(json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
print(f"Production infrastructure local service written: {output_file} status={status} action={action}", file=os.sys.stderr)
PY
}

wait_for_health() {
  local deadline=$((SECONDS + PISCES_LOCAL_SERVICE_WAIT_SECONDS))
  local current
  while [[ "$SECONDS" -le "$deadline" ]]; do
    current="$(health_status)"
    if [[ "$current" == "UP" ]]; then
      return 0
    fi
    sleep 2
  done
  return 1
}

write_launchd_files() {
  export PISCES_LOCAL_SERVICE_RUNNER_FILE_RESOLVED
  export PISCES_LOCAL_SERVICE_PLIST_FILE_RESOLVED
  export PISCES_LOCAL_SERVICE_LAUNCH_LABEL
  export PISCES_LOCAL_SERVICE_JAVA_OPTS

  python3 <<'PY'
import os
import plistlib
import shlex
from pathlib import Path

repo_root = Path(os.environ["PISCES_REPO_ROOT"]).resolve()
env_file = Path(os.environ["PISCES_LOCAL_ENV_FILE_RESOLVED"]).resolve()
stack_env_file = Path(os.environ["PISCES_LOCAL_STACK_ENV_FILE_RESOLVED"]).resolve()
pid_file = Path(os.environ["PISCES_LOCAL_SERVICE_PID_FILE_RESOLVED"]).resolve()
log_file = Path(os.environ["PISCES_LOCAL_SERVICE_LOG_FILE_RESOLVED"]).resolve()
app_jar = Path(os.environ["PISCES_LOCAL_SERVICE_APP_JAR_RESOLVED"]).resolve()
runner_file = Path(os.environ["PISCES_LOCAL_SERVICE_RUNNER_FILE_RESOLVED"]).resolve()
plist_file = Path(os.environ["PISCES_LOCAL_SERVICE_PLIST_FILE_RESOLVED"]).resolve()
java_opts = os.environ.get("PISCES_LOCAL_SERVICE_JAVA_OPTS", "").strip()

runner_file.parent.mkdir(parents=True, exist_ok=True)
plist_file.parent.mkdir(parents=True, exist_ok=True)
log_file.parent.mkdir(parents=True, exist_ok=True)

java_cmd = "java"
if java_opts:
    java_cmd = f"java {java_opts}"
runner = f"""#!/usr/bin/env bash
set -euo pipefail
cd {shlex.quote(str(repo_root))}
if [[ -f {shlex.quote(str(env_file))} ]]; then
  set -a
  source {shlex.quote(str(env_file))}
  set +a
fi
if [[ -f {shlex.quote(str(stack_env_file))} ]]; then
  set -a
  source {shlex.quote(str(stack_env_file))}
  set +a
fi
printf '%s\\n' "$$" > {shlex.quote(str(pid_file))}
exec {java_cmd} -jar {shlex.quote(str(app_jar))}
"""
runner_file.write_text(runner, encoding="utf-8")
runner_file.chmod(0o755)

plist = {
    "Label": os.environ["PISCES_LOCAL_SERVICE_LAUNCH_LABEL"],
    "ProgramArguments": ["/bin/bash", str(runner_file)],
    "WorkingDirectory": str(repo_root),
    "RunAtLoad": True,
    "StandardOutPath": str(log_file),
    "StandardErrorPath": str(log_file),
}
with plist_file.open("wb") as handle:
    plistlib.dump(plist, handle, sort_keys=True)
PY
}

launch_backend() {
  : >"$PISCES_LOCAL_SERVICE_LOG_FILE_RESOLVED"
  rm -f "$PISCES_LOCAL_SERVICE_PID_FILE_RESOLVED"
  if use_launchctl_supervisor; then
    write_launchd_files
    local domain
    domain="$(launch_domain)"
    launchctl bootout "$domain/$PISCES_LOCAL_SERVICE_LAUNCH_LABEL" >/dev/null 2>&1 || true
    sleep 1
    set +e
    launchctl bootstrap "$domain" "$PISCES_LOCAL_SERVICE_PLIST_FILE_RESOLVED"
    local bootstrap_exit=$?
    set -e
    if [[ "$bootstrap_exit" -ne 0 ]]; then
      sleep 2
      launchctl bootout "$domain/$PISCES_LOCAL_SERVICE_LAUNCH_LABEL" >/dev/null 2>&1 || true
      sleep 1
      launchctl bootstrap "$domain" "$PISCES_LOCAL_SERVICE_PLIST_FILE_RESOLVED"
    fi
    return
  fi

  (
    cd "$PISCES_REPO_ROOT"
    if [[ -n "${PISCES_LOCAL_SERVICE_JAVA_OPTS:-}" ]]; then
      # shellcheck disable=SC2086
      nohup java $PISCES_LOCAL_SERVICE_JAVA_OPTS -jar "$PISCES_LOCAL_SERVICE_APP_JAR_RESOLVED" >"$PISCES_LOCAL_SERVICE_LOG_FILE_RESOLVED" 2>&1 &
    else
      nohup java -jar "$PISCES_LOCAL_SERVICE_APP_JAR_RESOLVED" >"$PISCES_LOCAL_SERVICE_LOG_FILE_RESOLVED" 2>&1 &
    fi
    printf '%s\n' "$!" >"$PISCES_LOCAL_SERVICE_PID_FILE_RESOLVED"
  )
}

start_service() {
  if [[ "$PISCES_LOCAL_SERVICE_QIANWEN_KEY_STATUS" != "configured" ]] \
    && ! is_true "$PISCES_LOCAL_SERVICE_ALLOW_PLACEHOLDER_QIANWEN"; then
    write_summary "NEEDS_QIANWEN_API_KEY" "start" 1 "Refusing to start local service with missing or placeholder Qianwen API key."
    return 1
  fi

  if is_true "$PISCES_LOCAL_SERVICE_DRY_RUN"; then
    write_summary "PLAN_ONLY" "start" 0 "Dry run only; backend was not started."
    return 0
  fi

  command -v mvn >/dev/null 2>&1 || die "Missing command: mvn"

  if is_true "$PISCES_LOCAL_SERVICE_START_STACK"; then
    bash "$PISCES_REPO_ROOT/scripts/production-infrastructure-local-dependency-stack.sh" up >/dev/null
  fi
  if is_true "$PISCES_LOCAL_SERVICE_APPLY_SCHEMA"; then
    bash "$PISCES_REPO_ROOT/scripts/production-infrastructure-local-mysql-schema-apply.sh" >/dev/null
  fi
  if is_true "$PISCES_LOCAL_SERVICE_CHECK_DEPENDENCIES"; then
    PISCES_LOCAL_DEPENDENCY_STRICT=true \
      bash "$PISCES_REPO_ROOT/scripts/production-infrastructure-local-dependency-check.sh" >/dev/null
  fi
  if is_true "$PISCES_LOCAL_SERVICE_INSTALL_MODULES"; then
    (
      cd "$PISCES_REPO_ROOT"
      mvn -pl pisces-api -am -DskipTests package
    ) >/dev/null
  fi
  if [[ ! -f "$PISCES_LOCAL_SERVICE_APP_JAR_RESOLVED" ]]; then
    write_summary "MISSING_BACKEND_JAR" "start" 1 "Backend executable jar is missing after package step."
    return 1
  fi

  if [[ "$(pid_status)" == "running" ]]; then
    if wait_for_health; then
      write_summary "HEALTHY" "start" 0 "Local service was already running and health is UP."
      return 0
    fi
    write_summary "UNHEALTHY" "start" 1 "Local service process is already running but health did not become UP."
    return 1
  fi

  mkdir -p "$PISCES_LOCAL_SERVICE_DIR"
  launch_backend

  if wait_for_health; then
    write_summary "HEALTHY" "start" 0 "Local service started and actuator health is UP."
    return 0
  fi
  write_summary "UNHEALTHY" "start" 1 "Local service was started but actuator health did not become UP before timeout."
  return 1
}

stop_service() {
  if use_launchctl_supervisor; then
    launchctl bootout "$(launch_domain)/$PISCES_LOCAL_SERVICE_LAUNCH_LABEL" >/dev/null 2>&1 || true
  fi
  local pid=""
  if [[ -f "$PISCES_LOCAL_SERVICE_PID_FILE_RESOLVED" ]]; then
    pid="$(cat "$PISCES_LOCAL_SERVICE_PID_FILE_RESOLVED" 2>/dev/null || true)"
  fi
  if [[ -n "$pid" ]] && kill -0 "$pid" >/dev/null 2>&1; then
    kill "$pid" >/dev/null 2>&1 || true
    local deadline=$((SECONDS + 20))
    while [[ "$SECONDS" -le "$deadline" ]] && kill -0 "$pid" >/dev/null 2>&1; do
      sleep 1
    done
  fi
  rm -f "$PISCES_LOCAL_SERVICE_PID_FILE_RESOLVED"
  write_summary "STOPPED" "stop" 0 "Local service stop requested."
}

show_logs() {
  if [[ -f "$PISCES_LOCAL_SERVICE_LOG_FILE_RESOLVED" ]]; then
    tail -n "${PISCES_LOCAL_SERVICE_LOG_LINES:-120}" "$PISCES_LOCAL_SERVICE_LOG_FILE_RESOLVED"
    write_summary "LOGS_RECORDED" "logs" 0 "Local service logs printed."
    return
  fi
  write_summary "LOGS_MISSING" "logs" 1 "Local service log file does not exist."
  return 1
}

main() {
  if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
    usage
    exit 0
  fi

  command -v python3 >/dev/null 2>&1 || die "Missing command: python3"

  local action="${1:-start}"
  case "$action" in
    start|stop|status|health|logs)
      ;;
    *)
      usage >&2
      die "Unsupported action: $action"
      ;;
  esac

  PISCES_REPO_ROOT="$(resolve_repo_root)"
  PISCES_LOCAL_ENV_FILE="${PISCES_LOCAL_ENV_FILE:-config/pisces-local.env}"
  PISCES_LOCAL_STACK_ENV_FILE="${PISCES_LOCAL_STACK_ENV_FILE:-config/pisces-local-stack.env}"
  PISCES_LOCAL_SERVICE_OUTPUT_FILE="${PISCES_LOCAL_SERVICE_OUTPUT_FILE:-target/pisces-production-infrastructure-local-service/summary.json}"
  PISCES_LOCAL_SERVICE_PID_FILE="${PISCES_LOCAL_SERVICE_PID_FILE:-target/pisces-production-infrastructure-local-service/backend.pid}"
  PISCES_LOCAL_SERVICE_LOG_FILE="${PISCES_LOCAL_SERVICE_LOG_FILE:-target/pisces-production-infrastructure-local-service/backend.log}"
  PISCES_LOCAL_SERVICE_WAIT_SECONDS="${PISCES_LOCAL_SERVICE_WAIT_SECONDS:-120}"
  PISCES_LOCAL_SERVICE_START_STACK="${PISCES_LOCAL_SERVICE_START_STACK:-true}"
  PISCES_LOCAL_SERVICE_APPLY_SCHEMA="${PISCES_LOCAL_SERVICE_APPLY_SCHEMA:-true}"
  PISCES_LOCAL_SERVICE_CHECK_DEPENDENCIES="${PISCES_LOCAL_SERVICE_CHECK_DEPENDENCIES:-true}"
  PISCES_LOCAL_SERVICE_INSTALL_MODULES="${PISCES_LOCAL_SERVICE_INSTALL_MODULES:-true}"
  PISCES_LOCAL_SERVICE_APP_JAR="${PISCES_LOCAL_SERVICE_APP_JAR:-pisces-api/target/pisces-api-1.0.0.jar}"
  PISCES_LOCAL_SERVICE_JAVA_OPTS="${PISCES_LOCAL_SERVICE_JAVA_OPTS:-}"
  PISCES_LOCAL_SERVICE_LAUNCH_LABEL="${PISCES_LOCAL_SERVICE_LAUNCH_LABEL:-com.pisces.local-service}"
  PISCES_LOCAL_SERVICE_RUNNER_FILE="${PISCES_LOCAL_SERVICE_RUNNER_FILE:-target/pisces-production-infrastructure-local-service/backend-runner.sh}"
  PISCES_LOCAL_SERVICE_PLIST_FILE="${PISCES_LOCAL_SERVICE_PLIST_FILE:-target/pisces-production-infrastructure-local-service/com.pisces.local-service.plist}"
  PISCES_LOCAL_SERVICE_DRY_RUN="${PISCES_LOCAL_SERVICE_DRY_RUN:-false}"
  PISCES_LOCAL_SERVICE_ALLOW_PLACEHOLDER_QIANWEN="${PISCES_LOCAL_SERVICE_ALLOW_PLACEHOLDER_QIANWEN:-false}"
  PISCES_INSTANCE_URLS="${PISCES_INSTANCE_URLS:-http://localhost:9990/api}"
  PISCES_QIANWEN_API_KEY_ENV="${PISCES_QIANWEN_API_KEY_ENV:-TONGYI_API_KEY}"

  local env_file stack_env_file output_file pid_file log_file app_jar runner_file plist_file
  env_file="$(resolve_path "$PISCES_LOCAL_ENV_FILE")"
  stack_env_file="$(resolve_path "$PISCES_LOCAL_STACK_ENV_FILE")"
  output_file="$(resolve_path "$PISCES_LOCAL_SERVICE_OUTPUT_FILE")"
  pid_file="$(resolve_path "$PISCES_LOCAL_SERVICE_PID_FILE")"
  log_file="$(resolve_path "$PISCES_LOCAL_SERVICE_LOG_FILE")"
  app_jar="$(resolve_path "$PISCES_LOCAL_SERVICE_APP_JAR")"
  runner_file="$(resolve_path "$PISCES_LOCAL_SERVICE_RUNNER_FILE")"
  plist_file="$(resolve_path "$PISCES_LOCAL_SERVICE_PLIST_FILE")"
  mkdir -p "$(dirname "$output_file")" "$(dirname "$pid_file")" "$(dirname "$log_file")"

  if [[ -f "$env_file" ]]; then
    set -a
    # shellcheck disable=SC1090
    source "$env_file"
    set +a
  fi
  if [[ -f "$stack_env_file" ]]; then
    set -a
    # shellcheck disable=SC1090
    source "$stack_env_file"
    set +a
  fi

  PISCES_LOCAL_SERVICE_DIR="$(dirname "$output_file")"
  PISCES_LOCAL_SERVICE_HEALTH_URL="$(first_instance_url)/actuator/health"
  PISCES_LOCAL_SERVICE_QIANWEN_KEY_STATUS="$(qianwen_key_status)"
  PISCES_LOCAL_ENV_FILE_RESOLVED="$env_file"
  PISCES_LOCAL_STACK_ENV_FILE_RESOLVED="$stack_env_file"
  PISCES_LOCAL_SERVICE_OUTPUT_FILE_RESOLVED="$output_file"
  PISCES_LOCAL_SERVICE_PID_FILE_RESOLVED="$pid_file"
  PISCES_LOCAL_SERVICE_LOG_FILE_RESOLVED="$log_file"
  PISCES_LOCAL_SERVICE_APP_JAR_RESOLVED="$app_jar"
  PISCES_LOCAL_SERVICE_RUNNER_FILE_RESOLVED="$runner_file"
  PISCES_LOCAL_SERVICE_PLIST_FILE_RESOLVED="$plist_file"
  PISCES_LOCAL_SERVICE_SUPERVISOR="nohup"
  if use_launchctl_supervisor; then
    PISCES_LOCAL_SERVICE_SUPERVISOR="launchctl"
  fi

  export PISCES_REPO_ROOT
  export PISCES_LOCAL_SERVICE_DIR
  export PISCES_LOCAL_ENV_FILE_RESOLVED
  export PISCES_LOCAL_STACK_ENV_FILE_RESOLVED
  export PISCES_LOCAL_SERVICE_OUTPUT_FILE_RESOLVED
  export PISCES_LOCAL_SERVICE_PID_FILE_RESOLVED
  export PISCES_LOCAL_SERVICE_LOG_FILE_RESOLVED
  export PISCES_LOCAL_SERVICE_APP_JAR_RESOLVED
  export PISCES_LOCAL_SERVICE_RUNNER_FILE_RESOLVED
  export PISCES_LOCAL_SERVICE_PLIST_FILE_RESOLVED
  export PISCES_LOCAL_SERVICE_HEALTH_URL
  export PISCES_LOCAL_SERVICE_QIANWEN_KEY_STATUS
  export PISCES_LOCAL_SERVICE_LAUNCH_LABEL
  export PISCES_LOCAL_SERVICE_SUPERVISOR
  export PISCES_LOCAL_SERVICE_WAIT_SECONDS
  export PISCES_LOCAL_SERVICE_START_STACK
  export PISCES_LOCAL_SERVICE_APPLY_SCHEMA
  export PISCES_LOCAL_SERVICE_CHECK_DEPENDENCIES
  export PISCES_LOCAL_SERVICE_INSTALL_MODULES
  export PISCES_LOCAL_SERVICE_DRY_RUN
  export PISCES_LOCAL_SERVICE_ALLOW_PLACEHOLDER_QIANWEN
  export PISCES_QIANWEN_API_KEY_ENV

  case "$action" in
    start)
      start_service
      ;;
    stop)
      stop_service
      ;;
    status)
      write_summary "STATUS_RECORDED" "status" 0 "Local service status recorded."
      ;;
    health)
      if [[ "$(health_status)" == "UP" ]]; then
        write_summary "HEALTHY" "health" 0 "Local service actuator health is UP."
        return 0
      fi
      write_summary "UNHEALTHY" "health" 1 "Local service actuator health is not UP."
      return 1
      ;;
    logs)
      show_logs
      ;;
  esac
}

main "$@"
