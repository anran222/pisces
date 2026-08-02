#!/usr/bin/env bash

set -euo pipefail

require_command() {
  command -v "$1" >/dev/null 2>&1 || {
    printf 'Missing command: %s\n' "$1" >&2
    exit 1
  }
}

require_command python3

smoke_root="${PISCES_LOCAL_FINALIZE_SMOKE_ROOT:-target/pisces-production-infrastructure-local-finalize-smoke}"
run_id="$(date -u '+%Y%m%dT%H%M%SZ')-$$"
workspace="$smoke_root/local-finalize-smoke-$run_id"
env_file="$workspace/config/pisces-local.env"
missing_env_file="$workspace/config/generated-by-finalize.env"
stack_env_file="$workspace/config/pisces-local-stack.env"
missing_env_summary="$workspace/missing-env-summary.json"
placeholder_summary="$workspace/placeholder-summary.json"
plan_summary="$workspace/plan-summary.json"
disabled_plan_summary="$workspace/disabled-plan-summary.json"
collect_failure_summary="$workspace/collect-failure-summary.json"

set +e
(
  unset PISCES_EXPERIMENT_ID PISCES_LOCAL_SERVICE_SUMMARY_FILE PISCES_LOCAL_STACK_PROJECT_NAME PISCES_REDIS_DOCKER_CONTAINER PISCES_FAULT_CONFIRM PISCES_LOCAL_FINALIZE_REDIS_FAULT_MODE
  PISCES_LOCAL_ENV_FILE="$missing_env_file" \
  PISCES_LOCAL_STACK_ENV_FILE="$stack_env_file" \
  PISCES_LOCAL_FINALIZE_OUTPUT_FILE="$missing_env_summary" \
  bash scripts/production-infrastructure-local-finalize.sh >/dev/null
)
missing_env_status=$?
set -e

if [[ "$missing_env_status" -eq 0 ]]; then
  printf 'local finalize should stop after bootstrapping a missing env file\n' >&2
  exit 1
fi

python3 - "$missing_env_summary" "$missing_env_file" <<'PY'
import json
import sys
from pathlib import Path

summary = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
env_file = Path(sys.argv[2])
env_text = env_file.read_text(encoding="utf-8")
if summary.get("summaryType") != "pisces-production-infrastructure-local-finalize":
    raise SystemExit("missing-env finalize summary type mismatch")
if summary.get("status") != "NEEDS_QIANWEN_API_KEY":
    raise SystemExit(f"expected NEEDS_QIANWEN_API_KEY: {summary.get('status')}")
if summary.get("apiKeyStatus") != "placeholder":
    raise SystemExit(f"expected placeholder key status: {summary.get('apiKeyStatus')}")
if summary.get("bootstrapEnv") is not True:
    raise SystemExit("finalize should default bootstrapEnv=true")
if summary.get("envCreatedByFinalize") is not True:
    raise SystemExit("finalize should report envCreatedByFinalize=true")
if "<local-qianwen-api-key>" not in env_text:
    raise SystemExit("bootstrapped env should contain Qianwen placeholder")
if 'TONGYI_MODEL="qwen3.7-max"' not in env_text:
    raise SystemExit("bootstrapped env should contain production TongYi model")
if 'TONGYI_API_MODE="dashscope"' not in env_text:
    raise SystemExit("bootstrapped env should contain DashScope API mode")
if 'TONGYI_FALLBACK_MODEL="qwen3.7-max"' not in env_text:
    raise SystemExit("bootstrapped env should contain stable TongYi fallback model")
outputs = summary.get("outputs") or {}
bootstrap_summary = outputs.get("envBootstrapSummary") or ""
if not bootstrap_summary.endswith("bootstrap-summary.json"):
    raise SystemExit(f"missing bootstrap summary output: {outputs}")
if "replace only TONGYI_API_KEY" not in "\n".join(summary.get("nextCommands") or []):
    raise SystemExit("missing-env summary should guide single-key replacement")
if any(step.get("status") != "NOT_RUN" for step in summary.get("steps") or []):
    raise SystemExit("missing-env finalize must not run any finalization step")
PY

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
export PISCES_LOCAL_STACK_PROJECT_NAME="pisces-local-finalize-smoke"
export PISCES_REDIS_DOCKER_CONTAINER="pisces-local-finalize-smoke-redis-1"
ENV

set +e
(
  unset PISCES_EXPERIMENT_ID PISCES_LOCAL_SERVICE_SUMMARY_FILE PISCES_LOCAL_STACK_PROJECT_NAME PISCES_REDIS_DOCKER_CONTAINER PISCES_FAULT_CONFIRM PISCES_LOCAL_FINALIZE_REDIS_FAULT_MODE
  PISCES_LOCAL_ENV_FILE="$env_file" \
  PISCES_LOCAL_STACK_ENV_FILE="$stack_env_file" \
  PISCES_LOCAL_FINALIZE_OUTPUT_FILE="$placeholder_summary" \
  bash scripts/production-infrastructure-local-finalize.sh >/dev/null
)
placeholder_status=$?
set -e

