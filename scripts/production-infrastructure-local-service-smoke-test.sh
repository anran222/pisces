#!/usr/bin/env bash

set -euo pipefail

require_command() {
  command -v "$1" >/dev/null 2>&1 || {
    printf 'Missing command: %s\n' "$1" >&2
    exit 1
  }
}

require_command python3

smoke_root="${PISCES_LOCAL_SERVICE_SMOKE_ROOT:-target/pisces-production-infrastructure-local-service-smoke}"
run_id="$(date -u '+%Y%m%dT%H%M%SZ')-$$"
workspace="$smoke_root/local-service-smoke-$run_id"
env_file="$workspace/config/pisces-local.env"
stack_env_file="$workspace/config/pisces-local-stack.env"
placeholder_summary="$workspace/placeholder-summary.json"
plan_summary="$workspace/plan-summary.json"
status_summary="$workspace/status-summary.json"
pid_file="$workspace/backend.pid"
log_file="$workspace/backend.log"
launch_label="com.pisces.local-service.smoke.$run_id"

mkdir -p "$(dirname "$env_file")"
cat >"$env_file" <<'ENV'
export TONGYI_API_KEY="<local-qianwen-api-key>"
export PISCES_API_KEY_SPECS="runtime-key|shop-app|sdk|runtime,ops-key|shop-app|ops|management+analysis,admin-key|platform|admin|admin"
export MYSQL_URL="jdbc:mysql://127.0.0.1:23306/user_selected_pisces?useUnicode=true"
export MYSQL_USERNAME="user_selected"
export MYSQL_PASSWORD="user-selected-secret"
export SPRING_DATA_REDIS_HOST="127.0.0.2"
export SPRING_DATA_REDIS_PORT="26379"
export PISCES_INSTANCE_URLS="http://127.0.0.1:19990/api"
ENV

cat >"$stack_env_file" <<'ENV'
export MYSQL_URL="jdbc:mysql://localhost:13330/pisces?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
export MYSQL_USERNAME="root"
export MYSQL_PASSWORD=""
export SPRING_DATA_REDIS_HOST="localhost"
export SPRING_DATA_REDIS_PORT="16379"
export PISCES_ZOOKEEPER_CONNECT_STRING="localhost:12181"
ENV

set +e
PISCES_LOCAL_ENV_FILE="$env_file" \
PISCES_LOCAL_STACK_ENV_FILE="$stack_env_file" \
PISCES_LOCAL_SERVICE_OUTPUT_FILE="$placeholder_summary" \
PISCES_LOCAL_SERVICE_PID_FILE="$pid_file" \
PISCES_LOCAL_SERVICE_LOG_FILE="$log_file" \
PISCES_LOCAL_SERVICE_LAUNCH_LABEL="$launch_label" \
PISCES_LOCAL_SERVICE_DRY_RUN=true \
bash scripts/production-infrastructure-local-service.sh start >/dev/null
placeholder_status=$?
set -e

if [[ "$placeholder_status" -eq 0 ]]; then
  printf 'local service start should reject placeholder Qianwen key\n' >&2
  exit 1
fi

python3 - "$placeholder_summary" <<'PY'
import json
import sys
from pathlib import Path

summary = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
if summary.get("summaryType") != "pisces-production-infrastructure-local-service":
    raise SystemExit("service summary type mismatch")
if summary.get("status") != "NEEDS_QIANWEN_API_KEY":
    raise SystemExit(f"expected NEEDS_QIANWEN_API_KEY: {summary.get('status')}")
if summary.get("apiKeyStatus") != "placeholder":
    raise SystemExit(f"expected placeholder key status: {summary.get('apiKeyStatus')}")
if "replace only TONGYI_API_KEY" not in "\n".join(summary.get("nextCommands") or []):
    raise SystemExit("service summary should guide single-key replacement")
PY

python3 - "$env_file" <<'PY'
import sys
from pathlib import Path

