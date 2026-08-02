#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  scripts/production-infrastructure-local-closeout.sh

Environment:
  PISCES_REPO_ROOT                                Repository root. Default: inferred from this script.
  PISCES_RELEASE_ID                               Local release ID. Default: local-<utc timestamp>.
  PISCES_LOCAL_CLOSEOUT_DIR                       Output root. Default: target/pisces-production-infrastructure-local-closeout.
  PISCES_LOCAL_CLOSEOUT_RUN_PACKAGE_CHECK         Run strict release package check. Default: true.
  PISCES_LOCAL_CLOSEOUT_REQUIRE_CLEAN_GIT         Require clean worktree before final closeout. Default: true.
  PISCES_LOCAL_CLOSEOUT_REQUIRE_QIANWEN_KEY       Require Qianwen API key env var. Default: true.
  PISCES_QIANWEN_API_KEY_ENV                      Qianwen API key env var. Default: TONGYI_API_KEY.

Required evidence inputs:
  PISCES_PREPROD_DRILL_RECORD_FILE                Local preprod drill record markdown.
  PISCES_CAPACITY_BASELINE_MANIFEST_FILE          Local capacity baseline manifest JSON.
  PISCES_REDIS_FAULT_RECORD_FILE                  Local Redis fault drill record.
  PISCES_EVENT_REPLAY_AUDIT_SUMMARY_FILE          Local event replay audit summary JSON.
  PISCES_POST_RELEASE_METRICS_FILE                Local post-release metrics JSON.
  PISCES_EXPERIMENT_IMPACT_SUMMARY_FILE           Local experiment impact sampling summary JSON.
  PISCES_ROLLOUT_ACCEPTANCE_RECORD_FILE           Local staged rollout acceptance record JSON.
  PISCES_PRODUCTION_ACCEPTANCE_RECORD_FILE        Local production acceptance sign-off JSON.
  PISCES_COMPLETION_SCREENSHOT_DIR                Core screenshot directory. Default: ../pisces-web/target/screenshots/core-functions-current.

Optional output overrides:
  PISCES_LOCAL_EVIDENCE_VALIDATE_OUTPUT_FILE      Default: <closeout-dir>/local-evidence-validate-summary.json.
  PISCES_RELEASE_PACKAGE_REPORT_FILE              Default: <closeout-dir>/release-package-report.json.
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

resolve_git_sha() {
  git -C "$PISCES_REPO_ROOT" rev-parse HEAD
}

require_clean_git() {
  if [[ -n "$(git -C "$PISCES_REPO_ROOT" status --porcelain)" ]]; then
    die "Final local closeout requires clean git worktree. Commit or intentionally remove local changes first."
  fi
}

require_file_env() {
  local env_name="$1"
  local value="${!env_name:-}"
  [[ -n "$value" ]] || die "$env_name is required"
  [[ -f "$(resolve_path "$value")" ]] || die "$env_name not found: $value"
}

require_no_placeholder_evidence() {
  local -a env_names=(
    PISCES_PREPROD_DRILL_RECORD_FILE
    PISCES_CAPACITY_BASELINE_MANIFEST_FILE
    PISCES_REDIS_FAULT_RECORD_FILE
    PISCES_EVENT_REPLAY_AUDIT_SUMMARY_FILE
    PISCES_POST_RELEASE_METRICS_FILE
    PISCES_EXPERIMENT_IMPACT_SUMMARY_FILE
    PISCES_ROLLOUT_ACCEPTANCE_RECORD_FILE
    PISCES_PRODUCTION_ACCEPTANCE_RECORD_FILE
  )

  local findings=""
  local env_name value path result
  for env_name in "${env_names[@]}"; do
    value="${!env_name:-}"
    path="$(resolve_path "$value")"
    if command -v rg >/dev/null 2>&1; then
      result="$(rg -n -- 'TODO[A-Z0-9_]*|LOCAL-TODO' "$path" || true)"
    else
      result="$(grep -nE 'TODO[A-Z0-9_]*|LOCAL-TODO' "$path" || true)"
    fi
    if [[ -n "$result" ]]; then
      findings+=$'\n'
      findings+="$env_name=$path"$'\n'
      findings+="$result"$'\n'
    fi
  done

  if [[ -n "$findings" ]]; then
    printf '%s\n' "$findings" >&2
    die "Local closeout evidence still contains TODO placeholders; replace them with real local evidence first."
  fi
}

