#!/usr/bin/env bash

set -euo pipefail

require_command() {
  command -v "$1" >/dev/null 2>&1 || {
    printf 'Missing command: %s\n' "$1" >&2
    exit 1
  }
}

require_command python3

smoke_root="${PISCES_LOCAL_COMPLETION_VERIFY_SMOKE_ROOT:-target/pisces-production-infrastructure-local-completion-verify-smoke}"
run_id="$(date -u '+%Y%m%dT%H%M%SZ')-$$"
workspace="$smoke_root/completion-verify-smoke-$run_id"
env_file="$workspace/config/pisces-local.env"
stack_env_file="$workspace/config/pisces-local-stack.env"
finalize_summary="$workspace/finalize-summary.json"
readiness_summary="$workspace/readiness-summary.json"
verify_summary="$workspace/verify-summary.json"
completion_workspace="$workspace/evidence-workspace"
collection_summary="$workspace/collection-summary.json"
ai_smoke_summary="$workspace/ai-smoke-summary.json"
browser_workflow_summary="$workspace/browser-workflow-summary.json"
closeout_summary="$completion_workspace/closeout/final/completion-summary.json"
closeout_report="$completion_workspace/closeout/final/closeout-report.md"
manifest="$workspace/release-evidence-archive/local-release/manifest.json"
screenshot_dir="$workspace/screenshots"
layout_audit="$screenshot_dir/layout-audit.json"

mkdir -p "$(dirname "$env_file")" "$(dirname "$closeout_summary")" "$(dirname "$manifest")" "$screenshot_dir"

cat >"$env_file" <<'ENV'
export TONGYI_API_KEY="<local-qianwen-api-key>"
export TONGYI_MODEL="qwen3.7-max"
export TONGYI_API_MODE="dashscope"
export TONGYI_FALLBACK_MODEL="qwen3.7-max"
export TONGYI_FALLBACK_API_MODE="dashscope"
ENV
cat >"$stack_env_file" <<'ENV'
export PISCES_LOCAL_STACK_PROJECT_NAME="pisces-local-completion-verify-smoke"
ENV

cat >"$finalize_summary" <<JSON
{
  "summaryType": "pisces-production-infrastructure-local-finalize",
  "status": "NEEDS_QIANWEN_API_KEY",
  "apiKeyStatus": "placeholder",
  "dryRun": false,
  "runCloseout": true,
  "outputs": {
    "aiSmokeSummary": "$ai_smoke_summary",
    "browserWorkflowSummary": "$browser_workflow_summary",
    "collectionSummary": "$collection_summary",
    "evidenceWorkspace": "$completion_workspace"
  },
  "steps": []
}
JSON

cat >"$readiness_summary" <<'JSON'
{
  "summaryType": "pisces-production-infrastructure-local-readiness",
  "readiness": "NEEDS_LOCAL_EVIDENCE",
  "checks": [
    {"name": "qianwen api key provided by environment", "status": "HOLD"}
  ]
}
JSON

set +e
PISCES_LOCAL_ENV_FILE="$env_file" \
PISCES_LOCAL_STACK_ENV_FILE="$stack_env_file" \
PISCES_LOCAL_FINALIZE_OUTPUT_FILE="$finalize_summary" \
PISCES_LOCAL_READINESS_OUTPUT_FILE="$readiness_summary" \
PISCES_LOCAL_COMPLETION_VERIFY_OUTPUT_FILE="$verify_summary" \
bash scripts/production-infrastructure-local-completion-verify.sh >/dev/null
needs_key_status=$?
set -e
if [[ "$needs_key_status" -eq 0 ]]; then
  printf 'completion verify should fail before key replacement\n' >&2
  exit 1
fi

python3 - "$verify_summary" <<'PY'
import json
import sys
from pathlib import Path

summary = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
if summary.get("summaryType") != "pisces-production-infrastructure-local-completion-verify":
    raise SystemExit("verify summary type mismatch")
if summary.get("status") != "NEEDS_API_KEY":
    raise SystemExit(f"expected NEEDS_API_KEY: {summary.get('status')}")
if summary.get("apiKeyStatus") != "placeholder":
    raise SystemExit("placeholder key status should be preserved")
if "replace only TONGYI_API_KEY" not in "\n".join(summary.get("nextCommands") or []):
    raise SystemExit("verify summary should guide single-key replacement")
PY

python3 - "$env_file" <<'PY'
import sys
from pathlib import Path

path = Path(sys.argv[1])
api_key_env = "TONGYI_" + "API_KEY"
path.write_text(
    f"export {api_key_env}=\"test-api-key\"\n"
    "export TONGYI_MODEL=\"qwen3.7-max\"\n"
    "export TONGYI_API_MODE=\"dashscope\"\n"
    "export TONGYI_FALLBACK_MODEL=\"qwen3.7-max\"\n"
    "export TONGYI_FALLBACK_API_MODE=\"dashscope\"\n",
    encoding="utf-8",
)
PY

