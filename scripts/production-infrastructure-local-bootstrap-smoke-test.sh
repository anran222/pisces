#!/usr/bin/env bash

set -euo pipefail

require_command() {
  command -v "$1" >/dev/null 2>&1 || {
    printf 'Missing command: %s\n' "$1" >&2
    exit 1
  }
}

require_command python3

smoke_root="${PISCES_LOCAL_BOOTSTRAP_SMOKE_ROOT:-target/pisces-production-infrastructure-local-bootstrap-smoke}"
run_id="$(date -u '+%Y%m%dT%H%M%SZ')-$$"
workspace="$smoke_root/bootstrap-smoke-$run_id"
env_file="$workspace/config/pisces-local.env"
summary_file="$workspace/bootstrap-summary.json"

PISCES_LOCAL_ENV_FILE="$env_file" \
PISCES_LOCAL_BOOTSTRAP_OUTPUT_FILE="$summary_file" \
bash scripts/production-infrastructure-local-bootstrap.sh >/dev/null

python3 - "$summary_file" "$env_file" <<'PY'
import json
import sys
from pathlib import Path

summary_file = Path(sys.argv[1])
env_file = Path(sys.argv[2])
summary = json.loads(summary_file.read_text(encoding="utf-8"))

if summary.get("summaryType") != "pisces-production-infrastructure-local-bootstrap":
    raise SystemExit("bootstrap summary type mismatch")
if summary.get("status") != "NEEDS_QIANWEN_API_KEY":
    raise SystemExit(f"expected placeholder status: {summary.get('status')}")
if summary.get("envCreated") is not True:
    raise SystemExit("bootstrap should create missing local env file")
if summary.get("qianwenApiKey") != "placeholder":
    raise SystemExit("placeholder key should not be treated as configured")
if not env_file.is_file():
    raise SystemExit("bootstrap did not create local env file")
if "<local-qianwen-api-key>" not in env_file.read_text(encoding="utf-8"):
    raise SystemExit("created env should retain Qianwen placeholder")
if 'TONGYI_MODEL="qwen3.7-max"' not in env_file.read_text(encoding="utf-8"):
    raise SystemExit("created env should retain production TongYi model")
if 'TONGYI_API_MODE="dashscope"' not in env_file.read_text(encoding="utf-8"):
    raise SystemExit("created env should retain DashScope API mode")
if 'TONGYI_FALLBACK_MODEL="qwen3.7-max"' not in env_file.read_text(encoding="utf-8"):
    raise SystemExit("created env should retain stable TongYi fallback model")
if not any("replace only TONGYI_API_KEY" in command for command in summary.get("nextCommands") or []):
    raise SystemExit("bootstrap should tell the user to replace only TONGYI_API_KEY")
PY

set +e
PISCES_LOCAL_ENV_FILE="$env_file" \
PISCES_LOCAL_BOOTSTRAP_OUTPUT_FILE="$workspace/bootstrap-strict-placeholder-summary.json" \
PISCES_LOCAL_BOOTSTRAP_STRICT=true \
bash scripts/production-infrastructure-local-bootstrap.sh >/dev/null
strict_placeholder_status=$?
set -e

if [[ "$strict_placeholder_status" -eq 0 ]]; then
  printf 'strict bootstrap should reject placeholder Qianwen key\n' >&2
  exit 1
fi

python3 - "$env_file" <<'PY'
import sys
from pathlib import Path

env_file = Path(sys.argv[1])
text = env_file.read_text(encoding="utf-8")
env_file.write_text(text.replace("<local-qianwen-api-key>", "local-qianwen-key-for-smoke"), encoding="utf-8")
PY

ready_summary_file="$workspace/bootstrap-ready-summary.json"
PISCES_LOCAL_ENV_FILE="$env_file" \
PISCES_LOCAL_BOOTSTRAP_OUTPUT_FILE="$ready_summary_file" \
PISCES_LOCAL_BOOTSTRAP_STRICT=true \
bash scripts/production-infrastructure-local-bootstrap.sh >/dev/null

python3 - "$ready_summary_file" <<'PY'
import json
import sys
from pathlib import Path

summary_file = Path(sys.argv[1])
text = summary_file.read_text(encoding="utf-8")
summary = json.loads(text)

if summary.get("status") != "READY_TO_SOURCE":
    raise SystemExit(f"expected ready status: {summary.get('status')}")
if summary.get("qianwenApiKey") != "configured":
    raise SystemExit("configured key should be summarized without exposing the value")
if "local-qianwen-key-for-smoke" in text:
    raise SystemExit("bootstrap summary must not leak the Qianwen key value")
required_commands = {
    "production-infrastructure-local-finalize.sh",
}
commands = "\n".join(summary.get("nextCommands") or [])
for marker in required_commands:
    if marker not in commands:
        raise SystemExit(f"missing next command marker: {marker}")
for deprecated_marker in (
    "production-infrastructure-local-service.sh start",
    "production-infrastructure-local-evidence-collect.sh",
):
    if deprecated_marker in commands:
        raise SystemExit(f"bootstrap should not require manual finalizer substep: {deprecated_marker}")
PY

printf 'production infrastructure local bootstrap smoke test passed\n'
