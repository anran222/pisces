#!/usr/bin/env bash

set -euo pipefail

require_command() {
  command -v "$1" >/dev/null 2>&1 || {
    printf 'Missing command: %s\n' "$1" >&2
    exit 1
  }
}

require_command python3

smoke_root="${PISCES_LOCAL_PREKEY_SMOKE_ROOT:-target/pisces-production-infrastructure-local-prekey-smoke}"
run_id="$(date -u '+%Y%m%dT%H%M%SZ')-$$"
workspace="$smoke_root/local-prekey-smoke-$run_id"
env_file="$workspace/config/pisces-local.env"
stack_env_file="$workspace/config/pisces-local-stack.env"
summary_file="$workspace/prekey-summary.json"

mkdir -p "$(dirname "$env_file")"
cat >"$env_file" <<'ENV'
export TONGYI_API_KEY="<local-qianwen-api-key>"
export TONGYI_MODEL="qwen3.7-max"
export TONGYI_API_MODE="dashscope"
export TONGYI_FALLBACK_MODEL="qwen3.7-max"
export TONGYI_FALLBACK_API_MODE="dashscope"
export PISCES_API_KEY_SPECS="runtime-key|shop-app|sdk|runtime,ops-key|shop-app|ops|management+analysis,admin-key|platform|admin|admin"
ENV

cat >"$stack_env_file" <<'ENV'
export MYSQL_URL="jdbc:mysql://localhost:13330/pisces?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
export MYSQL_USERNAME="root"
export MYSQL_PASSWORD=""
export SPRING_DATA_REDIS_HOST="localhost"
export SPRING_DATA_REDIS_PORT="16379"
export PISCES_ZOOKEEPER_CONNECT_STRING="localhost:12181"
export PISCES_LOCAL_STACK_PROJECT_NAME="pisces-local-prekey-smoke"
export PISCES_REDIS_DOCKER_CONTAINER="pisces-local-prekey-smoke-redis-1"
ENV

(
  unset PISCES_REDIS_DOCKER_CONTAINER PISCES_FAULT_CONFIRM PISCES_LOCAL_FINALIZE_REDIS_FAULT_MODE
  PISCES_LOCAL_ENV_FILE="$env_file" \
  PISCES_LOCAL_STACK_ENV_FILE="$stack_env_file" \
  PISCES_LOCAL_PREKEY_OUTPUT_FILE="$summary_file" \
  bash scripts/production-infrastructure-local-prekey-check.sh >/dev/null
)

python3 - "$summary_file" <<'PY'
import json
import sys
from pathlib import Path

summary_file = Path(sys.argv[1])
text = summary_file.read_text(encoding="utf-8")
summary = json.loads(text)

if summary.get("summaryType") != "pisces-production-infrastructure-local-prekey":
    raise SystemExit("prekey summary type mismatch")
if summary.get("status") != "READY_FOR_API_KEY":
    raise SystemExit(f"expected READY_FOR_API_KEY: {summary.get('status')}")
if summary.get("apiKeyStatus") != "placeholder":
    raise SystemExit(f"expected placeholder key status: {summary.get('apiKeyStatus')}")
local_env = summary.get("localEnv") or {}
if local_env.get("exists") is not True:
    raise SystemExit("prekey smoke env should exist")
if local_env.get("gitIgnored") != "ignored":
    raise SystemExit(f"prekey smoke env should be ignored: {local_env}")
refusal = summary.get("finalizerRefusal") or {}
if refusal.get("status") != "NEEDS_QIANWEN_API_KEY":
    raise SystemExit(f"finalizer should refuse placeholder key: {refusal}")
if refusal.get("stepsAllNotRun") is not True:
    raise SystemExit("placeholder finalizer must not run steps")
plan = summary.get("finalizerDryRunPlan") or {}
if plan.get("status") != "PLAN_ONLY":
    raise SystemExit(f"dry-run plan should be PLAN_ONLY: {plan}")
if plan.get("apiKeyStatus") != "configured":
    raise SystemExit(f"dry-run plan should see configured temp key: {plan}")
if (
    plan.get("dryRun") is not True
    or plan.get("captureFrontend") is not True
    or plan.get("runAiSmoke") is not True
    or plan.get("runCloseout") is not True
    or plan.get("runCompletionVerify") is not True
):
    raise SystemExit(f"dry-run plan should include full defaults: {plan}")
frontend_evidence = plan.get("frontendEvidence") or {}
if frontend_evidence.get("requiredScreenshots") != ["09-variant-lab-tongyi-model-evidence.png"]:
    raise SystemExit(f"dry-run plan should expose required frontend screenshot gate: {frontend_evidence}")
redis_fault = plan.get("redisFault") or {}
if redis_fault.get("effectiveMode") != "docker-stop":
    raise SystemExit(f"local stack redis fault should resolve to docker-stop: {redis_fault}")
commands = "\n".join(plan.get("commands") or [])
for expected in (
    "production-infrastructure-local-dependency-stack.sh",
    "production-infrastructure-local-mysql-schema-apply.sh",
    "production-infrastructure-local-dependency-check.sh",
    "production-infrastructure-local-service.sh",
    "production-infrastructure-local-readiness.sh",
    "production-infrastructure-local-ai-smoke.sh",
    "production-infrastructure-local-frontend-evidence.sh",
    "production-infrastructure-local-evidence-collect.sh",
    "production-infrastructure-local-completion-verify.sh",
):
    if expected not in commands:
        raise SystemExit(f"dry-run plan missing command: {expected}")
if "prekey-dry-run-token" in text:
    raise SystemExit("prekey summary must not leak the temporary key")
next_commands = "\n".join(summary.get("nextCommands") or [])
if "replace only TONGYI_API_KEY" not in next_commands:
    raise SystemExit("prekey summary should guide single-key replacement")
if "production-infrastructure-local-finalize.sh" not in next_commands:
    raise SystemExit("prekey summary should point to finalizer")
PY

printf 'production infrastructure local prekey check smoke test passed\n'