env_file = Path(sys.argv[1])
text = env_file.read_text(encoding="utf-8")
env_file.write_text(text.replace("<local-qianwen-api-key>", "local-qianwen-key-for-service-smoke"), encoding="utf-8")
PY

PISCES_LOCAL_ENV_FILE="$env_file" \
PISCES_LOCAL_STACK_ENV_FILE="$stack_env_file" \
PISCES_LOCAL_SERVICE_OUTPUT_FILE="$plan_summary" \
PISCES_LOCAL_SERVICE_PID_FILE="$pid_file" \
PISCES_LOCAL_SERVICE_LOG_FILE="$log_file" \
PISCES_LOCAL_SERVICE_LAUNCH_LABEL="$launch_label" \
PISCES_LOCAL_SERVICE_DRY_RUN=true \
bash scripts/production-infrastructure-local-service.sh start >/dev/null

python3 - "$plan_summary" <<'PY'
import json
import sys
from pathlib import Path

summary_file = Path(sys.argv[1])
text = summary_file.read_text(encoding="utf-8")
summary = json.loads(text)
if summary.get("status") != "PLAN_ONLY":
    raise SystemExit(f"expected PLAN_ONLY: {summary.get('status')}")
if summary.get("apiKeyStatus") != "configured":
    raise SystemExit(f"expected configured key status: {summary.get('apiKeyStatus')}")
if "local-qianwen-key-for-service-smoke" in text:
    raise SystemExit("service summary must not leak Qianwen key")
if summary.get("startCommand") != "java -jar pisces-api/target/pisces-api-1.0.0.jar":
    raise SystemExit("service summary should record backend start command")
if summary.get("prepareCommand") != "mvn -pl pisces-api -am -DskipTests package":
    raise SystemExit("service summary should record backend package command")
if summary.get("installModules") is not True:
    raise SystemExit("service summary should build current executable jar by default")
if summary.get("appJar") != "pisces-api/target/pisces-api-1.0.0.jar":
    raise SystemExit("service summary should record backend jar path")
if not summary.get("stackEnvFilePresent"):
    raise SystemExit("service summary should record stack env file")
configuration = summary.get("runtimeConfiguration") or {}
if configuration.get("loadOrder") != ["stackEnv", "localEnv"]:
    raise SystemExit(f"unexpected env load order: {configuration}")
mysql = configuration.get("mysql") or {}
if mysql != {"host": "127.0.0.1", "port": 23306, "database": "user_selected_pisces"}:
    raise SystemExit(f"local env must override stack MySQL defaults: {mysql}")
redis = configuration.get("redis") or {}
if redis != {"host": "127.0.0.2", "port": 26379}:
    raise SystemExit(f"local env must override stack Redis defaults: {redis}")
if len(configuration.get("fingerprint") or "") != 16:
    raise SystemExit("service summary should include a stable sanitized configuration fingerprint")
for secret in ("local-qianwen-key-for-service-smoke", "user-selected-secret"):
    if secret in text:
        raise SystemExit("service summary must not leak local secrets")
PY

PISCES_LOCAL_ENV_FILE="$env_file" \
PISCES_LOCAL_STACK_ENV_FILE="$stack_env_file" \
PISCES_LOCAL_SERVICE_OUTPUT_FILE="$status_summary" \
PISCES_LOCAL_SERVICE_PID_FILE="$pid_file" \
PISCES_LOCAL_SERVICE_LOG_FILE="$log_file" \
PISCES_LOCAL_SERVICE_LAUNCH_LABEL="$launch_label" \
bash scripts/production-infrastructure-local-service.sh status >/dev/null

python3 - "$status_summary" <<'PY'
import json
import sys
from pathlib import Path

summary = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
if summary.get("status") != "STATUS_RECORDED":
    raise SystemExit(f"expected STATUS_RECORDED: {summary.get('status')}")
if summary.get("processStatus") != "not_running":
    raise SystemExit(f"expected not_running: {summary.get('processStatus')}")
PY

printf 'production infrastructure local service smoke test passed\n'
