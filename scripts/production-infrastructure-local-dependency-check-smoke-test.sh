#!/usr/bin/env bash

set -euo pipefail

require_command() {
  command -v "$1" >/dev/null 2>&1 || {
    printf 'Missing command: %s\n' "$1" >&2
    exit 1
  }
}

require_command python3

smoke_root="${PISCES_LOCAL_DEPENDENCY_SMOKE_ROOT:-target/pisces-production-infrastructure-local-dependency-check-smoke}"
run_id="$(date -u '+%Y%m%dT%H%M%SZ')-$$"
workspace="$smoke_root/dependency-check-smoke-$run_id"
summary_file="$workspace/dependency-summary.json"

PISCES_LOCAL_DEPENDENCY_OUTPUT_FILE="$summary_file" \
PISCES_LOCAL_DEPENDENCY_CHECK_MYSQL=false \
PISCES_LOCAL_DEPENDENCY_CHECK_REDIS=false \
PISCES_LOCAL_DEPENDENCY_CHECK_ZOOKEEPER=false \
bash scripts/production-infrastructure-local-dependency-check.sh >/dev/null

python3 - "$summary_file" <<'PY'
import json
import sys
from pathlib import Path

summary_file = Path(sys.argv[1])
summary = json.loads(summary_file.read_text(encoding="utf-8"))

if summary.get("summaryType") != "pisces-production-infrastructure-local-dependency-check":
    raise SystemExit("dependency summary type mismatch")
if summary.get("status") != "READY_FOR_LOCAL_SERVICE_START":
    raise SystemExit(f"disabled dependency checks should be ready: {summary.get('status')}")
checks = {check["name"]: check for check in summary.get("checks", [])}
for name in ("mysql dependency check", "redis dependency check", "zookeeper dependency check"):
    if checks.get(name, {}).get("actual") != "skipped":
        raise SystemExit(f"{name} should be skipped")
if "production-infrastructure-local-service.sh start" not in "\n".join(summary.get("nextCommands") or []):
    raise SystemExit("dependency summary should include local service start command")
PY

strict_summary_file="$workspace/dependency-strict-summary.json"
set +e
MYSQL_URL="jdbc:mysql://127.0.0.1:1/pisces" \
PISCES_LOCAL_DEPENDENCY_OUTPUT_FILE="$strict_summary_file" \
PISCES_LOCAL_DEPENDENCY_CHECK_REDIS=false \
PISCES_LOCAL_DEPENDENCY_CHECK_ZOOKEEPER=false \
PISCES_LOCAL_DEPENDENCY_STRICT=true \
bash scripts/production-infrastructure-local-dependency-check.sh >/dev/null
strict_status=$?
set -e

if [[ "$strict_status" -eq 0 ]]; then
  printf 'strict dependency check should reject unreachable MySQL\n' >&2
  exit 1
fi

python3 - "$strict_summary_file" <<'PY'
import json
import sys
from pathlib import Path

summary_file = Path(sys.argv[1])
summary = json.loads(summary_file.read_text(encoding="utf-8"))

if summary.get("status") != "NEEDS_LOCAL_DEPENDENCIES":
    raise SystemExit(f"expected dependency hold status: {summary.get('status')}")
holds = [check for check in summary.get("checks", []) if check.get("status") == "HOLD"]
if not any(check.get("name") == "mysql port reachable" for check in holds):
    raise SystemExit("unreachable MySQL should produce mysql port HOLD")
PY

printf 'production infrastructure local dependency check smoke test passed\n'