if [[ "$placeholder_status" -eq 0 ]]; then
  printf 'local finalize should reject placeholder Qianwen key\n' >&2
  exit 1
fi

python3 - "$placeholder_summary" <<'PY'
import json
import sys
from pathlib import Path

summary = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
if summary.get("summaryType") != "pisces-production-infrastructure-local-finalize":
    raise SystemExit("finalize summary type mismatch")
if summary.get("status") != "NEEDS_QIANWEN_API_KEY":
    raise SystemExit(f"expected NEEDS_QIANWEN_API_KEY: {summary.get('status')}")
if summary.get("apiKeyStatus") != "placeholder":
    raise SystemExit(f"expected placeholder key status: {summary.get('apiKeyStatus')}")
if "replace only TONGYI_API_KEY" not in "\n".join(summary.get("nextCommands") or []):
    raise SystemExit("finalize summary should guide single-key replacement")
if any(step.get("status") != "NOT_RUN" for step in summary.get("steps") or []):
    raise SystemExit("placeholder finalize must not run any finalization step")
PY

python3 - "$env_file" <<'PY'
import sys
from pathlib import Path

env_file = Path(sys.argv[1])
text = env_file.read_text(encoding="utf-8")
env_file.write_text(text.replace("<local-qianwen-api-key>", "local-qianwen-key-for-finalize-smoke"), encoding="utf-8")
PY

(
  unset PISCES_EXPERIMENT_ID PISCES_LOCAL_SERVICE_SUMMARY_FILE PISCES_LOCAL_STACK_PROJECT_NAME PISCES_REDIS_DOCKER_CONTAINER PISCES_FAULT_CONFIRM PISCES_LOCAL_FINALIZE_REDIS_FAULT_MODE
  PISCES_RELEASE_ID="local-finalize-smoke-$run_id" \
  PISCES_LOCAL_ENV_FILE="$env_file" \
  PISCES_LOCAL_STACK_ENV_FILE="$stack_env_file" \
  PISCES_LOCAL_FINALIZE_OUTPUT_FILE="$plan_summary" \
  PISCES_LOCAL_FINALIZE_DRY_RUN=true \
  bash scripts/production-infrastructure-local-finalize.sh >/dev/null
)

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
if "local-qianwen-key-for-finalize-smoke" in text:
    raise SystemExit("finalize summary must not leak Qianwen key")
if summary.get("dryRun") is not True:
    raise SystemExit("finalize dry-run summary should set dryRun=true")
if summary.get("runCloseout") is not True:
    raise SystemExit("finalize should run closeout by default")
if summary.get("runCompletionVerify") is not True:
    raise SystemExit("finalize should run completion verify by default")
if summary.get("runAiSmoke") is not True:
    raise SystemExit("finalize should run local AI smoke by default")
if summary.get("captureFrontend") is not True:
    raise SystemExit("finalize should capture frontend evidence by default")
if summary.get("tongyiModel") != "qwen3.7-max":
    raise SystemExit(f"finalize should record qwen3.7-max model: {summary.get('tongyiModel')}")
if summary.get("tongyiFallbackModel") != "qwen3.7-max":
    raise SystemExit(f"finalize should record qwen3.7-max fallback model: {summary.get('tongyiFallbackModel')}")
if summary.get("modelStrategy") != "production-dashscope-qwen3.7-max-with-token-plan-preview-opt-in":
    raise SystemExit(f"finalize should record production model strategy: {summary.get('modelStrategy')}")
frontend_evidence = summary.get("frontendEvidence") or {}
if frontend_evidence.get("captureEnabled") is not True:
    raise SystemExit(f"finalize should enable frontend evidence by default: {frontend_evidence}")
if frontend_evidence.get("requiredScreenshots") != ["09-variant-lab-tongyi-model-evidence.png"]:
    raise SystemExit(f"finalize should require variant model evidence screenshot: {frontend_evidence}")
redis_fault = summary.get("redisFault") or {}
if redis_fault.get("requestedMode") != "auto":
    raise SystemExit(f"expected auto Redis fault mode request: {redis_fault}")
if redis_fault.get("effectiveMode") != "docker-stop":
    raise SystemExit(f"expected local stack Redis fault mode to resolve to docker-stop: {redis_fault}")
if redis_fault.get("dockerContainer") != "pisces-local-finalize-smoke-redis-1":
    raise SystemExit(f"expected local stack Redis container in summary: {redis_fault}")
