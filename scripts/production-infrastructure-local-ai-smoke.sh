#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  source config/pisces-local.env
  scripts/production-infrastructure-local-ai-smoke.sh

Environment:
  PISCES_REPO_ROOT                         Repository root. Default: inferred from this script.
  PISCES_LOCAL_ENV_FILE                    Local env file loaded before smoke. Default: config/pisces-local.env.
  PISCES_LOCAL_STACK_ENV_FILE              Local stack env file loaded before smoke. Default: config/pisces-local-stack.env.
  PISCES_LOCAL_AI_SMOKE_OUTPUT_FILE        JSON output. Default: target/pisces-production-infrastructure-local-ai-smoke/summary.json.
  PISCES_LOCAL_AI_SMOKE_RESPONSE_FILE      Raw API response JSON. Default: beside output file.
  PISCES_LOCAL_AI_SMOKE_REQUEST_FILE       Request JSON. Default: beside output file.
  PISCES_LOCAL_AI_SMOKE_DRY_RUN            Write plan only, do not call local service. Default: false.
  PISCES_LOCAL_AI_SMOKE_REQUIRE_SERVICE_SUMMARY
                                             Require healthy local service summary before smoke. Default: true.
  PISCES_LOCAL_SERVICE_SUMMARY_FILE        Local service summary. Default: target/pisces-production-infrastructure-local-service/summary.json.
  PISCES_INSTANCE_URLS                     Service base URL. Default: http://localhost:9990/api.
  PISCES_MANAGEMENT_API_KEY                Management scope API key. Default: ops-key.
  PISCES_QIANWEN_API_KEY_ENV               Runtime API key env var. Default: TONGYI_API_KEY.
  PISCES_TONGYI_MODEL_ENV                  Text model env var. Default: TONGYI_MODEL.
  PISCES_TONGYI_API_MODE_ENV               Text API mode env var. Default: TONGYI_API_MODE.
  PISCES_TONGYI_FALLBACK_MODEL_ENV         Fallback text model env var. Default: TONGYI_FALLBACK_MODEL.
  PISCES_TONGYI_FALLBACK_API_MODE_ENV      Fallback API mode env var. Default: TONGYI_FALLBACK_API_MODE.
  PISCES_LOCAL_AI_SMOKE_MIN_VARIANT_COUNT  Minimum generated variants. Default: 1.
  PISCES_LOCAL_AI_SMOKE_TIMEOUT_SECONDS    HTTP timeout. Default: 90.

This script performs the local Qianwen/TongYi smoke gate used by the finalizer.
It calls POST /variants/generate through the running Pisces service, so it
validates the configured API key, model ID, Spring binding, DashScope client,
controller authentication, and text variant parsing path in one check.
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

load_env_file() {
  local file="$1"
  if [[ -f "$file" ]]; then
    set -a
    # shellcheck disable=SC1090
    source "$file"
    set +a
  fi
}

first_instance_url() {
  python3 - "$PISCES_INSTANCE_URLS" <<'PY'
import sys

urls = [item.strip().rstrip("/") for item in sys.argv[1].split(",") if item.strip()]
if not urls:
    raise SystemExit("PISCES_INSTANCE_URLS is empty")
print(urls[0])
PY
}

qianwen_key_status() {
  local value="${!PISCES_QIANWEN_API_KEY_ENV:-}"
  case "$value" in
    "" )
      printf 'missing'
      ;;
    "<local-qianwen-api-key>"|"<your-dashscope-api-key>"|*local-qianwen-api-key* )
      printf 'placeholder'
      ;;
    \<*\> )
      printf 'placeholder'
      ;;
    * )
      printf 'configured'
      ;;
  esac
}