cat >"$finalize_summary" <<JSON
{
  "summaryType": "pisces-production-infrastructure-local-finalize",
  "status": "PASS",
  "apiKeyStatus": "configured",
  "dryRun": false,
  "runCloseout": true,
  "outputs": {
    "aiSmokeSummary": "$ai_smoke_summary",
    "browserWorkflowSummary": "$browser_workflow_summary",
    "collectionSummary": "$collection_summary",
    "evidenceWorkspace": "$completion_workspace"
  },
  "steps": [
    {"name": "local dependency stack up", "status": "PASS"},
    {"name": "local MySQL schema apply", "status": "PASS"},
    {"name": "local dependency check", "status": "PASS"},
    {"name": "local service start", "status": "PASS"},
    {"name": "local readiness", "status": "PASS"},
    {"name": "local AI smoke", "status": "PASS"},
    {"name": "local frontend evidence", "status": "PASS"},
    {"name": "real browser experiment workflow", "status": "PASS"},
    {"name": "local evidence collect", "status": "PASS"}
  ]
}
JSON

cat >"$ai_smoke_summary" <<'JSON'
{
  "summaryType": "pisces-production-infrastructure-local-ai-smoke",
  "summaryVersion": 1,
  "status": "PASS",
  "targetEnvironment": "local",
  "apiKeyEnv": "TONGYI_API_KEY",
  "apiKeyStatus": "configured",
  "tongyiModelEnv": "TONGYI_MODEL",
  "tongyiModel": "qwen3.7-max",
  "tongyiApiModeEnv": "TONGYI_API_MODE",
  "tongyiApiMode": "dashscope",
  "tongyiFallbackModelEnv": "TONGYI_FALLBACK_MODEL",
  "tongyiFallbackModel": "qwen3.7-max",
  "tongyiFallbackApiModeEnv": "TONGYI_FALLBACK_API_MODE",
  "tongyiFallbackApiMode": "dashscope",
  "modelStrategy": "production-dashscope-qwen3.7-max-with-token-plan-preview-opt-in",
  "tongyiSelectedModel": "qwen3.7-max",
  "tongyiSelectedApiMode": "dashscope",
  "tongyiFallbackUsed": false,
  "tongyiAttemptedModels": ["qwen3.7-max"],
  "tongyiSelectedModelStrategy": "production-dashscope-qwen3.7-max-with-token-plan-preview-opt-in",
  "dryRun": false,
  "endpoint": "/variants/generate",
  "httpStatus": "200"
}
JSON

cat >"$browser_workflow_summary" <<'JSON'
{
  "summaryType": "pisces-real-browser-workflow-smoke",
  "summaryVersion": 1,
  "status": "PASS",
  "appId": "shop-app",
  "experimentId": "exp_browser_smoke",
  "initialExperimentCount": 8,
  "finalExperimentCount": 8,
  "cleanedUp": true,
  "runtimeErrorCount": 0,
  "runtimeErrors": [],
  "steps": [
    {"name": "应用和原始数据可访问", "status": "PASS"},
    {"name": "页面调用真实千问生成完整方案", "status": "PASS"},
    {"name": "页面完成实验草案填充和创建前检查", "status": "PASS"},
    {"name": "页面创建真实实验", "status": "PASS"},
    {"name": "页面启动真实实验", "status": "PASS"},
    {"name": "页面生成并物化真实实验数据", "status": "PASS"},
    {"name": "页面读取真实统计和分析结果", "status": "PASS"},
    {"name": "页面停止真实实验", "status": "PASS"},
    {"name": "页面生成报告并提交待审核结论", "status": "PASS"}
  ],
  "screenshots": [
    "01-real-ai-plans.png",
    "02-real-preflight.png",
    "03-real-analysis.png",
    "04-real-conclusion.png"
  ],
  "error": null
}
JSON

cat >"$readiness_summary" <<'JSON'
{
  "summaryType": "pisces-production-infrastructure-local-readiness",
  "readiness": "READY_FOR_LOCAL_CLOSEOUT",
  "checks": [
    {"name": "qianwen api key provided by environment", "status": "PASS"},
    {"name": "local service health", "status": "PASS"},
    {"name": "Operations audit status", "status": "HOLD"}
  ]
}
JSON

cat >"$collection_summary" <<JSON
{
  "summaryType": "pisces-production-infrastructure-local-evidence-collect",
  "status": "PASS",
  "workspace": "$completion_workspace",
  "closeoutWrapper": "$completion_workspace/run-local-closeout.sh",
  "closeout": {
    "summaryFile": "$closeout_summary",
    "reportFile": "$closeout_report"
  }
}
JSON

cat >"$closeout_summary" <<JSON
{
  "summaryType": "pisces-production-infrastructure-completion-audit",
  "status": "PASS",
  "completionStatus": "COMPLETE",
  "targetEnvironment": "local",
  "evidence": {
    "releaseEvidenceManifest": "$manifest",
    "screenshotDir": "$screenshot_dir",
    "layoutAudit": "$layout_audit"
  },
  "checks": []
}
JSON

