#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  PISCES_BASELINE_INPUT_FILE=target/pisces-runtime-capacity-baseline-20260720113000.jsonl \
    scripts/runtime-plane-archive-baseline.sh

Environment:
  PISCES_BASELINE_INPUT_FILE     Required JSONL file from runtime-plane-capacity-baseline.sh.
  PISCES_BASELINE_ARCHIVE_DIR    Archive root. Default: target/pisces-runtime-baseline-archive
  PISCES_ENVIRONMENT             Environment name. Default: local
  PISCES_EXPERIMENT_ID           Experiment ID used for the baseline. Default: unknown-experiment
  PISCES_RELEASE_ID              Release or change ID. Default: manual
  PISCES_INSTANCE_URLS           Comma separated instance URLs used by the baseline.
  PISCES_OPERATOR                Operator name. Default: current OS user.
  PISCES_GIT_SHA                 Git SHA to record. Default: git rev-parse HEAD when available.
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

sanitize_path_part() {
  printf '%s' "$1" | tr -c '[:alnum:]._-' '-'
}

resolve_git_sha() {
  if [[ -n "${PISCES_GIT_SHA:-}" ]]; then
    printf '%s' "$PISCES_GIT_SHA"
    return
  fi
  if command -v git >/dev/null 2>&1 && git rev-parse --show-toplevel >/dev/null 2>&1; then
    git rev-parse HEAD
    return
  fi
  printf 'unknown'
}

resolve_git_dirty() {
  if command -v git >/dev/null 2>&1 && git rev-parse --show-toplevel >/dev/null 2>&1; then
    if [[ -n "$(git status --porcelain)" ]]; then
      printf 'true'
      return
    fi
    printf 'false'
    return
  fi
  printf 'unknown'
}

main() {
  if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
    usage
    exit 0
  fi

  require_command python3

  PISCES_BASELINE_INPUT_FILE="${PISCES_BASELINE_INPUT_FILE:-}"
  PISCES_BASELINE_ARCHIVE_DIR="${PISCES_BASELINE_ARCHIVE_DIR:-target/pisces-runtime-baseline-archive}"
  PISCES_ENVIRONMENT="${PISCES_ENVIRONMENT:-local}"
  PISCES_EXPERIMENT_ID="${PISCES_EXPERIMENT_ID:-unknown-experiment}"
  PISCES_RELEASE_ID="${PISCES_RELEASE_ID:-manual}"
  PISCES_INSTANCE_URLS="${PISCES_INSTANCE_URLS:-}"
  PISCES_OPERATOR="${PISCES_OPERATOR:-${USER:-unknown}}"
  PISCES_GIT_SHA="$(resolve_git_sha)"
  PISCES_GIT_DIRTY="$(resolve_git_dirty)"

  [[ -n "$PISCES_BASELINE_INPUT_FILE" ]] || die "PISCES_BASELINE_INPUT_FILE is required"
  [[ -f "$PISCES_BASELINE_INPUT_FILE" ]] || die "Baseline input file not found: $PISCES_BASELINE_INPUT_FILE"

  export PISCES_BASELINE_INPUT_FILE
  export PISCES_BASELINE_ARCHIVE_DIR
  export PISCES_ENVIRONMENT
  export PISCES_EXPERIMENT_ID
  export PISCES_RELEASE_ID
  export PISCES_INSTANCE_URLS
  export PISCES_OPERATOR
  export PISCES_GIT_SHA
  export PISCES_GIT_DIRTY

  local archived_at archive_name archive_dir
  archived_at="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  archive_name="$(date -u '+%Y%m%dT%H%M%SZ')-$(sanitize_path_part "$PISCES_ENVIRONMENT")-$(sanitize_path_part "$PISCES_EXPERIMENT_ID")-$(sanitize_path_part "$PISCES_RELEASE_ID")"
  archive_dir="${PISCES_BASELINE_ARCHIVE_DIR%/}/${archive_name}"
  mkdir -p "$archive_dir"
  cp "$PISCES_BASELINE_INPUT_FILE" "$archive_dir/capacity-baseline.jsonl"

  python3 - "$archive_dir/manifest.json" "$archived_at" <<'PY'
import json
import os
import sys

manifest_file = sys.argv[1]
archived_at = sys.argv[2]
input_file = os.environ["PISCES_BASELINE_INPUT_FILE"]
archive_jsonl = os.path.join(os.path.dirname(manifest_file), "capacity-baseline.jsonl")

steps = []
with open(archive_jsonl, encoding="utf-8") as source:
    for line_number, line in enumerate(source, start=1):
        stripped = line.strip()
        if not stripped:
            continue
        try:
            payload = json.loads(stripped)
        except json.JSONDecodeError as exc:
            print(f"Invalid JSONL at line {line_number}: {exc}", file=sys.stderr)
            sys.exit(1)
        for field in ("step", "total", "ok", "failed", "errorRate", "latencyMs"):
            if field not in payload:
                print(f"Missing field {field} at line {line_number}", file=sys.stderr)
                sys.exit(1)
        latency = payload.get("latencyMs") or {}
        for field in ("p50", "p95", "p99"):
            if field not in latency:
                print(f"Missing latencyMs.{field} at line {line_number}", file=sys.stderr)
                sys.exit(1)
        steps.append(payload)

if not steps:
    print("Baseline input has no valid steps", file=sys.stderr)
    sys.exit(1)

manifest = {
    "archivedAt": archived_at,
    "sourceFile": input_file,
    "environment": os.environ["PISCES_ENVIRONMENT"],
    "experimentId": os.environ["PISCES_EXPERIMENT_ID"],
    "releaseId": os.environ["PISCES_RELEASE_ID"],
    "instanceUrls": [item.strip() for item in os.environ["PISCES_INSTANCE_URLS"].split(",") if item.strip()],
    "operator": os.environ["PISCES_OPERATOR"],
    "gitSha": os.environ["PISCES_GIT_SHA"],
    "gitDirty": os.environ["PISCES_GIT_DIRTY"],
    "stepCount": len(steps),
    "maxErrorRate": max(float(step["errorRate"]) for step in steps),
    "maxP95Ms": max(float(step["latencyMs"]["p95"]) for step in steps),
    "maxP99Ms": max(float(step["latencyMs"]["p99"]) for step in steps),
    "steps": [
        {
            "step": step["step"],
            "requests": step.get("requests"),
            "concurrency": step.get("concurrency"),
            "total": step["total"],
            "ok": step["ok"],
            "failed": step["failed"],
            "errorRate": step["errorRate"],
            "latencyMs": step["latencyMs"],
            "versions": step.get("versions", {}),
        }
        for step in steps
    ],
}

with open(manifest_file, "w", encoding="utf-8") as target:
    json.dump(manifest, target, ensure_ascii=False, indent=2, sort_keys=True)
    target.write("\n")
PY

  log "Baseline archive written to ${archive_dir}"
}

main "$@"
