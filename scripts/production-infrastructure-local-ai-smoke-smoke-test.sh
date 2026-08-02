#!/usr/bin/env bash

set -euo pipefail

require_command() {
  command -v "$1" >/dev/null 2>&1 || {
    printf 'Missing command: %s\n' "$1" >&2
    exit 1
  }
}

require_command python3

smoke_root="${PISCES_LOCAL_AI_SMOKE_SMOKE_ROOT:-target/pisces-production-infrastructure-local-ai-smoke-smoke}"
run_id="$(date -u '+%Y%m%dT%H%M%SZ')-$$"
workspace="$smoke_root/ai-smoke-$run_id"
env_file="$workspace/config/pisces-local.env"
stack_env_file="$workspace/config/pisces-local-stack.env"
service_summary="$workspace/service-summary.json"
placeholder_summary="$workspace/placeholder-summary.json"
plan_summary="$workspace/plan-summary.json"
pass_summary="$workspace/pass-summary.json"
server_port_file="$workspace/server-port.txt"
server_log="$workspace/server.log"

mkdir -p "$(dirname "$env_file")"

cat >"$env_file" <<'ENV'
export TONGYI_API_KEY="<local-qianwen-api-key>"
export TONGYI_MODEL="qwen3.7-max"
export TONGYI_API_MODE="dashscope"
export TONGYI_FALLBACK_MODEL="qwen3.7-max"
export TONGYI_FALLBACK_API_MODE="dashscope"
ENV
cat >"$stack_env_file" <<'ENV'
export PISCES_LOCAL_STACK_PROJECT_NAME="pisces-local-ai-smoke-smoke"
ENV

set +e
PISCES_LOCAL_ENV_FILE="$env_file" \
PISCES_LOCAL_STACK_ENV_FILE="$stack_env_file" \
PISCES_LOCAL_AI_SMOKE_OUTPUT_FILE="$placeholder_summary" \
bash scripts/production-infrastructure-local-ai-smoke.sh >/dev/null
placeholder_status=$?
set -e

if [[ "$placeholder_status" -eq 0 ]]; then
  printf 'local AI smoke should reject placeholder Qianwen key\n' >&2
  exit 1
fi

python3 - "$placeholder_summary" <<'PY'
import json
import sys
from pathlib import Path

summary = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
if summary.get("summaryType") != "pisces-production-infrastructure-local-ai-smoke":
    raise SystemExit("AI smoke summary type mismatch")
if summary.get("status") != "NEEDS_QIANWEN_API_KEY":
    raise SystemExit(f"expected NEEDS_QIANWEN_API_KEY: {summary.get('status')}")
if summary.get("apiKeyStatus") != "placeholder":
    raise SystemExit(f"expected placeholder key status: {summary.get('apiKeyStatus')}")
if summary.get("tongyiModel") != "qwen3.7-max":
    raise SystemExit(f"expected qwen3.7-max model: {summary.get('tongyiModel')}")
if summary.get("tongyiFallbackModel") != "qwen3.7-max":
    raise SystemExit(f"expected qwen3.7-max fallback model: {summary.get('tongyiFallbackModel')}")
if summary.get("modelStrategy") != "production-dashscope-qwen3.7-max-with-token-plan-preview-opt-in":
    raise SystemExit(f"expected production model strategy: {summary.get('modelStrategy')}")
if "replace only TONGYI_API_KEY" not in "\n".join(summary.get("nextCommands") or []):
    raise SystemExit("AI smoke should guide single-key replacement")
PY

python3 - "$env_file" <<'PY'
import sys
from pathlib import Path

env_file = Path(sys.argv[1])
text = env_file.read_text(encoding="utf-8")
env_file.write_text(text.replace("<local-qianwen-api-key>", "local-qianwen-key-for-ai-smoke"), encoding="utf-8")
PY

PISCES_LOCAL_ENV_FILE="$env_file" \
PISCES_LOCAL_STACK_ENV_FILE="$stack_env_file" \
PISCES_LOCAL_AI_SMOKE_OUTPUT_FILE="$plan_summary" \
PISCES_LOCAL_AI_SMOKE_DRY_RUN=true \
bash scripts/production-infrastructure-local-ai-smoke.sh >/dev/null

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
if summary.get("dryRun") is not True:
    raise SystemExit("AI smoke dry run summary should set dryRun=true")
if summary.get("endpoint") != "/variants/generate":
    raise SystemExit("AI smoke dry run should expose variants endpoint")
if "local-qianwen-key-for-ai-smoke" in text:
    raise SystemExit("AI smoke dry run summary must not leak the Qianwen key")
PY