validate_local_service_summary() {
  if ! is_true "$PISCES_LOCAL_AI_SMOKE_REQUIRE_SERVICE_SUMMARY"; then
    log "Skipping local service summary gate because PISCES_LOCAL_AI_SMOKE_REQUIRE_SERVICE_SUMMARY=false"
    return
  fi

  python3 - "$PISCES_LOCAL_SERVICE_SUMMARY_FILE" <<'PY'
import json
import sys
from pathlib import Path

summary_file = Path(sys.argv[1])
if not summary_file.is_file():
    raise SystemExit(
        "Local service summary is missing; run "
        "bash scripts/production-infrastructure-local-service.sh start"
    )

try:
    summary = json.loads(summary_file.read_text(encoding="utf-8"))
except Exception as exc:
    raise SystemExit(f"Local service summary is not valid JSON: {exc}") from exc

problems = []
if summary.get("summaryType") != "pisces-production-infrastructure-local-service":
    problems.append("summaryType must be pisces-production-infrastructure-local-service")
if summary.get("targetEnvironment") != "local":
    problems.append("targetEnvironment must be local")
if summary.get("status") != "HEALTHY":
    problems.append("status must be HEALTHY")
if summary.get("apiKeyStatus") != "configured":
    problems.append("apiKeyStatus must be configured")
if summary.get("healthStatus") != "UP":
    problems.append("healthStatus must be UP")
if summary.get("dryRun") is True:
    problems.append("dryRun must be false")

if problems:
    joined = "; ".join(problems)
    raise SystemExit(
        f"Local service summary is not ready for AI smoke: {joined}. "
        "Run bash scripts/production-infrastructure-local-service.sh start after "
        "replacing TONGYI_API_KEY in config/pisces-local.env."
    )
PY
}

write_request_body() {
  python3 - "$PISCES_LOCAL_AI_SMOKE_REQUEST_FILE_RESOLVED" "$PISCES_LOCAL_AI_SMOKE_MIN_VARIANT_COUNT" <<'PY'
import json
import sys
from pathlib import Path

request_file = Path(sys.argv[1])
count = int(sys.argv[2])
payload = {
    "variantType": "TEXT",
    "goal": "验证通义文本模型连通性，生成一条实验按钮文案",
    "audience": "本地生产验收",
    "constraints": [
        "只输出中文短文案",
        "不要包含敏感信息",
        "每条至少十个汉字",
    ],
    "count": count,
    "sourceContext": {
        "brief": "pisces local ai smoke",
    },
}
request_file.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
PY
}