cat >"$closeout_report" <<'MARKDOWN'
# Pisces Production Infrastructure Closeout

Verdict: **COMPLETE**
MARKDOWN

cat >"$manifest" <<'JSON'
{
  "manifestType": "pisces-runtime-plane-release-evidence",
  "environment": "local"
}
JSON

touch "$screenshot_dir/09-variant-lab-tongyi-model-evidence.png"

cat >"$layout_audit" <<'JSON'
{
  "summaryType": "pisces-web-core-layout-audit",
  "summaryVersion": 1,
  "status": "PASS",
  "strict": true,
  "screenshotCount": 23,
  "enforcedCount": 12,
  "failedCount": 0,
  "failedScreens": [],
  "records": [
    {
      "fileName": "09-variant-lab-tongyi-model-evidence.png",
      "status": "PASS",
      "enforced": true
    }
  ]
}
JSON

PISCES_LOCAL_ENV_FILE="$env_file" \
PISCES_LOCAL_STACK_ENV_FILE="$stack_env_file" \
PISCES_LOCAL_FINALIZE_OUTPUT_FILE="$finalize_summary" \
PISCES_LOCAL_READINESS_OUTPUT_FILE="$readiness_summary" \
PISCES_LOCAL_COMPLETION_VERIFY_OUTPUT_FILE="$verify_summary" \
bash scripts/production-infrastructure-local-completion-verify.sh >/dev/null

python3 - "$verify_summary" "$env_file" <<'PY'
import json
import sys
from pathlib import Path

summary_file = Path(sys.argv[1])
env_file = Path(sys.argv[2])
summary_text = summary_file.read_text(encoding="utf-8")
summary = json.loads(summary_text)
if summary.get("status") != "COMPLETE":
    raise SystemExit(f"expected COMPLETE: {summary.get('status')}")
if summary.get("apiKeyStatus") != "configured":
    raise SystemExit("configured key status expected")
if "test-api-key" in summary_text:
    raise SystemExit("verify summary must not leak local key")
if summary.get("holdChecks"):
    raise SystemExit(f"expected no holds: {summary.get('holdChecks')}")
if summary.get("failedChecks"):
    raise SystemExit(f"expected no failures: {summary.get('failedChecks')}")
if summary.get("nextCommands"):
    raise SystemExit("complete summary should not have next commands")
if summary.get("layoutAudit") is None:
    raise SystemExit("complete summary should expose layout audit path")
if summary.get("aiSmokeSummary") is None:
    raise SystemExit("complete summary should expose AI smoke summary path")
if summary.get("browserWorkflowSummary") is None:
    raise SystemExit("complete summary should expose browser workflow summary path")
ai_smoke_checks = [
    item for item in summary.get("checks", [])
    if item.get("name", "").startswith("local AI smoke")
]
if len(ai_smoke_checks) < 4:
    raise SystemExit("expected AI smoke checks in completion verify summary")
bad_ai_smoke_checks = [item for item in ai_smoke_checks if item.get("status") != "PASS"]
if bad_ai_smoke_checks:
    raise SystemExit(f"AI smoke checks should pass: {bad_ai_smoke_checks}")
browser_workflow_checks = [
    item for item in summary.get("checks", [])
    if item.get("name", "").startswith("real browser workflow")
]
if len(browser_workflow_checks) < 6:
    raise SystemExit("expected real browser workflow checks in completion verify summary")
bad_browser_workflow_checks = [
    item for item in browser_workflow_checks if item.get("status") != "PASS"
]
if bad_browser_workflow_checks:
    raise SystemExit(f"browser workflow checks should pass: {bad_browser_workflow_checks}")
layout_checks = [
    item for item in summary.get("checks", [])
    if item.get("name", "").startswith("core frontend layout audit")
]
if len(layout_checks) < 4:
    raise SystemExit("expected layout audit checks in completion verify summary")
bad_layout_checks = [item for item in layout_checks if item.get("status") != "PASS"]
if bad_layout_checks:
    raise SystemExit(f"layout audit checks should pass: {bad_layout_checks}")
variant_model_evidence_checks = [
    item for item in summary.get("checks", [])
    if item.get("name", "").startswith("variant lab model evidence")
]
if len(variant_model_evidence_checks) != 2:
    raise SystemExit("expected variant lab model evidence screenshot checks")
bad_variant_model_evidence_checks = [
    item for item in variant_model_evidence_checks
    if item.get("status") != "PASS"
]
if bad_variant_model_evidence_checks:
    raise SystemExit(f"variant lab model evidence checks should pass: {bad_variant_model_evidence_checks}")
if not env_file.exists():
    raise SystemExit("env file should remain in place")
PY

printf 'production infrastructure local completion verify smoke test passed\n'
