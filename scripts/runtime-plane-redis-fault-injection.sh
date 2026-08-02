#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  PISCES_EXPERIMENT_ID=exp_price_001 PISCES_FAULT_CONFIRM=true \
    PISCES_REDIS_DOCKER_CONTAINER=pisces-redis scripts/runtime-plane-redis-fault-injection.sh

Environment:
  PISCES_INSTANCE_URLS              Comma separated service base URLs. Default: http://localhost:9990/api
  PISCES_EXPERIMENT_ID              Required experiment ID.
  PISCES_RUNTIME_API_KEY            Runtime scope API key. Default: runtime-key
  PISCES_REDIS_FAULT_MODE           docker-pause | docker-stop | manual. Default: manual
  PISCES_REDIS_DOCKER_CONTAINER     Required for docker-pause and docker-stop.
  PISCES_FAULT_CONFIRM              Must be true for docker-pause and docker-stop.
  PISCES_FAULT_MANUAL_GRACE_SECONDS Manual mode grace period before traffic. Default: 10
  PISCES_FAULT_DURATION_SECONDS     Fault duration. Default: 30
  PISCES_FAULT_ASSIGNMENT_REQUESTS  Requests per phase. Default: 300
  PISCES_FAULT_ASSIGNMENT_CONCURRENCY  Concurrency per phase. Default: 16
USAGE
}

log() {
  printf '[%s] %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*" >&2
}

die() {
  log "ERROR: $*"
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "Missing command: $1"
}

run_release_drill_phase() {
  local phase="$1"
  local visitor_prefix="fault-${phase}-$(date +%s)"
  log "Running ${phase} runtime drill phase"
  PISCES_INSTANCE_URLS="$PISCES_INSTANCE_URLS" \
  PISCES_EXPERIMENT_ID="$PISCES_EXPERIMENT_ID" \
  PISCES_RUNTIME_API_KEY="$PISCES_RUNTIME_API_KEY" \
  PISCES_RELEASE_ACTION=observe \
  PISCES_ASSIGNMENT_REQUESTS="$PISCES_FAULT_ASSIGNMENT_REQUESTS" \
  PISCES_ASSIGNMENT_CONCURRENCY="$PISCES_FAULT_ASSIGNMENT_CONCURRENCY" \
  PISCES_VISITOR_PREFIX="$visitor_prefix" \
  bash "$PISCES_REPO_ROOT/scripts/runtime-plane-release-drill.sh"
}

apply_fault() {
  case "$PISCES_REDIS_FAULT_MODE" in
    manual)
      log "Manual fault mode: inject Redis failure now. Traffic starts in ${PISCES_FAULT_MANUAL_GRACE_SECONDS}s."
      sleep "$PISCES_FAULT_MANUAL_GRACE_SECONDS"
      ;;
    docker-pause)
      require_command docker
      log "Pausing Redis container ${PISCES_REDIS_DOCKER_CONTAINER}"
      docker pause "$PISCES_REDIS_DOCKER_CONTAINER" >/dev/null
      PISCES_FAULT_APPLIED=docker-pause
      ;;
    docker-stop)
      require_command docker
      log "Stopping Redis container ${PISCES_REDIS_DOCKER_CONTAINER}"
      docker stop "$PISCES_REDIS_DOCKER_CONTAINER" >/dev/null
      PISCES_FAULT_APPLIED=docker-stop
      ;;
    *)
      die "Unsupported PISCES_REDIS_FAULT_MODE: $PISCES_REDIS_FAULT_MODE"
      ;;
  esac
}

restore_fault() {
  case "${PISCES_FAULT_APPLIED:-}" in
    docker-pause)
      log "Unpausing Redis container ${PISCES_REDIS_DOCKER_CONTAINER}"
      docker unpause "$PISCES_REDIS_DOCKER_CONTAINER" >/dev/null || true
      ;;
    docker-stop)
      log "Starting Redis container ${PISCES_REDIS_DOCKER_CONTAINER}"
      docker start "$PISCES_REDIS_DOCKER_CONTAINER" >/dev/null || true
      ;;
    *)
      ;;
  esac
}

