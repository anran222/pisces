#!/usr/bin/env bash

set -euo pipefail

resolve_repo_root() {
  if [[ -n "${PISCES_REPO_ROOT:-}" ]]; then
    (cd "$PISCES_REPO_ROOT" && pwd)
    return
  fi
  local script_dir
  script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  git -C "$script_dir/.." rev-parse --show-toplevel
}

resolve_path() {
  case "$1" in
    /*) printf '%s' "$1" ;;
    *) printf '%s/%s' "$PISCES_REPO_ROOT" "$1" ;;
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

resolve_key_for_scope() {
  local scope="$1"
  python3 - "${PISCES_API_KEY_SPECS:-}" "$scope" <<'PY'
import sys

specs, required_scope = sys.argv[1:]
for raw_spec in specs.split(","):
    parts = [part.strip() for part in raw_spec.split("|")]
    if len(parts) < 4:
        continue
    scopes = {scope.strip().lower() for scope in parts[3].split("+") if scope.strip()}
    if required_scope.lower() in scopes or "admin" in scopes:
        print(parts[0])
        raise SystemExit(0)
raise SystemExit(1)
PY
}

url_ready() {
  curl -fsS --max-time 3 "$1" >/dev/null 2>&1
}

frontend_pid=""
cleanup() {
  if [[ -n "$frontend_pid" ]] && kill -0 "$frontend_pid" >/dev/null 2>&1; then
    kill "$frontend_pid" >/dev/null 2>&1 || true
    wait "$frontend_pid" >/dev/null 2>&1 || true
  fi
}

main() {
  command -v curl >/dev/null 2>&1 || { printf 'Missing command: curl\n' >&2; return 1; }
  command -v npm >/dev/null 2>&1 || { printf 'Missing command: npm\n' >&2; return 1; }
  command -v python3 >/dev/null 2>&1 || { printf 'Missing command: python3\n' >&2; return 1; }

  PISCES_REPO_ROOT="$(resolve_repo_root)"
  local stack_env_file local_env_file
  stack_env_file="$(resolve_path "${PISCES_LOCAL_STACK_ENV_FILE:-config/pisces-local-stack.env}")"
  local_env_file="$(resolve_path "${PISCES_LOCAL_ENV_FILE:-config/pisces-local.env}")"
  load_env_file "$stack_env_file"
  load_env_file "$local_env_file"

  local instance_urls instance_url management_key admin_key web_dir web_host web_port web_base_url output_file
  instance_urls="${PISCES_INSTANCE_URLS:-http://127.0.0.1:9990/api}"
  instance_url="${instance_urls%%,*}"
  instance_url="${instance_url%/}"
  management_key="${PISCES_MANAGEMENT_API_KEY:-$(resolve_key_for_scope management)}"
  admin_key="${PISCES_ADMIN_API_KEY:-$(resolve_key_for_scope admin || true)}"
  web_dir="$(resolve_path "${PISCES_WEB_DIR:-../pisces-web}")"
  web_host="${PISCES_REAL_WORKFLOW_WEB_HOST:-127.0.0.1}"
  web_port="${PISCES_REAL_WORKFLOW_WEB_PORT:-3041}"
  web_base_url="${PISCES_WEB_BASE_URL:-http://${web_host}:${web_port}}"
  output_file="$(resolve_path "${PISCES_REAL_WORKFLOW_OUTPUT_FILE:-target/pisces-real-browser-workflow-smoke/summary.json}")"

  url_ready "$instance_url/actuator/health" || {
    printf 'Pisces backend is not healthy: %s\n' "$instance_url" >&2
    return 1
  }
  [[ -d "$web_dir" && -f "$web_dir/package.json" ]] || {
    printf 'Pisces frontend directory is unavailable: %s\n' "$web_dir" >&2
    return 1
  }

  if ! url_ready "$web_base_url"; then
    mkdir -p "$(dirname "$output_file")"
    (
      cd "$web_dir"
      npm run dev -- --host "$web_host" --port "$web_port"
    ) >"$(dirname "$output_file")/frontend.log" 2>&1 &
    frontend_pid="$!"
    trap cleanup EXIT
    local deadline=$((SECONDS + 60))
    while [[ "$SECONDS" -lt "$deadline" ]]; do
      url_ready "$web_base_url" && break
      kill -0 "$frontend_pid" >/dev/null 2>&1 || break
      sleep 1
    done
    url_ready "$web_base_url" || {
      printf 'Pisces frontend did not become ready: %s\n' "$web_base_url" >&2
      return 1
    }
  fi

  (
    cd "$web_dir"
    PISCES_WEB_BASE_URL="$web_base_url" \
    PISCES_API_BASE_URL="$instance_url" \
    PISCES_MANAGEMENT_API_KEY="$management_key" \
    PISCES_ADMIN_API_KEY="$admin_key" \
    PISCES_REAL_WORKFLOW_OUTPUT_FILE="$output_file" \
    npm run test:ui:real
  )
}

main "$@"