cat >"$service_summary" <<'JSON'
{
  "summaryType": "pisces-production-infrastructure-local-service",
  "targetEnvironment": "local",
  "status": "HEALTHY",
  "apiKeyStatus": "configured",
  "healthStatus": "UP",
  "dryRun": false
}
JSON

python3 - "$server_port_file" >"$server_log" 2>&1 <<'PY' &
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

port_file = Path(sys.argv[1])


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        if self.path != "/api/variants/generate":
            self.send_response(404)
            self.end_headers()
            return
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length)
        try:
            request = json.loads(body.decode("utf-8"))
        except Exception:
            request = {}
        if self.headers.get("X-Pisces-Api-Key") != "ops-key":
            self.send_response(401)
            self.end_headers()
            return
        if request.get("variantType") != "TEXT":
            self.send_response(400)
            self.end_headers()
            return

        payload = {
            "code": 200,
            "message": "生成成功",
            "data": {
                "variantType": "TEXT",
                "variants": ["本地AI连通性验证文案"],
                "count": 1,
                "aiProvider": "tongyi",
                "aiPrimaryModel": "qwen3.7-max",
                "aiModel": "qwen3.7-max",
                "aiApiMode": "dashscope",
                "aiFallbackUsed": False,
                "aiFallbackModel": "qwen3.7-max",
                "aiAttemptedModels": ["qwen3.7-max"],
                "aiModelStrategy": "production-dashscope-qwen3.7-max-with-token-plan-preview-opt-in",
            },
        }
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, _format, *args):
        return


server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
port_file.write_text(str(server.server_address[1]), encoding="utf-8")
server.serve_forever()
PY
server_pid=$!
trap 'kill "$server_pid" >/dev/null 2>&1 || true' EXIT

for _ in {1..50}; do
  if [[ -f "$server_port_file" ]]; then
    break
  fi
  sleep 0.1
done
if [[ ! -f "$server_port_file" ]]; then
  printf 'AI smoke test server did not start\n' >&2
  exit 1
fi
server_port="$(cat "$server_port_file")"

PISCES_LOCAL_ENV_FILE="$env_file" \
PISCES_LOCAL_STACK_ENV_FILE="$stack_env_file" \
PISCES_LOCAL_SERVICE_SUMMARY_FILE="$service_summary" \
PISCES_LOCAL_AI_SMOKE_OUTPUT_FILE="$pass_summary" \
PISCES_INSTANCE_URLS="http://127.0.0.1:$server_port/api" \
bash scripts/production-infrastructure-local-ai-smoke.sh >/dev/null

python3 - "$pass_summary" <<'PY'
import json
import sys
from pathlib import Path

summary_file = Path(sys.argv[1])
text = summary_file.read_text(encoding="utf-8")
summary = json.loads(text)
if summary.get("status") != "PASS":
    raise SystemExit(f"expected PASS: {summary.get('status')}")
if summary.get("apiKeyStatus") != "configured":
    raise SystemExit(f"expected configured key status: {summary.get('apiKeyStatus')}")
if summary.get("tongyiModel") != "qwen3.7-max":
    raise SystemExit(f"expected qwen3.7-max model: {summary.get('tongyiModel')}")
if summary.get("tongyiFallbackModel") != "qwen3.7-max":
    raise SystemExit(f"expected qwen3.7-max fallback model: {summary.get('tongyiFallbackModel')}")
if summary.get("modelStrategy") != "production-dashscope-qwen3.7-max-with-token-plan-preview-opt-in":
    raise SystemExit(f"expected production model strategy: {summary.get('modelStrategy')}")
if summary.get("tongyiSelectedModel") != "qwen3.7-max":
    raise SystemExit(f"expected selected production model: {summary.get('tongyiSelectedModel')}")
if summary.get("tongyiSelectedApiMode") != "dashscope":
    raise SystemExit(f"expected selected DashScope mode: {summary.get('tongyiSelectedApiMode')}")
if summary.get("tongyiFallbackUsed") is not False:
    raise SystemExit(f"expected no fallback in mock success: {summary.get('tongyiFallbackUsed')}")
if summary.get("tongyiAttemptedModels") != ["qwen3.7-max"]:
    raise SystemExit(f"expected attempted production model: {summary.get('tongyiAttemptedModels')}")
if summary.get("httpStatus") != "200":
    raise SystemExit(f"expected HTTP 200: {summary.get('httpStatus')}")
if summary.get("endpoint") != "/variants/generate":
    raise SystemExit("AI smoke summary should expose variants endpoint")
if not str(summary.get("responseFile") or "").endswith("variant-generate-response.json"):
    raise SystemExit("AI smoke summary should expose response file")
if "local-qianwen-key-for-ai-smoke" in text:
    raise SystemExit("AI smoke summary must not leak the Qianwen key")
PY

printf 'production infrastructure local AI smoke smoke test passed\n'