main() {
  if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
    usage
    exit 0
  fi

  require_command python3
  require_command git

  PISCES_REPO_ROOT="$(resolve_repo_root)"
  PISCES_RELEASE_ID="${PISCES_RELEASE_ID:-local-$(date -u '+%Y%m%dT%H%M%SZ')}"
  PISCES_LOCAL_CLOSEOUT_DIR="${PISCES_LOCAL_CLOSEOUT_DIR:-target/pisces-production-infrastructure-local-closeout}"
  PISCES_LOCAL_CLOSEOUT_RUN_PACKAGE_CHECK="${PISCES_LOCAL_CLOSEOUT_RUN_PACKAGE_CHECK:-true}"
  PISCES_LOCAL_CLOSEOUT_REQUIRE_CLEAN_GIT="${PISCES_LOCAL_CLOSEOUT_REQUIRE_CLEAN_GIT:-true}"
  PISCES_LOCAL_CLOSEOUT_REQUIRE_QIANWEN_KEY="${PISCES_LOCAL_CLOSEOUT_REQUIRE_QIANWEN_KEY:-true}"
  PISCES_QIANWEN_API_KEY_ENV="${PISCES_QIANWEN_API_KEY_ENV:-TONGYI_API_KEY}"
  PISCES_COMPLETION_SCREENSHOT_DIR="${PISCES_COMPLETION_SCREENSHOT_DIR:-../pisces-web/target/screenshots/core-functions-current}"

  local closeout_dir archive_root evidence_validate_summary release_report preprod_summary manifest_file slo_summary rollout_summary acceptance_summary final_dir expected_git_sha
  closeout_dir="$(resolve_path "$PISCES_LOCAL_CLOSEOUT_DIR")"
  archive_root="$closeout_dir/release-evidence-archive"
  evidence_validate_summary="$(resolve_path "${PISCES_LOCAL_EVIDENCE_VALIDATE_OUTPUT_FILE:-$PISCES_LOCAL_CLOSEOUT_DIR/local-evidence-validate-summary.json}")"
  release_report="$(resolve_path "${PISCES_RELEASE_PACKAGE_REPORT_FILE:-$PISCES_LOCAL_CLOSEOUT_DIR/release-package-report.json}")"
  preprod_summary="$closeout_dir/preprod-record-check-summary.json"
  slo_summary="$closeout_dir/post-release-slo-summary.json"
  rollout_summary="$closeout_dir/staged-rollout-decision-summary.json"
  acceptance_summary="$closeout_dir/production-acceptance-summary.json"
  final_dir="$closeout_dir/final"
  mkdir -p "$closeout_dir" "$archive_root"

  if is_true "$PISCES_LOCAL_CLOSEOUT_REQUIRE_CLEAN_GIT"; then
    require_clean_git
  fi

  if is_true "$PISCES_LOCAL_CLOSEOUT_REQUIRE_QIANWEN_KEY" && [[ -z "${!PISCES_QIANWEN_API_KEY_ENV:-}" ]]; then
    die "$PISCES_QIANWEN_API_KEY_ENV is required for local production closeout"
  fi

  require_command ruby
  require_command promtool

  require_file_env PISCES_PREPROD_DRILL_RECORD_FILE
  require_file_env PISCES_CAPACITY_BASELINE_MANIFEST_FILE
  require_file_env PISCES_REDIS_FAULT_RECORD_FILE
  require_file_env PISCES_EVENT_REPLAY_AUDIT_SUMMARY_FILE
  require_file_env PISCES_POST_RELEASE_METRICS_FILE
  require_file_env PISCES_EXPERIMENT_IMPACT_SUMMARY_FILE
  require_file_env PISCES_ROLLOUT_ACCEPTANCE_RECORD_FILE
  require_file_env PISCES_PRODUCTION_ACCEPTANCE_RECORD_FILE
  require_no_placeholder_evidence

  expected_git_sha="$(resolve_git_sha)"
  local require_clean_git
  require_clean_git=false
  if is_true "$PISCES_LOCAL_CLOSEOUT_REQUIRE_CLEAN_GIT"; then
    require_clean_git=true
  fi

  log "Validating local closeout evidence files"
  PISCES_RELEASE_ID="$PISCES_RELEASE_ID" \
  PISCES_EXPECTED_GIT_SHA="$expected_git_sha" \
  PISCES_TARGET_ENVIRONMENT=local \
  PISCES_LOCAL_EVIDENCE_VALIDATE_OUTPUT_FILE="$evidence_validate_summary" \
  PISCES_PREPROD_DRILL_RECORD_FILE="$(resolve_path "$PISCES_PREPROD_DRILL_RECORD_FILE")" \
  PISCES_CAPACITY_BASELINE_MANIFEST_FILE="$(resolve_path "$PISCES_CAPACITY_BASELINE_MANIFEST_FILE")" \
  PISCES_REDIS_FAULT_RECORD_FILE="$(resolve_path "$PISCES_REDIS_FAULT_RECORD_FILE")" \
  PISCES_EVENT_REPLAY_AUDIT_SUMMARY_FILE="$(resolve_path "$PISCES_EVENT_REPLAY_AUDIT_SUMMARY_FILE")" \
  PISCES_POST_RELEASE_METRICS_FILE="$(resolve_path "$PISCES_POST_RELEASE_METRICS_FILE")" \
  PISCES_EXPERIMENT_IMPACT_SUMMARY_FILE="$(resolve_path "$PISCES_EXPERIMENT_IMPACT_SUMMARY_FILE")" \
  PISCES_ROLLOUT_ACCEPTANCE_RECORD_FILE="$(resolve_path "$PISCES_ROLLOUT_ACCEPTANCE_RECORD_FILE")" \
  PISCES_PRODUCTION_ACCEPTANCE_RECORD_FILE="$(resolve_path "$PISCES_PRODUCTION_ACCEPTANCE_RECORD_FILE")" \
  bash "$PISCES_REPO_ROOT/scripts/production-infrastructure-local-evidence-validate.sh"

  if is_true "$PISCES_LOCAL_CLOSEOUT_RUN_PACKAGE_CHECK"; then
    log "Running strict local release package check"
    PISCES_RELEASE_PACKAGE_RUN_TESTS=true \
    PISCES_RELEASE_PACKAGE_REQUIRE_PROMTOOL=true \
    PISCES_RELEASE_PACKAGE_REQUIRE_RUBY=true \
    PISCES_RELEASE_PACKAGE_REPORT_FILE="$release_report" \
    bash "$PISCES_REPO_ROOT/scripts/runtime-plane-release-package-check.sh"
  else
    [[ -f "$release_report" ]] || die "Release package report not found: $release_report"
  fi

  log "Checking local preprod drill record"
  PISCES_PREPROD_DRILL_RECORD_FILE="$(resolve_path "$PISCES_PREPROD_DRILL_RECORD_FILE")" \
  PISCES_PREPROD_DRILL_RECORD_OUTPUT_FILE="$preprod_summary" \
  PISCES_RELEASE_PACKAGE_REPORT_FILE="$release_report" \
  PISCES_CAPACITY_BASELINE_MANIFEST_FILE="$(resolve_path "$PISCES_CAPACITY_BASELINE_MANIFEST_FILE")" \
  PISCES_EVENT_REPLAY_AUDIT_SUMMARY_FILE="$(resolve_path "$PISCES_EVENT_REPLAY_AUDIT_SUMMARY_FILE")" \
  PISCES_PREPROD_REQUIRE_STRICT_PACKAGE_CI=true \
  PISCES_PREPROD_REQUIRE_EVIDENCE_ARCHIVE=false \
  PISCES_PREPROD_REQUIRE_CAPACITY_BASELINE=true \
  PISCES_PREPROD_REQUIRE_REDIS_FAULT=true \
  PISCES_PREPROD_REQUIRE_OBSERVABILITY=true \
  PISCES_PREPROD_REQUIRE_EVENT_REPLAY=true \
  bash "$PISCES_REPO_ROOT/scripts/runtime-plane-preprod-drill-record-check.sh"

  log "Archiving local release evidence"
  PISCES_RELEASE_ID="$PISCES_RELEASE_ID" \
  PISCES_ENVIRONMENT=local \
  PISCES_RELEASE_EVIDENCE_ARCHIVE_DIR="$archive_root" \
  PISCES_RELEASE_PACKAGE_REPORT_FILE="$release_report" \
  PISCES_PREPROD_DRILL_RECORD_FILE="$(resolve_path "$PISCES_PREPROD_DRILL_RECORD_FILE")" \
  PISCES_CAPACITY_BASELINE_MANIFEST_FILE="$(resolve_path "$PISCES_CAPACITY_BASELINE_MANIFEST_FILE")" \
  PISCES_REDIS_FAULT_RECORD_FILE="$(resolve_path "$PISCES_REDIS_FAULT_RECORD_FILE")" \
  PISCES_EVENT_REPLAY_AUDIT_SUMMARY_FILE="$(resolve_path "$PISCES_EVENT_REPLAY_AUDIT_SUMMARY_FILE")" \
  PISCES_EXPECTED_GIT_SHA="$expected_git_sha" \
  PISCES_RELEASE_EVIDENCE_REQUIRE_CLEAN_GIT="$require_clean_git" \
  bash "$PISCES_REPO_ROOT/scripts/runtime-plane-release-evidence-archive.sh"

  manifest_file="$(find "$archive_root" -mindepth 2 -maxdepth 2 -name manifest.json -print | sort | tail -n 1)"
  [[ -n "$manifest_file" && -f "$manifest_file" ]] || die "Release evidence manifest was not created"

  log "Reviewing local post-release SLO evidence"
  PISCES_RELEASE_EVIDENCE_MANIFEST_FILE="$manifest_file" \
  PISCES_POST_RELEASE_METRICS_FILE="$(resolve_path "$PISCES_POST_RELEASE_METRICS_FILE")" \
  PISCES_REDIS_FAULT_RECORD_FILE="$(resolve_path "$PISCES_REDIS_FAULT_RECORD_FILE")" \
  PISCES_POST_RELEASE_SLO_OUTPUT_FILE="$slo_summary" \
  bash "$PISCES_REPO_ROOT/scripts/runtime-plane-post-release-slo-review.sh"

  log "Making local full-stage rollout decision"
  PISCES_RELEASE_STAGE=full \
  PISCES_RELEASE_EVIDENCE_MANIFEST_FILE="$manifest_file" \
  PISCES_POST_RELEASE_SLO_SUMMARY_FILE="$slo_summary" \
  PISCES_EXPERIMENT_IMPACT_SUMMARY_FILE="$(resolve_path "$PISCES_EXPERIMENT_IMPACT_SUMMARY_FILE")" \
  PISCES_ROLLOUT_ACCEPTANCE_RECORD_FILE="$(resolve_path "$PISCES_ROLLOUT_ACCEPTANCE_RECORD_FILE")" \
  PISCES_ROLLOUT_DECISION_OUTPUT_FILE="$rollout_summary" \
  PISCES_ROLLOUT_REQUIRE_TRACE_SAMPLING=true \
  PISCES_ROLLOUT_REQUIRE_CLEAN_GIT="$require_clean_git" \
  bash "$PISCES_REPO_ROOT/scripts/runtime-plane-staged-rollout-decision.sh"

  log "Checking local production acceptance"
  PISCES_RELEASE_EVIDENCE_MANIFEST_FILE="$manifest_file" \
  PISCES_POST_RELEASE_SLO_SUMMARY_FILE="$slo_summary" \
  PISCES_EXPERIMENT_IMPACT_SUMMARY_FILE="$(resolve_path "$PISCES_EXPERIMENT_IMPACT_SUMMARY_FILE")" \
  PISCES_STAGED_ROLLOUT_DECISION_SUMMARY_FILE="$rollout_summary" \
  PISCES_PRODUCTION_ACCEPTANCE_RECORD_FILE="$(resolve_path "$PISCES_PRODUCTION_ACCEPTANCE_RECORD_FILE")" \
  PISCES_PRODUCTION_ACCEPTANCE_OUTPUT_FILE="$acceptance_summary" \
  PISCES_PRODUCTION_ACCEPTANCE_REQUIRE_CLEAN_GIT="$require_clean_git" \
  PISCES_PRODUCTION_ACCEPTANCE_REQUIRE_EVENT_REPLAY=true \
  PISCES_PRODUCTION_ACCEPTANCE_REQUIRE_TRACE=true \
  bash "$PISCES_REPO_ROOT/scripts/runtime-plane-production-acceptance-check.sh"

  log "Running final local production infrastructure closeout"
  PISCES_COMPLETION_TARGET_ENVIRONMENT=local \
  PISCES_COMPLETION_RELEASE_PACKAGE_REPORT_FILE="$release_report" \
  PISCES_COMPLETION_PREPROD_RECORD_CHECK_SUMMARY_FILE="$preprod_summary" \
  PISCES_COMPLETION_RELEASE_EVIDENCE_MANIFEST_FILE="$manifest_file" \
  PISCES_COMPLETION_PRODUCTION_ACCEPTANCE_SUMMARY_FILE="$acceptance_summary" \
  PISCES_COMPLETION_SCREENSHOT_DIR="$PISCES_COMPLETION_SCREENSHOT_DIR" \
  PISCES_COMPLETION_REQUIRE_CLEAN_GIT="$require_clean_git" \
  PISCES_PRODUCTION_CLOSEOUT_DIR="$final_dir" \
  bash "$PISCES_REPO_ROOT/scripts/production-infrastructure-closeout.sh"

  log "Local production infrastructure closeout complete: $final_dir/closeout-report.md"
}

main "$@"