validate_environment() {
  [[ -n "$PISCES_EXPERIMENT_ID" ]] || die "PISCES_EXPERIMENT_ID is required"
  [[ "$PISCES_FAULT_DURATION_SECONDS" =~ ^[0-9]+$ ]] || die "PISCES_FAULT_DURATION_SECONDS must be a number"
  [[ "$PISCES_FAULT_MANUAL_GRACE_SECONDS" =~ ^[0-9]+$ ]] \
    || die "PISCES_FAULT_MANUAL_GRACE_SECONDS must be a number"
  [[ "$PISCES_FAULT_ASSIGNMENT_REQUESTS" =~ ^[0-9]+$ ]] \
    || die "PISCES_FAULT_ASSIGNMENT_REQUESTS must be a number"
  [[ "$PISCES_FAULT_ASSIGNMENT_CONCURRENCY" =~ ^[0-9]+$ ]] \
    || die "PISCES_FAULT_ASSIGNMENT_CONCURRENCY must be a number"
  [[ "$PISCES_FAULT_ASSIGNMENT_CONCURRENCY" -gt 0 ]] \
    || die "PISCES_FAULT_ASSIGNMENT_CONCURRENCY must be greater than 0"

  case "$PISCES_REDIS_FAULT_MODE" in
    manual)
      ;;
    docker-pause|docker-stop)
      [[ "$PISCES_FAULT_CONFIRM" == "true" ]] \
        || die "Set PISCES_FAULT_CONFIRM=true before changing Redis container state"
      [[ -n "$PISCES_REDIS_DOCKER_CONTAINER" ]] \
        || die "PISCES_REDIS_DOCKER_CONTAINER is required for $PISCES_REDIS_FAULT_MODE"
      ;;
    *)
      die "Unsupported PISCES_REDIS_FAULT_MODE: $PISCES_REDIS_FAULT_MODE"
      ;;
  esac
}

main() {
  if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
    usage
    exit 0
  fi

  require_command bash
  PISCES_REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
  PISCES_INSTANCE_URLS="${PISCES_INSTANCE_URLS:-http://localhost:9990/api}"
  PISCES_EXPERIMENT_ID="${PISCES_EXPERIMENT_ID:-}"
  PISCES_RUNTIME_API_KEY="${PISCES_RUNTIME_API_KEY:-runtime-key}"
  PISCES_REDIS_FAULT_MODE="${PISCES_REDIS_FAULT_MODE:-manual}"
  PISCES_REDIS_DOCKER_CONTAINER="${PISCES_REDIS_DOCKER_CONTAINER:-}"
  PISCES_FAULT_CONFIRM="${PISCES_FAULT_CONFIRM:-false}"
  PISCES_FAULT_MANUAL_GRACE_SECONDS="${PISCES_FAULT_MANUAL_GRACE_SECONDS:-10}"
  PISCES_FAULT_DURATION_SECONDS="${PISCES_FAULT_DURATION_SECONDS:-30}"
  PISCES_FAULT_ASSIGNMENT_REQUESTS="${PISCES_FAULT_ASSIGNMENT_REQUESTS:-300}"
  PISCES_FAULT_ASSIGNMENT_CONCURRENCY="${PISCES_FAULT_ASSIGNMENT_CONCURRENCY:-16}"
  PISCES_FAULT_APPLIED=""

  export PISCES_INSTANCE_URLS
  export PISCES_EXPERIMENT_ID
  export PISCES_RUNTIME_API_KEY
  export PISCES_REDIS_FAULT_MODE
  export PISCES_REDIS_DOCKER_CONTAINER
  export PISCES_FAULT_CONFIRM
  export PISCES_FAULT_MANUAL_GRACE_SECONDS
  export PISCES_FAULT_DURATION_SECONDS
  export PISCES_FAULT_ASSIGNMENT_REQUESTS
  export PISCES_FAULT_ASSIGNMENT_CONCURRENCY
  export PISCES_REPO_ROOT
  export PISCES_FAULT_APPLIED

  validate_environment
  trap restore_fault EXIT

  log "Starting Redis fault injection: mode=${PISCES_REDIS_FAULT_MODE}, duration=${PISCES_FAULT_DURATION_SECONDS}s"
  run_release_drill_phase baseline
  apply_fault
  sleep "$PISCES_FAULT_DURATION_SECONDS" &
  local sleep_pid=$!
  run_release_drill_phase during-fault
  wait "$sleep_pid" || true
  restore_fault
  PISCES_FAULT_APPLIED=""
  sleep 5
  run_release_drill_phase recovery
  log "Redis fault injection drill completed."
}

main "$@"
