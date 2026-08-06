#!/usr/bin/env bash

set -euo pipefail

require_command() {
  command -v "$1" >/dev/null 2>&1 || {
    printf 'Missing command: %s\n' "$1" >&2
    exit 1
  }
}

require_command python3

smoke_root="${PISCES_LOCAL_STACK_SMOKE_ROOT:-target/pisces-production-infrastructure-local-stack-smoke}"
run_id="$(date -u '+%Y%m%dT%H%M%SZ')-$$"
workspace="$smoke_root/dependency-stack-smoke-$run_id"
stack_env_file="$workspace/config/pisces-local-stack.env"
local_env_file="$workspace/config/missing-pisces-local.env"
stack_summary_file="$workspace/stack-summary.json"
dependency_summary_file="$workspace/dependency-summary.json"
schema_summary_file="$workspace/schema-summary.json"

mkdir -p "$(dirname "$local_env_file")"
: >"$local_env_file"

PISCES_LOCAL_STACK_DRY_RUN=true \
PISCES_LOCAL_STACK_ENV_FILE="$stack_env_file" \
PISCES_LOCAL_STACK_OUTPUT_FILE="$stack_summary_file" \
PISCES_LOCAL_MYSQL_PORT=13330 \
PISCES_LOCAL_REDIS_PORT=16379 \
PISCES_LOCAL_ZOOKEEPER_PORT=12181 \
bash scripts/production-infrastructure-local-dependency-stack.sh up >/dev/null

python3 - "$stack_summary_file" "$stack_env_file" <<'PY'
import json
import sys
from pathlib import Path

summary_file = Path(sys.argv[1])
stack_env_file = Path(sys.argv[2])
summary = json.loads(summary_file.read_text(encoding="utf-8"))
env_text = stack_env_file.read_text(encoding="utf-8")

if summary.get("summaryType") != "pisces-production-infrastructure-local-dependency-stack":
    raise SystemExit("stack summary type mismatch")
if summary.get("status") != "PLAN_ONLY":
    raise SystemExit(f"expected dry-run PLAN_ONLY: {summary.get('status')}")
if summary.get("action") != "up":
    raise SystemExit(f"expected action up: {summary.get('action')}")
if summary.get("ports") != {"mysql": 13330, "redis": 16379, "zookeeper": 12181}:
    raise SystemExit(f"unexpected stack ports: {summary.get('ports')}")
if summary.get("portOverrides") != {"mysql": True, "redis": True, "zookeeper": True}:
    raise SystemExit(f"unexpected port override flags: {summary.get('portOverrides')}")
if not stack_env_file.is_file():
    raise SystemExit("stack env file was not created")
for marker in (
    "MYSQL_URL=\"jdbc:mysql://localhost:13330/pisces",
    "SPRING_DATA_REDIS_PORT=\"16379\"",
    "SPRING_DATA_REDIS_TIMEOUT=\"300ms\"",
    "PISCES_ZOOKEEPER_CONNECT_STRING=\"localhost:12181\"",
):
    if marker not in env_text:
        raise SystemExit(f"missing stack env marker: {marker}")
if "production-infrastructure-local-mysql-schema-apply.sh" not in "\n".join(summary.get("nextCommands") or []):
    raise SystemExit("stack summary should point to schema apply")
PY

(
  unset MYSQL_URL MYSQL_USERNAME MYSQL_PASSWORD SPRING_DATA_REDIS_HOST SPRING_DATA_REDIS_PORT PISCES_ZOOKEEPER_CONNECT_STRING
  PISCES_LOCAL_ENV_FILE="$local_env_file" \
  PISCES_LOCAL_STACK_ENV_FILE="$stack_env_file" \
  PISCES_LOCAL_DEPENDENCY_OUTPUT_FILE="$dependency_summary_file" \
  PISCES_LOCAL_DEPENDENCY_CHECK_MYSQL=false \
  PISCES_LOCAL_DEPENDENCY_CHECK_REDIS=false \
  PISCES_LOCAL_DEPENDENCY_CHECK_ZOOKEEPER=false \
  bash scripts/production-infrastructure-local-dependency-check.sh >/dev/null
)

python3 - "$dependency_summary_file" <<'PY'
import json
import sys
from pathlib import Path

summary = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
if summary.get("status") != "READY_FOR_LOCAL_SERVICE_START":
    raise SystemExit(f"disabled checks should be ready: {summary.get('status')}")
if summary.get("stackEnvFilePresent") is not True:
    raise SystemExit("dependency check should consume stack env file")
if summary.get("mysql", {}).get("port") != 13330:
    raise SystemExit(f"dependency check did not use stack MySQL port: {summary.get('mysql')}")
if summary.get("redis", {}).get("port") != 16379:
    raise SystemExit(f"dependency check did not use stack Redis port: {summary.get('redis')}")
PY

(
  unset MYSQL_URL MYSQL_USERNAME MYSQL_PASSWORD SPRING_DATA_REDIS_HOST SPRING_DATA_REDIS_PORT PISCES_ZOOKEEPER_CONNECT_STRING
  PISCES_LOCAL_ENV_FILE="$local_env_file" \
  PISCES_LOCAL_STACK_ENV_FILE="$stack_env_file" \
  PISCES_LOCAL_MYSQL_SCHEMA_OUTPUT_FILE="$schema_summary_file" \
  PISCES_LOCAL_MYSQL_SCHEMA_DRY_RUN=true \
  bash scripts/production-infrastructure-local-mysql-schema-apply.sh >/dev/null
)

python3 - "$schema_summary_file" <<'PY'
import json
import sys
from pathlib import Path

summary = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
if summary.get("status") != "PLAN_ONLY":
    raise SystemExit(f"schema dry-run should be PLAN_ONLY: {summary.get('status')}")
if summary.get("stackEnvFilePresent") is not True:
    raise SystemExit("schema apply should consume stack env file")
if summary.get("mysql", {}).get("port") != 13330:
    raise SystemExit(f"schema apply did not use stack MySQL port: {summary.get('mysql')}")
if len(summary.get("baseFiles") or []) < 1:
    raise SystemExit("schema apply should still plan base files")
PY

printf 'production infrastructure local dependency stack smoke test passed\n'
