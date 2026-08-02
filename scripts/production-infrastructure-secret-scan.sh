#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  scripts/production-infrastructure-secret-scan.sh

Environment:
  PISCES_REPO_ROOT                       Repository root. Default: inferred from this script.
  PISCES_SECRET_SCAN_OUTPUT_FILE         JSON output. Default: target/pisces-production-infrastructure-secret-scan/summary.json.

The scanner allows environment variable names and placeholders such as
${TONGYI_API_KEY:}, <local-qianwen-api-key>, runtime-key, ops-key, and test-api-key.
It fails on likely committed real API keys or literal TONGYI/DASHSCOPE/QIANWEN key
assignments. Ignored local env files such as config/*.env are skipped because
they are the supported place for machine-local secrets.
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

resolve_output_file() {
  case "$1" in
    /*)
      printf '%s' "$1"
      ;;
    *)
      printf '%s/%s' "$PISCES_REPO_ROOT" "$1"
      ;;
  esac
}

main() {
  if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
    usage
    exit 0
  fi

  command -v python3 >/dev/null 2>&1 || die "Missing command: python3"

  PISCES_REPO_ROOT="$(resolve_repo_root)"
  PISCES_SECRET_SCAN_OUTPUT_FILE="${PISCES_SECRET_SCAN_OUTPUT_FILE:-target/pisces-production-infrastructure-secret-scan/summary.json}"

  local output_file
  output_file="$(resolve_output_file "$PISCES_SECRET_SCAN_OUTPUT_FILE")"
  mkdir -p "$(dirname "$output_file")"

  export PISCES_REPO_ROOT

  python3 - "$output_file" <<'PY'
import json
import os
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

output_file = Path(sys.argv[1])
repo_root = Path(os.environ["PISCES_REPO_ROOT"])

skip_dirs = {
    ".git",
    ".idea",
    ".codex",
    ".mvn",
    "target",
    "node_modules",
    "dist",
    "build",
    ".worktrees",
}
skip_suffixes = {
    ".class",
    ".jar",
    ".png",
    ".jpg",
    ".jpeg",
    ".gif",
    ".webp",
    ".ico",
    ".pdf",
    ".zip",
    ".gz",
    ".tar",
    ".lock",
}
allowed_literal_values = {
    "",
    "runtime-key",
    "ops-key",
    "legacy-key",
    "scope-smoke-key",
    "segment-smoke-key",
    "test-api-key",
    "<runtime-key>",
    "<management-key>",
    "<analysis-or-management-key>",
    "<runtime-key-from-vault>",
    "<management-key-from-vault>",
    "<local-qianwen-api-key>",
    "<your-dashscope-api-key>",
}

line_patterns = [
    (
        "dashscope_or_openai_style_secret",
        re.compile(r"(?<![A-Za-z0-9_])sk-[A-Za-z0-9][A-Za-z0-9_-]{20,}"),
    ),
    (
        "aliyun_access_key_id",
        re.compile(r"(?<![A-Za-z0-9])LTAI[A-Za-z0-9]{12,}"),
    ),
]

assignment_pattern = re.compile(
    r"(?:^|\bexport\s+)(?P<name>TONGYI_API_KEY|DASHSCOPE_API_KEY|QIANWEN_API_KEY)\s*=\s*(?P<value>[^#\n\r]+)"
)
structured_key_pattern = re.compile(
    r"^\s*[\"']?(?P<name>apiKey|api-key|apikey|accessKeyId|accessKeySecret)[\"']?\s*:\s*(?P<value>[^#\n\r]+)"
)


def now_iso():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


skipped_local_env_files = []


def is_local_env_file(relative_path):
    name = relative_path.name
    if name == ".env.example" or name.endswith(".env.example"):
        return False
    if len(relative_path.parts) == 2 and relative_path.parts[0] == "config" and name.endswith(".env"):
        return True
    if name == ".env" or name.startswith(".env.") or name.endswith(".local.env"):
        return True
    return False


def should_skip(path):
    relative_path = path.relative_to(repo_root)
    if is_local_env_file(relative_path):
        skipped_local_env_files.append(str(relative_path))
        return True
    relative_parts = relative_path.parts
    if any(part in skip_dirs for part in relative_parts):
        return True
    if path.suffix.lower() in skip_suffixes:
        return True
    try:
        return path.stat().st_size > 2 * 1024 * 1024
    except OSError:
        return True


def normalize_value(value):
    return value.strip().strip('"').strip("'").strip()


def is_env_or_placeholder(value):
    normalized = normalize_value(value)
    if normalized in allowed_literal_values:
        return True
    if normalized.startswith("${") and "}" in normalized:
        return True
    if normalized.startswith("<") and normalized.endswith(">"):
        return True
    if normalized.startswith("process.env."):
        return True
    if normalized.startswith("System.getenv("):
        return True
    if normalized.lower().startswith("todo"):
        return True
    return False


def add_finding(findings, path, line_number, rule, snippet):
    findings.append({
        "file": str(path.relative_to(repo_root)),
        "line": line_number,
        "rule": rule,
        "snippet": snippet.strip()[:240],
    })


findings = []
scanned_files = 0

for path in repo_root.rglob("*"):
    if not path.is_file() or should_skip(path):
        continue
    try:
        text = path.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        continue
    except OSError:
        continue

    scanned_files += 1
    for line_number, line in enumerate(text.splitlines(), 1):
        for rule_name, pattern in line_patterns:
            if pattern.search(line):
                add_finding(findings, path, line_number, rule_name, line)

        for match in assignment_pattern.finditer(line):
            value = match.group("value")
            if not is_env_or_placeholder(value):
                add_finding(findings, path, line_number, "literal_qianwen_key_assignment", line)

        for match in structured_key_pattern.finditer(line):
            value = match.group("value")
            if not is_env_or_placeholder(value):
                normalized = normalize_value(value).rstrip(",")
                if len(normalized) >= 16:
                    add_finding(findings, path, line_number, "literal_structured_secret_value", line)

status = "PASS" if not findings else "FAIL"
summary = {
    "summaryType": "pisces-production-infrastructure-secret-scan",
    "summaryVersion": 1,
    "generatedAt": now_iso(),
    "status": status,
    "repoRoot": str(repo_root),
    "scannedFiles": scanned_files,
    "skippedLocalEnvFiles": skipped_local_env_files,
    "findingCount": len(findings),
    "findings": findings,
}

output_file.write_text(json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
print(f"Production infrastructure secret scan written: {output_file} status={status} findings={len(findings)}", file=sys.stderr)
sys.exit(0 if status == "PASS" else 2)
PY
}

main "$@"