write_summary() {
  local status="$1"
  local exit_code="$2"
  local message="$3"
  local http_status="${4:-}"

  export PISCES_LOCAL_AI_SMOKE_STATUS="$status"
  export PISCES_LOCAL_AI_SMOKE_EXIT_CODE="$exit_code"
  export PISCES_LOCAL_AI_SMOKE_MESSAGE="$message"
  export PISCES_LOCAL_AI_SMOKE_HTTP_STATUS="$http_status"

  python3 <<'PY'
import json
import os
from datetime import datetime, timezone
from pathlib import Path

repo_root = Path(os.environ["PISCES_REPO_ROOT"]).resolve()
output_file = Path(os.environ["PISCES_LOCAL_AI_SMOKE_OUTPUT_FILE_RESOLVED"])
request_file = Path(os.environ["PISCES_LOCAL_AI_SMOKE_REQUEST_FILE_RESOLVED"])
response_file = Path(os.environ["PISCES_LOCAL_AI_SMOKE_RESPONSE_FILE_RESOLVED"])
service_summary = Path(os.environ["PISCES_LOCAL_SERVICE_SUMMARY_FILE"])
local_env_file = Path(os.environ["PISCES_LOCAL_ENV_FILE_RESOLVED"])
stack_env_file = Path(os.environ["PISCES_LOCAL_STACK_ENV_FILE_RESOLVED"])


def display(path):
    path = Path(path)
    try:
        return str(path.resolve().relative_to(repo_root))
    except ValueError:
        return str(path)


def read_response_metadata():
    if not response_file.is_file():
        return {}
    try:
        payload = json.loads(response_file.read_text(encoding="utf-8", errors="replace"))
    except Exception:
        return {}
    data = payload.get("data")
    if not isinstance(data, dict):
        return {}
    attempted_models = data.get("aiAttemptedModels")
    if not isinstance(attempted_models, list):
        attempted_models = []
    return {
        "selectedModel": data.get("aiModel"),
        "selectedApiMode": data.get("aiApiMode"),
        "fallbackUsed": data.get("aiFallbackUsed"),
        "attemptedModels": [str(model) for model in attempted_models if str(model).strip()],
        "primaryModel": data.get("aiPrimaryModel"),
        "fallbackModel": data.get("aiFallbackModel"),
        "modelStrategy": data.get("aiModelStrategy"),
    }


status = os.environ["PISCES_LOCAL_AI_SMOKE_STATUS"]
api_key_env = os.environ["PISCES_QIANWEN_API_KEY_ENV"]
response_metadata = read_response_metadata() if status == "PASS" else {}
next_commands = []
if status == "NEEDS_QIANWEN_API_KEY":
    next_commands.extend([
        f"edit {display(local_env_file)} and replace only {api_key_env}",
        "bash scripts/production-infrastructure-local-finalize.sh",
    ])
elif status in {"SERVICE_SUMMARY_NOT_READY", "AI_SMOKE_FAILED"}:
    next_commands.extend([
        "bash scripts/production-infrastructure-local-service.sh start",
        "bash scripts/production-infrastructure-local-ai-smoke.sh",
        "bash scripts/production-infrastructure-local-finalize.sh",
    ])
elif status == "PLAN_ONLY":
    next_commands.append("bash scripts/production-infrastructure-local-ai-smoke.sh")

summary = {
    "summaryType": "pisces-production-infrastructure-local-ai-smoke",
    "summaryVersion": 1,
    "generatedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "status": status,
    "exitCode": int(os.environ["PISCES_LOCAL_AI_SMOKE_EXIT_CODE"]),
    "message": os.environ["PISCES_LOCAL_AI_SMOKE_MESSAGE"],
    "targetEnvironment": "local",
    "apiKeyEnv": api_key_env,
    "apiKeyStatus": os.environ["PISCES_LOCAL_AI_SMOKE_QIANWEN_KEY_STATUS"],
    "tongyiModelEnv": os.environ["PISCES_TONGYI_MODEL_ENV"],
    "tongyiModel": os.environ["PISCES_LOCAL_AI_SMOKE_TONGYI_MODEL"],
    "tongyiApiModeEnv": os.environ["PISCES_TONGYI_API_MODE_ENV"],
    "tongyiApiMode": os.environ["PISCES_LOCAL_AI_SMOKE_TONGYI_API_MODE"],
    "tongyiFallbackModelEnv": os.environ["PISCES_TONGYI_FALLBACK_MODEL_ENV"],
    "tongyiFallbackModel": os.environ["PISCES_LOCAL_AI_SMOKE_TONGYI_FALLBACK_MODEL"],
    "tongyiFallbackApiModeEnv": os.environ["PISCES_TONGYI_FALLBACK_API_MODE_ENV"],
    "tongyiFallbackApiMode": os.environ["PISCES_LOCAL_AI_SMOKE_TONGYI_FALLBACK_API_MODE"],
    "modelStrategy": "production-dashscope-qwen3.7-max-with-token-plan-preview-opt-in",
    "tongyiSelectedModel": response_metadata.get("selectedModel"),
    "tongyiSelectedApiMode": response_metadata.get("selectedApiMode"),
    "tongyiFallbackUsed": response_metadata.get("fallbackUsed"),
    "tongyiAttemptedModels": response_metadata.get("attemptedModels") or [],
    "tongyiSelectedModelStrategy": response_metadata.get("modelStrategy"),
    "dryRun": os.environ["PISCES_LOCAL_AI_SMOKE_DRY_RUN"].lower() in {"true", "1", "yes", "y"},
    "serviceSummary": display(service_summary),
    "serviceSummaryRequired": os.environ["PISCES_LOCAL_AI_SMOKE_REQUIRE_SERVICE_SUMMARY"].lower()
    in {"true", "1", "yes", "y"},
    "instanceUrl": os.environ["PISCES_LOCAL_AI_SMOKE_INSTANCE_URL"],
    "endpoint": "/variants/generate",
    "httpStatus": os.environ["PISCES_LOCAL_AI_SMOKE_HTTP_STATUS"] or None,
    "requestFile": display(request_file),
    "responseFile": display(response_file) if response_file.is_file() else None,
    "requestContract": {
        "variantType": "TEXT",
        "count": int(os.environ["PISCES_LOCAL_AI_SMOKE_MIN_VARIANT_COUNT"]),
    },
    "commands": [
        (
            "curl -H 'X-Pisces-Api-Key: <management-key>' "
            "-H 'Content-Type: application/json' "
            "--data @<request-file> "
            f"{os.environ['PISCES_LOCAL_AI_SMOKE_INSTANCE_URL']}/variants/generate"
        )
    ],
    "nextCommands": next_commands,
}
output_file.write_text(json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
print(f"Production infrastructure local AI smoke written: {output_file} status={status}", file=os.sys.stderr)
PY
}

validate_response() {
  python3 - \
    "$PISCES_LOCAL_AI_SMOKE_RESPONSE_FILE_RESOLVED" \
    "$PISCES_LOCAL_AI_SMOKE_HTTP_STATUS_VALUE" \
    "$PISCES_LOCAL_AI_SMOKE_MIN_VARIANT_COUNT" \
    "${!PISCES_QIANWEN_API_KEY_ENV:-}" <<'PY'
import json
import sys
from pathlib import Path

response_file = Path(sys.argv[1])
http_status = int(sys.argv[2])
min_count = int(sys.argv[3])
api_key = sys.argv[4]
text = response_file.read_text(encoding="utf-8", errors="replace")

problems = []
if http_status < 200 or http_status >= 300:
    problems.append(f"HTTP status must be 2xx, got {http_status}")
try:
    payload = json.loads(text)
except Exception as exc:
    raise SystemExit(f"AI smoke response is not valid JSON: {exc}") from exc

if payload.get("code") != 200:
    problems.append(f"BaseResponse code must be 200, got {payload.get('code')}")
data = payload.get("data")
if not isinstance(data, dict):
    problems.append("data must be an object")
else:
    if data.get("variantType") != "TEXT":
        problems.append(f"variantType must be TEXT, got {data.get('variantType')}")
    variants = data.get("variants")
    if not isinstance(variants, list):
        problems.append("variants must be a list")
    elif len([item for item in variants if isinstance(item, str) and item.strip()]) < min_count:
        problems.append(f"variants must contain at least {min_count} non-empty item(s)")
    count = data.get("count")
    if not isinstance(count, int) or count < min_count:
        problems.append(f"count must be an integer >= {min_count}, got {count}")
    if not isinstance(data.get("aiModel"), str) or not data.get("aiModel").strip():
        problems.append("data.aiModel must record the selected TongYi text model")
    if not isinstance(data.get("aiApiMode"), str) or not data.get("aiApiMode").strip():
        problems.append("data.aiApiMode must record the selected TongYi text API mode")
    if not isinstance(data.get("aiFallbackUsed"), bool):
        problems.append("data.aiFallbackUsed must be a boolean")
    attempted_models = data.get("aiAttemptedModels")
    if not isinstance(attempted_models, list) or not attempted_models:
        problems.append("data.aiAttemptedModels must contain at least the selected model")
    if data.get("aiModelStrategy") != "production-dashscope-qwen3.7-max-with-token-plan-preview-opt-in":
        problems.append("data.aiModelStrategy must record the production DashScope model strategy")
if api_key and api_key in text:
    problems.append("response leaked the Qianwen API key")

if problems:
    raise SystemExit("; ".join(problems))
PY
}

main() {
  if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
    usage
    exit 0
  fi

  require_command python3

  PISCES_REPO_ROOT="$(resolve_repo_root)"
  PISCES_LOCAL_ENV_FILE="${PISCES_LOCAL_ENV_FILE:-config/pisces-local.env}"
  PISCES_LOCAL_STACK_ENV_FILE="${PISCES_LOCAL_STACK_ENV_FILE:-config/pisces-local-stack.env}"
  PISCES_LOCAL_AI_SMOKE_OUTPUT_FILE="${PISCES_LOCAL_AI_SMOKE_OUTPUT_FILE:-target/pisces-production-infrastructure-local-ai-smoke/summary.json}"
  PISCES_LOCAL_AI_SMOKE_DRY_RUN="${PISCES_LOCAL_AI_SMOKE_DRY_RUN:-false}"
  PISCES_LOCAL_AI_SMOKE_REQUIRE_SERVICE_SUMMARY="${PISCES_LOCAL_AI_SMOKE_REQUIRE_SERVICE_SUMMARY:-true}"
  PISCES_LOCAL_SERVICE_SUMMARY_FILE="${PISCES_LOCAL_SERVICE_SUMMARY_FILE:-target/pisces-production-infrastructure-local-service/summary.json}"
  PISCES_INSTANCE_URLS="${PISCES_INSTANCE_URLS:-http://localhost:9990/api}"
  PISCES_MANAGEMENT_API_KEY="${PISCES_MANAGEMENT_API_KEY:-ops-key}"
  PISCES_QIANWEN_API_KEY_ENV="${PISCES_QIANWEN_API_KEY_ENV:-TONGYI_API_KEY}"
  PISCES_TONGYI_MODEL_ENV="${PISCES_TONGYI_MODEL_ENV:-TONGYI_MODEL}"
  PISCES_TONGYI_API_MODE_ENV="${PISCES_TONGYI_API_MODE_ENV:-TONGYI_API_MODE}"
  PISCES_TONGYI_FALLBACK_MODEL_ENV="${PISCES_TONGYI_FALLBACK_MODEL_ENV:-TONGYI_FALLBACK_MODEL}"
  PISCES_TONGYI_FALLBACK_API_MODE_ENV="${PISCES_TONGYI_FALLBACK_API_MODE_ENV:-TONGYI_FALLBACK_API_MODE}"
  PISCES_LOCAL_AI_SMOKE_MIN_VARIANT_COUNT="${PISCES_LOCAL_AI_SMOKE_MIN_VARIANT_COUNT:-1}"
  PISCES_LOCAL_AI_SMOKE_TIMEOUT_SECONDS="${PISCES_LOCAL_AI_SMOKE_TIMEOUT_SECONDS:-90}"

  local env_file stack_env_file output_file output_dir request_file response_file service_summary
  env_file="$(resolve_path "$PISCES_LOCAL_ENV_FILE")"
  stack_env_file="$(resolve_path "$PISCES_LOCAL_STACK_ENV_FILE")"
  output_file="$(resolve_path "$PISCES_LOCAL_AI_SMOKE_OUTPUT_FILE")"
  output_dir="$(dirname "$output_file")"
  request_file="$(resolve_path "${PISCES_LOCAL_AI_SMOKE_REQUEST_FILE:-$output_dir/variant-generate-request.json}")"
  response_file="$(resolve_path "${PISCES_LOCAL_AI_SMOKE_RESPONSE_FILE:-$output_dir/variant-generate-response.json}")"
  service_summary="$(resolve_path "$PISCES_LOCAL_SERVICE_SUMMARY_FILE")"
  mkdir -p "$output_dir" "$(dirname "$request_file")" "$(dirname "$response_file")"

  load_env_file "$env_file"
  load_env_file "$stack_env_file"

  PISCES_LOCAL_AI_SMOKE_QIANWEN_KEY_STATUS="$(qianwen_key_status)"
  PISCES_LOCAL_AI_SMOKE_TONGYI_MODEL="${!PISCES_TONGYI_MODEL_ENV:-qwen3.7-max}"
  PISCES_LOCAL_AI_SMOKE_TONGYI_API_MODE="${!PISCES_TONGYI_API_MODE_ENV:-dashscope}"
  PISCES_LOCAL_AI_SMOKE_TONGYI_FALLBACK_MODEL="${!PISCES_TONGYI_FALLBACK_MODEL_ENV:-qwen3.7-max}"
  PISCES_LOCAL_AI_SMOKE_TONGYI_FALLBACK_API_MODE="${!PISCES_TONGYI_FALLBACK_API_MODE_ENV:-dashscope}"
  PISCES_LOCAL_AI_SMOKE_INSTANCE_URL="$(first_instance_url)"
  PISCES_LOCAL_AI_SMOKE_REQUEST_FILE_RESOLVED="$request_file"
  PISCES_LOCAL_AI_SMOKE_RESPONSE_FILE_RESOLVED="$response_file"
  PISCES_LOCAL_AI_SMOKE_OUTPUT_FILE_RESOLVED="$output_file"
  PISCES_LOCAL_ENV_FILE_RESOLVED="$env_file"
  PISCES_LOCAL_STACK_ENV_FILE_RESOLVED="$stack_env_file"
  PISCES_LOCAL_SERVICE_SUMMARY_FILE="$service_summary"

  export PISCES_REPO_ROOT
  export PISCES_LOCAL_ENV_FILE_RESOLVED
  export PISCES_LOCAL_STACK_ENV_FILE_RESOLVED
  export PISCES_LOCAL_AI_SMOKE_OUTPUT_FILE_RESOLVED
  export PISCES_LOCAL_AI_SMOKE_REQUEST_FILE_RESOLVED
  export PISCES_LOCAL_AI_SMOKE_RESPONSE_FILE_RESOLVED
  export PISCES_LOCAL_AI_SMOKE_DRY_RUN
  export PISCES_LOCAL_AI_SMOKE_REQUIRE_SERVICE_SUMMARY
  export PISCES_LOCAL_SERVICE_SUMMARY_FILE
  export PISCES_LOCAL_AI_SMOKE_QIANWEN_KEY_STATUS
  export PISCES_LOCAL_AI_SMOKE_TONGYI_MODEL
  export PISCES_LOCAL_AI_SMOKE_TONGYI_API_MODE
  export PISCES_LOCAL_AI_SMOKE_TONGYI_FALLBACK_MODEL
  export PISCES_LOCAL_AI_SMOKE_TONGYI_FALLBACK_API_MODE
  export PISCES_LOCAL_AI_SMOKE_INSTANCE_URL
  export PISCES_LOCAL_AI_SMOKE_MIN_VARIANT_COUNT
  export PISCES_QIANWEN_API_KEY_ENV
  export PISCES_TONGYI_MODEL_ENV
  export PISCES_TONGYI_API_MODE_ENV
  export PISCES_TONGYI_FALLBACK_MODEL_ENV
  export PISCES_TONGYI_FALLBACK_API_MODE_ENV

  write_request_body

  if [[ "$PISCES_LOCAL_AI_SMOKE_QIANWEN_KEY_STATUS" != "configured" ]]; then
    write_summary "NEEDS_QIANWEN_API_KEY" 1 \
      "Refusing to run local AI smoke with missing or placeholder Qianwen API key."
    return 1
  fi

  if is_true "$PISCES_LOCAL_AI_SMOKE_DRY_RUN"; then
    write_summary "PLAN_ONLY" 0 "Dry run only; local AI smoke was not executed."
    return
  fi

  set +e
  validate_local_service_summary
  local service_summary_status=$?
  set -e
  if [[ "$service_summary_status" -ne 0 ]]; then
    write_summary "SERVICE_SUMMARY_NOT_READY" "$service_summary_status" \
      "Local service summary is not ready for AI smoke."
    return "$service_summary_status"
  fi

  require_command curl

  log "Calling local TongYi variant generation smoke with model=$PISCES_LOCAL_AI_SMOKE_TONGYI_MODEL fallback=$PISCES_LOCAL_AI_SMOKE_TONGYI_FALLBACK_MODEL"
  set +e
  PISCES_LOCAL_AI_SMOKE_HTTP_STATUS_VALUE="$(
    curl -sS --max-time "$PISCES_LOCAL_AI_SMOKE_TIMEOUT_SECONDS" \
      -w "%{http_code}" \
      -o "$response_file" \
      -H "Content-Type: application/json" \
      -H "X-Pisces-Api-Key: $PISCES_MANAGEMENT_API_KEY" \
      --data-binary "@$request_file" \
      "$PISCES_LOCAL_AI_SMOKE_INSTANCE_URL/variants/generate"
  )"
  local curl_exit=$?
  set -e
  if [[ "$curl_exit" -ne 0 ]]; then
    write_summary "AI_SMOKE_FAILED" "$curl_exit" "Local AI smoke curl call failed." \
      "${PISCES_LOCAL_AI_SMOKE_HTTP_STATUS_VALUE:-}"
    return "$curl_exit"
  fi
  export PISCES_LOCAL_AI_SMOKE_HTTP_STATUS_VALUE

  set +e
  validate_response
  local validation_exit=$?
  set -e
  if [[ "$validation_exit" -ne 0 ]]; then
    write_summary "AI_SMOKE_FAILED" "$validation_exit" "Local AI smoke response validation failed." \
      "$PISCES_LOCAL_AI_SMOKE_HTTP_STATUS_VALUE"
    return "$validation_exit"
  fi

  write_summary "PASS" 0 "Local TongYi text variant smoke passed." \
    "$PISCES_LOCAL_AI_SMOKE_HTTP_STATUS_VALUE"
}

main "$@"