if redis_fault.get("faultConfirm") is not True:
    raise SystemExit(f"expected local stack Redis fault confirmation: {redis_fault}")
if redis_fault.get("autoConfirmedLocalStackContainer") is not True:
    raise SystemExit(f"expected local stack container auto confirmation: {redis_fault}")
if any(step.get("status") != "NOT_RUN" for step in summary.get("steps") or []):
    raise SystemExit("dry-run finalize must not run any finalization step")
commands = summary.get("commands") or []
for expected in (
    "bash scripts/production-infrastructure-local-dependency-stack.sh up",
    "bash scripts/production-infrastructure-local-mysql-schema-apply.sh",
    "bash scripts/production-infrastructure-local-dependency-check.sh",
    "bash scripts/production-infrastructure-local-service.sh start",
    "bash scripts/production-infrastructure-local-readiness.sh",
    "bash scripts/production-infrastructure-local-ai-smoke.sh",
    "bash scripts/production-infrastructure-local-frontend-evidence.sh",
):
    if expected not in commands:
        raise SystemExit(f"finalize plan missing command: {expected}")
if not any(
    "PISCES_LOCAL_COLLECT_REDIS_FAULT_MODE=docker-stop" in command
    and "production-infrastructure-local-evidence-collect.sh" in command
    for command in commands
):
    raise SystemExit("finalize plan should pass the resolved Redis fault mode to collector")
if "bash scripts/production-infrastructure-local-completion-verify.sh" not in commands:
    raise SystemExit("finalize plan missing completion verify command")
outputs = summary.get("outputs") or {}
if not str(outputs.get("evidenceWorkspace") or "").endswith("evidence-workspace"):
    raise SystemExit("finalize summary should expose evidence workspace")
if not str(outputs.get("completionVerifySummary") or "").endswith("completion-verify-summary.json"):
    raise SystemExit("finalize summary should expose completion verify summary")
PY

(
  unset PISCES_EXPERIMENT_ID PISCES_LOCAL_SERVICE_SUMMARY_FILE PISCES_LOCAL_STACK_PROJECT_NAME PISCES_REDIS_DOCKER_CONTAINER PISCES_FAULT_CONFIRM PISCES_LOCAL_FINALIZE_REDIS_FAULT_MODE
  PISCES_RELEASE_ID="local-finalize-disabled-plan-smoke-$run_id" \
  PISCES_LOCAL_ENV_FILE="$env_file" \
  PISCES_LOCAL_STACK_ENV_FILE="$stack_env_file" \
  PISCES_LOCAL_FINALIZE_OUTPUT_FILE="$disabled_plan_summary" \
  PISCES_LOCAL_FINALIZE_DRY_RUN=true \
  PISCES_LOCAL_FINALIZE_START_DEPENDENCY_STACK=false \
  PISCES_LOCAL_FINALIZE_APPLY_SCHEMA=false \
  PISCES_LOCAL_FINALIZE_CHECK_DEPENDENCIES=false \
  PISCES_LOCAL_FINALIZE_START_SERVICE=false \
  PISCES_LOCAL_FINALIZE_RUN_READINESS=false \
  PISCES_LOCAL_FINALIZE_RUN_AI_SMOKE=false \
  PISCES_LOCAL_FINALIZE_CAPTURE_FRONTEND=false \
  PISCES_LOCAL_FINALIZE_RUN_COMPLETION_VERIFY=false \
  bash scripts/production-infrastructure-local-finalize.sh >/dev/null
)

python3 - "$disabled_plan_summary" <<'PY'
import json
import sys
from pathlib import Path

summary = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
if summary.get("status") != "PLAN_ONLY":
    raise SystemExit(f"expected disabled plan to stay PLAN_ONLY: {summary.get('status')}")
expected_false_flags = (
    "startDependencyStack",
    "applySchema",
    "checkDependencies",
    "startService",
    "runReadiness",
    "runAiSmoke",
    "captureFrontend",
    "runCompletionVerify",
)
for flag in expected_false_flags:
    if summary.get(flag) is not False:
        raise SystemExit(f"expected {flag}=false in disabled plan: {summary.get(flag)}")
frontend_evidence = summary.get("frontendEvidence") or {}
if frontend_evidence.get("captureEnabled") is not False:
    raise SystemExit(f"disabled plan should expose captureEnabled=false: {frontend_evidence}")
if frontend_evidence.get("requiredScreenshots") != ["09-variant-lab-tongyi-model-evidence.png"]:
    raise SystemExit(f"disabled plan should still expose required screenshot gate: {frontend_evidence}")
commands = summary.get("commands") or []
for unexpected in (
    "production-infrastructure-local-dependency-stack.sh",
    "production-infrastructure-local-mysql-schema-apply.sh",
    "production-infrastructure-local-dependency-check.sh",
    "production-infrastructure-local-service.sh",
    "production-infrastructure-local-readiness.sh",
    "production-infrastructure-local-ai-smoke.sh",
    "production-infrastructure-local-frontend-evidence.sh",
    "production-infrastructure-local-completion-verify.sh",
):
    if any(unexpected in command for command in commands):
        raise SystemExit(f"disabled plan should not include command: {unexpected}")
if len(commands) != 1 or "production-infrastructure-local-evidence-collect.sh" not in commands[0]:
    raise SystemExit(f"disabled plan should retain only evidence collection command: {commands}")
if commands != summary.get("nextCommands"):
    raise SystemExit("dry-run nextCommands should mirror the configured command plan")
PY

set +e
(
  unset PISCES_EXPERIMENT_ID PISCES_LOCAL_SERVICE_SUMMARY_FILE PISCES_LOCAL_STACK_PROJECT_NAME PISCES_REDIS_DOCKER_CONTAINER PISCES_FAULT_CONFIRM PISCES_LOCAL_FINALIZE_REDIS_FAULT_MODE
  PISCES_RELEASE_ID="local-finalize-collect-failure-smoke-$run_id" \
  PISCES_LOCAL_ENV_FILE="$env_file" \
  PISCES_LOCAL_STACK_ENV_FILE="$stack_env_file" \
  PISCES_LOCAL_FINALIZE_OUTPUT_FILE="$collect_failure_summary" \
  PISCES_LOCAL_FINALIZE_START_DEPENDENCY_STACK=false \
  PISCES_LOCAL_FINALIZE_APPLY_SCHEMA=false \
  PISCES_LOCAL_FINALIZE_CHECK_DEPENDENCIES=false \
  PISCES_LOCAL_FINALIZE_START_SERVICE=false \
  PISCES_LOCAL_FINALIZE_RUN_READINESS=false \
  PISCES_LOCAL_FINALIZE_RUN_AI_SMOKE=false \
  PISCES_LOCAL_FINALIZE_CAPTURE_FRONTEND=false \
  bash scripts/production-infrastructure-local-finalize.sh >/dev/null
)
collect_failure_status=$?
set -e

if [[ "$collect_failure_status" -eq 0 ]]; then
  printf 'local finalize should fail when evidence collection cannot verify service summary\n' >&2
  exit 1
fi

python3 - "$collect_failure_summary" <<'PY'
import json
import sys
from pathlib import Path

summary_file = Path(sys.argv[1])
text = summary_file.read_text(encoding="utf-8")
summary = json.loads(text)
if summary.get("status") != "EVIDENCE_COLLECT_FAILED":
    raise SystemExit(f"expected EVIDENCE_COLLECT_FAILED: {summary.get('status')}")
if summary.get("apiKeyStatus") != "configured":
    raise SystemExit(f"expected configured key status: {summary.get('apiKeyStatus')}")
if "local-qianwen-key-for-finalize-smoke" in text:
    raise SystemExit("failure summary must not leak Qianwen key")
steps = {step.get("name"): step for step in summary.get("steps") or []}
if steps.get("local dependency stack up", {}).get("status") != "NOT_RUN":
    raise SystemExit("dependency stack step should be NOT_RUN in collect-failure smoke")
if steps.get("local MySQL schema apply", {}).get("status") != "NOT_RUN":
    raise SystemExit("schema step should be NOT_RUN in collect-failure smoke")
if steps.get("local dependency check", {}).get("status") != "NOT_RUN":
    raise SystemExit("dependency check step should be NOT_RUN in collect-failure smoke")
if steps.get("local service start", {}).get("status") != "NOT_RUN":
    raise SystemExit("service step should be NOT_RUN in collect-failure smoke")
if steps.get("local readiness", {}).get("status") != "NOT_RUN":
    raise SystemExit("readiness step should be NOT_RUN in collect-failure smoke")
if steps.get("local AI smoke", {}).get("status") != "NOT_RUN":
    raise SystemExit("AI smoke step should be NOT_RUN in collect-failure smoke")
if steps.get("local frontend evidence", {}).get("status") != "NOT_RUN":
    raise SystemExit("frontend evidence step should be NOT_RUN in collect-failure smoke")
if steps.get("local evidence collect", {}).get("status") != "FAIL":
    raise SystemExit("collect step should be FAIL in collect-failure smoke")
if steps.get("local completion verify", {}).get("status") != "NOT_RUN":
    raise SystemExit("completion verify step should be NOT_RUN when collection fails")
PY

printf 'production infrastructure local finalize smoke test passed\n'
