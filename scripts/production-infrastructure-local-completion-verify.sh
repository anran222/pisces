#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  scripts/production-infrastructure-local-completion-verify.sh

Environment:
  PISCES_REPO_ROOT                         Repository root. Default: inferred from this script.
  PISCES_LOCAL_COMPLETION_VERIFY_OUTPUT_FILE
                                           JSON output. Default: target/pisces-production-infrastructure-local-completion-verify/summary.json.
  PISCES_LOCAL_FINALIZE_OUTPUT_FILE         Finalizer summary. Default: target/pisces-production-infrastructure-local-finalize/summary.json.
  PISCES_LOCAL_READINESS_OUTPUT_FILE        Readiness summary. Default: target/pisces-production-infrastructure-local-readiness/summary.json.
  PISCES_QIANWEN_API_KEY_ENV               Runtime API key env var. Default: TONGYI_API_KEY.

This script is the post-key local completion verifier. It does not start
services or collect evidence. It only verifies current evidence produced by the
local finalizer, TongYi AI smoke, and closeout. The only successful terminal
state is COMPLETE.
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

main() {
  if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
    usage
    exit 0
  fi

  command -v python3 >/dev/null 2>&1 || die "Missing command: python3"

  PISCES_REPO_ROOT="$(resolve_repo_root)"
  PISCES_LOCAL_ENV_FILE="$(resolve_path "${PISCES_LOCAL_ENV_FILE:-config/pisces-local.env}")"
  PISCES_LOCAL_STACK_ENV_FILE="$(resolve_path "${PISCES_LOCAL_STACK_ENV_FILE:-config/pisces-local-stack.env}")"
  PISCES_LOCAL_FINALIZE_OUTPUT_FILE="$(resolve_path "${PISCES_LOCAL_FINALIZE_OUTPUT_FILE:-target/pisces-production-infrastructure-local-finalize/summary.json}")"
  PISCES_LOCAL_READINESS_OUTPUT_FILE="$(resolve_path "${PISCES_LOCAL_READINESS_OUTPUT_FILE:-target/pisces-production-infrastructure-local-readiness/summary.json}")"
  PISCES_LOCAL_COMPLETION_VERIFY_OUTPUT_FILE="$(resolve_path "${PISCES_LOCAL_COMPLETION_VERIFY_OUTPUT_FILE:-target/pisces-production-infrastructure-local-completion-verify/summary.json}")"
  PISCES_QIANWEN_API_KEY_ENV="${PISCES_QIANWEN_API_KEY_ENV:-TONGYI_API_KEY}"

  load_env_file "$PISCES_LOCAL_ENV_FILE"
  load_env_file "$PISCES_LOCAL_STACK_ENV_FILE"
  PISCES_LOCAL_COMPLETION_VERIFY_API_KEY_STATUS="$(qianwen_key_status)"

  mkdir -p "$(dirname "$PISCES_LOCAL_COMPLETION_VERIFY_OUTPUT_FILE")"

  export PISCES_REPO_ROOT
  export PISCES_LOCAL_ENV_FILE
  export PISCES_LOCAL_STACK_ENV_FILE
  export PISCES_LOCAL_FINALIZE_OUTPUT_FILE
  export PISCES_LOCAL_READINESS_OUTPUT_FILE
  export PISCES_LOCAL_COMPLETION_VERIFY_OUTPUT_FILE
  export PISCES_QIANWEN_API_KEY_ENV
  export PISCES_LOCAL_COMPLETION_VERIFY_API_KEY_STATUS

  python3 <<'PY'
import json
import os
from datetime import datetime, timezone
from pathlib import Path

repo_root = Path(os.environ["PISCES_REPO_ROOT"]).resolve()
output_file = Path(os.environ["PISCES_LOCAL_COMPLETION_VERIFY_OUTPUT_FILE"])
local_env_file = Path(os.environ["PISCES_LOCAL_ENV_FILE"])
finalize_file = Path(os.environ["PISCES_LOCAL_FINALIZE_OUTPUT_FILE"])
readiness_file = Path(os.environ["PISCES_LOCAL_READINESS_OUTPUT_FILE"])
api_key_env = os.environ["PISCES_QIANWEN_API_KEY_ENV"]
api_key_status = os.environ["PISCES_LOCAL_COMPLETION_VERIFY_API_KEY_STATUS"]


def display(path):
    path = Path(path)
    try:
        return str(path.resolve().relative_to(repo_root))
    except Exception:
        return str(path)


def read_json(path):
    path = Path(path)
    if not path.is_file():
        return None
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception as exc:
        return {"_invalidJson": str(exc)}


def add_check(checks, name, status, actual, expected, evidence=None, action=None):
    item = {
        "name": name,
        "status": status,
        "actual": actual,
        "expected": expected,
    }
    if evidence:
        item["evidence"] = evidence
    if action:
        item["action"] = action
    checks.append(item)


def step_statuses(finalize):
    return {
        item.get("name"): item.get("status")
        for item in (finalize or {}).get("steps", [])
    }


def path_from_finalize(finalize, key):
    value = ((finalize or {}).get("outputs") or {}).get(key)
    if not value:
        return None
    return (repo_root / value).resolve() if not Path(value).is_absolute() else Path(value)


def resolve_optional_path(value):
    if not value:
        return None
    path = Path(value)
    return path if path.is_absolute() else (repo_root / path).resolve()


def default_local_closeout_file(name):
    closeout_dir = Path(os.environ.get(
        "PISCES_LOCAL_CLOSEOUT_DIR",
        "target/pisces-production-infrastructure-local-closeout",
    ))
    if not closeout_dir.is_absolute():
        closeout_dir = (repo_root / closeout_dir).resolve()
    return closeout_dir / "final" / name


checks = []
next_commands = []
finalize = read_json(finalize_file)
readiness = read_json(readiness_file)

add_check(
    checks,
    "qianwen api key configured",
    "PASS" if api_key_status == "configured" else "HOLD",
    api_key_status,
    f"{api_key_env} configured with a non-placeholder value",
    display(local_env_file),
    f"edit {display(local_env_file)} and replace only {api_key_env}",
)

if finalize is None:
    add_check(
        checks,
        "local finalizer summary exists",
        "HOLD",
        "missing",
        "present",
        display(finalize_file),
        "bash scripts/production-infrastructure-local-finalize.sh",
    )
elif finalize.get("_invalidJson"):
    add_check(
        checks,
        "local finalizer summary parses",
        "FAIL",
        finalize["_invalidJson"],
        "valid JSON",
        display(finalize_file),
    )
else:
    add_check(
        checks,
        "local finalizer completed",
        "PASS" if finalize.get("status") == "PASS" else "HOLD",
        finalize.get("status"),
        "PASS",
        display(finalize_file),
        "bash scripts/production-infrastructure-local-finalize.sh",
    )
    add_check(
        checks,
        "local finalizer used real key",
        "PASS" if finalize.get("apiKeyStatus") == "configured" else "HOLD",
        finalize.get("apiKeyStatus"),
        "configured",
        display(finalize_file),
        f"edit {display(local_env_file)} and replace only {api_key_env}",
    )
    add_check(
        checks,
        "local finalizer ran closeout",
        "PASS" if finalize.get("runCloseout") is True else "HOLD",
        finalize.get("runCloseout"),
        "true",
        display(finalize_file),
        "rerun finalizer with PISCES_LOCAL_FINALIZE_RUN_CLOSEOUT=true",
    )
    add_check(
        checks,
        "local finalizer was not dry-run",
        "PASS" if finalize.get("dryRun") is False else "HOLD",
        finalize.get("dryRun"),
        "false",
        display(finalize_file),
        "bash scripts/production-infrastructure-local-finalize.sh",
    )
    statuses = step_statuses(finalize)
    required_steps = [
        "local dependency stack up",
        "local MySQL schema apply",
        "local dependency check",
        "local service start",
        "local readiness",
        "local AI smoke",
        "local frontend evidence",
        "local evidence collect",
    ]
    missing_or_bad = [
        f"{name}={statuses.get(name)}"
        for name in required_steps
        if statuses.get(name) != "PASS"
    ]
    add_check(
        checks,
        "local finalizer required steps passed",
        "PASS" if not missing_or_bad else "HOLD",
        "PASS" if not missing_or_bad else ", ".join(missing_or_bad),
        "all required steps PASS",
        display(finalize_file),
        "inspect failed step summaries under target/pisces-production-infrastructure-local-finalize",
    )

ai_smoke_file = path_from_finalize(finalize, "aiSmokeSummary") if isinstance(finalize, dict) else None
ai_smoke = read_json(ai_smoke_file) if ai_smoke_file else None
if ai_smoke_file:
    if ai_smoke is None:
        add_check(
            checks,
            "local AI smoke summary exists",
            "HOLD",
            "missing",
            "present",
            display(ai_smoke_file),
            "bash scripts/production-infrastructure-local-ai-smoke.sh",
        )
    elif ai_smoke.get("_invalidJson"):
        add_check(
            checks,
            "local AI smoke summary parses",
            "FAIL",
            ai_smoke["_invalidJson"],
            "valid JSON",
            display(ai_smoke_file),
        )
    else:
        add_check(
            checks,
            "local AI smoke summary contract",
            "PASS" if ai_smoke.get("summaryType") == "pisces-production-infrastructure-local-ai-smoke" else "FAIL",
            ai_smoke.get("summaryType"),
            "pisces-production-infrastructure-local-ai-smoke",
            display(ai_smoke_file),
        )
        add_check(
            checks,
            "local AI smoke status",
            "PASS" if ai_smoke.get("status") == "PASS" else "HOLD",
            ai_smoke.get("status"),
            "PASS",
            display(ai_smoke_file),
            "bash scripts/production-infrastructure-local-ai-smoke.sh",
        )
        add_check(
            checks,
            "local AI smoke used real key",
            "PASS" if ai_smoke.get("apiKeyStatus") == "configured" else "HOLD",
            ai_smoke.get("apiKeyStatus"),
            "configured",
            display(ai_smoke_file),
            f"edit {display(local_env_file)} and replace only {api_key_env}",
        )
        add_check(
            checks,
            "local AI smoke model recorded",
            "PASS" if isinstance(ai_smoke.get("tongyiModel"), str) and ai_smoke.get("tongyiModel").strip() else "HOLD",
            ai_smoke.get("tongyiModel"),
            "non-empty TongYi text model",
            display(ai_smoke_file),
            "set TONGYI_MODEL or keep the default production DashScope strategy",
        )
        add_check(
            checks,
            "local AI smoke production model strategy recorded",
            "PASS" if ai_smoke.get("modelStrategy")
            == "production-dashscope-qwen3.7-max-with-token-plan-preview-opt-in" else "HOLD",
            ai_smoke.get("modelStrategy"),
            "production-dashscope-qwen3.7-max-with-token-plan-preview-opt-in",
            display(ai_smoke_file),
            "restore TongYi model strategy defaults from config/pisces-local.env.example",
        )
        add_check(
            checks,
            "local AI smoke selected model recorded",
            "PASS" if isinstance(ai_smoke.get("tongyiSelectedModel"), str)
            and ai_smoke.get("tongyiSelectedModel").strip() else "HOLD",
            ai_smoke.get("tongyiSelectedModel"),
            "actual selected TongYi text model from /variants/generate response",
            display(ai_smoke_file),
            "rerun local AI smoke after confirming /variants/generate returns AI model metadata",
        )
        add_check(
            checks,
            "local AI smoke attempted models recorded",
            "PASS" if isinstance(ai_smoke.get("tongyiAttemptedModels"), list)
            and len(ai_smoke.get("tongyiAttemptedModels")) > 0 else "HOLD",
            ai_smoke.get("tongyiAttemptedModels"),
            "non-empty attempted model list",
            display(ai_smoke_file),
            "rerun local AI smoke after confirming /variants/generate returns attempted model metadata",
        )
else:
    add_check(
        checks,
        "local AI smoke summary exists",
        "HOLD",
        "missing aiSmokeSummary path",
        "present",
        display(finalize_file),
        "bash scripts/production-infrastructure-local-finalize.sh",
    )

collection_file = path_from_finalize(finalize, "collectionSummary") if isinstance(finalize, dict) else None
collection = read_json(collection_file) if collection_file else None
if collection_file:
    if collection is None:
        add_check(
            checks,
            "local evidence collection summary exists",
            "HOLD",
            "missing",
            "present",
            display(collection_file),
            "bash scripts/production-infrastructure-local-finalize.sh",
        )
    elif collection.get("_invalidJson"):
        add_check(
            checks,
            "local evidence collection summary parses",
            "FAIL",
            collection["_invalidJson"],
            "valid JSON",
            display(collection_file),
        )
    else:
        add_check(
            checks,
            "local evidence collection completed",
            "PASS" if collection.get("status") == "PASS" else "HOLD",
            collection.get("status"),
            "PASS",
            display(collection_file),
            "inspect evidence collection summary and rerun finalizer",
        )
        closeout_wrapper = collection.get("closeoutWrapper")
        if closeout_wrapper:
            add_check(
                checks,
                "local evidence exposes closeout wrapper",
                "PASS",
                closeout_wrapper,
                "present",
                display(collection_file),
            )
        else:
            add_check(
                checks,
                "local evidence exposes closeout wrapper",
                "HOLD",
                "missing",
                "present",
                display(collection_file),
                "rerun scripts/production-infrastructure-local-evidence-collect.sh",
            )
else:
    add_check(
        checks,
        "local evidence collection summary exists",
        "HOLD",
        "missing collectionSummary path",
        "present",
        display(finalize_file),
        "bash scripts/production-infrastructure-local-finalize.sh",
    )

if readiness is None:
    add_check(
        checks,
        "local readiness summary exists",
        "HOLD",
        "missing",
        "present",
        display(readiness_file),
        "bash scripts/production-infrastructure-local-readiness.sh",
    )
elif readiness.get("_invalidJson"):
    add_check(
        checks,
        "local readiness summary parses",
        "FAIL",
        readiness["_invalidJson"],
        "valid JSON",
        display(readiness_file),
    )
else:
    readiness_holds = [
        item.get("name")
        for item in readiness.get("checks", [])
        if item.get("status") == "HOLD"
    ]
    readiness_holds_resolved = bool(collection and collection.get("status") == "PASS")
    add_check(
        checks,
        "local readiness has no failed checks",
        "PASS"
        if not [item for item in readiness.get("checks", []) if item.get("status") == "FAIL"]
        else "FAIL",
        [
            item.get("name")
            for item in readiness.get("checks", [])
            if item.get("status") == "FAIL"
        ],
        "no FAIL checks",
        display(readiness_file),
    )
    add_check(
        checks,
        "local readiness has no holds",
        "PASS"
        if not readiness_holds or readiness_holds_resolved
        else "HOLD",
        readiness_holds,
        "no HOLD checks, or pre-evidence HOLD checks resolved by successful evidence collection",
        display(readiness_file),
        "bash scripts/production-infrastructure-local-finalize.sh",
    )

closeout_summary_file = None
closeout_report_file = None
if collection and isinstance(collection, dict):
    closeout = collection.get("closeout") or {}
    if closeout.get("summaryFile"):
        closeout_summary_file = Path(closeout["summaryFile"])
    else:
        closeout_summary_file = default_local_closeout_file("completion-summary.json")
    if closeout.get("reportFile"):
        closeout_report_file = Path(closeout["reportFile"])
    else:
        closeout_report_file = default_local_closeout_file("closeout-report.md")

if closeout_summary_file and not closeout_summary_file.is_absolute():
    closeout_summary_file = (repo_root / closeout_summary_file).resolve()
if closeout_report_file and not closeout_report_file.is_absolute():
    closeout_report_file = (repo_root / closeout_report_file).resolve()

closeout_summary = read_json(closeout_summary_file) if closeout_summary_file else None
if closeout_summary_file:
    if closeout_summary is None:
        add_check(
            checks,
            "final closeout summary exists",
            "HOLD",
            "missing",
            "present",
            display(closeout_summary_file),
            "run the generated run-local-closeout.sh or rerun finalizer",
        )
    elif closeout_summary.get("_invalidJson"):
        add_check(
            checks,
            "final closeout summary parses",
            "FAIL",
            closeout_summary["_invalidJson"],
            "valid JSON",
            display(closeout_summary_file),
        )
    else:
        add_check(
            checks,
            "final closeout completion",
            "PASS" if closeout_summary.get("completionStatus") == "COMPLETE" else "HOLD",
            closeout_summary.get("completionStatus"),
            "COMPLETE",
            display(closeout_summary_file),
            "inspect closeout blocking gates and rerun generated run-local-closeout.sh",
        )
        add_check(
            checks,
            "final closeout target environment",
            "PASS" if closeout_summary.get("targetEnvironment") == "local" else "HOLD",
            closeout_summary.get("targetEnvironment"),
            "local",
            display(closeout_summary_file),
        )
else:
    add_check(
        checks,
        "final closeout summary exists",
        "HOLD",
        "missing closeout summary path",
        "present",
        display(collection_file) if collection_file else display(finalize_file),
        "bash scripts/production-infrastructure-local-finalize.sh",
    )

layout_audit_file = None
screenshot_dir = None
if closeout_summary and isinstance(closeout_summary, dict):
    evidence = closeout_summary.get("evidence") or {}
    if evidence.get("screenshotDir"):
        screenshot_dir = Path(evidence["screenshotDir"])
    if evidence.get("layoutAudit"):
        layout_audit_file = Path(evidence["layoutAudit"])
    elif evidence.get("screenshotDir"):
        layout_audit_file = Path(evidence["screenshotDir"]) / "layout-audit.json"
if screenshot_dir and not screenshot_dir.is_absolute():
    screenshot_dir = (repo_root / screenshot_dir).resolve()
if layout_audit_file and not layout_audit_file.is_absolute():
    layout_audit_file = (repo_root / layout_audit_file).resolve()

layout_audit = read_json(layout_audit_file) if layout_audit_file else None
if layout_audit_file:
    if layout_audit is None:
        add_check(
            checks,
            "core frontend layout audit exists",
            "HOLD",
            "missing",
            "present",
            display(layout_audit_file),
            "rerun scripts/production-infrastructure-local-frontend-evidence.sh or the full finalizer",
        )
    elif layout_audit.get("_invalidJson"):
        add_check(
            checks,
            "core frontend layout audit parses",
            "FAIL",
            layout_audit["_invalidJson"],
            "valid JSON",
            display(layout_audit_file),
        )
    else:
        add_check(
            checks,
            "core frontend layout audit contract",
            "PASS" if layout_audit.get("summaryType") == "pisces-web-core-layout-audit" else "FAIL",
            layout_audit.get("summaryType"),
            "pisces-web-core-layout-audit",
            display(layout_audit_file),
        )
        add_check(
            checks,
            "core frontend layout audit status",
            "PASS" if layout_audit.get("status") == "PASS" else "HOLD",
            layout_audit.get("status"),
            "PASS",
            display(layout_audit_file),
            "fix horizontal layout regressions and rerun frontend evidence",
        )
        add_check(
            checks,
            "core frontend layout audit failed count",
            "PASS" if layout_audit.get("failedCount") == 0 else "HOLD",
            layout_audit.get("failedCount"),
            0,
            display(layout_audit_file),
            "inspect failedScreens in layout-audit.json",
        )
        enforced_count = layout_audit.get("enforcedCount")
        add_check(
            checks,
            "core frontend layout audit enforced screens",
            "PASS" if isinstance(enforced_count, int) and enforced_count >= 8 else "HOLD",
            enforced_count,
            ">= 8",
            display(layout_audit_file),
            "ensure core desktop screens and modals are enforced by capture-core-functions.cjs",
        )
        required_screenshot = "09-variant-lab-tongyi-model-evidence.png"
        layout_records = [
            item
            for item in layout_audit.get("records", [])
            if isinstance(item, dict)
        ]
        required_record = next(
            (item for item in layout_records if item.get("fileName") == required_screenshot),
            None,
        )
        screenshot_base_dir = screenshot_dir or layout_audit_file.parent
        required_screenshot_file = screenshot_base_dir / required_screenshot
        add_check(
            checks,
            "variant lab model evidence screenshot exists",
            "PASS" if required_screenshot_file.is_file() else "HOLD",
            display(required_screenshot_file) if required_screenshot_file.is_file() else "missing",
            required_screenshot,
            display(screenshot_base_dir),
            "rerun scripts/production-infrastructure-local-frontend-evidence.sh after updating pisces-web",
        )
        add_check(
            checks,
            "variant lab model evidence layout audit passed",
            "PASS" if required_record and required_record.get("status") == "PASS" else "HOLD",
            required_record.get("status") if required_record else "missing",
            "PASS",
            display(layout_audit_file),
            "ensure capture-core-functions.cjs captures 09-variant-lab-tongyi-model-evidence.png",
        )
else:
    add_check(
        checks,
        "core frontend layout audit exists",
        "HOLD",
        "missing layout audit path",
        "layout-audit.json",
        display(closeout_summary_file) if closeout_summary_file else display(finalize_file),
        "rerun scripts/production-infrastructure-local-frontend-evidence.sh or the full finalizer",
    )

if closeout_report_file:
    report_ok = closeout_report_file.is_file() and "Verdict: **COMPLETE**" in closeout_report_file.read_text(
        encoding="utf-8",
        errors="replace",
    )
    add_check(
        checks,
        "final closeout report verdict",
        "PASS" if report_ok else "HOLD",
        "COMPLETE" if report_ok else "missing or not COMPLETE",
        "Verdict: **COMPLETE**",
        display(closeout_report_file),
        "inspect closeout report",
    )
else:
    add_check(
        checks,
        "final closeout report verdict",
        "HOLD",
        "missing closeout report path",
        "Verdict: **COMPLETE**",
        display(collection_file) if collection_file else display(finalize_file),
    )

manifest_file = None
if closeout_summary and isinstance(closeout_summary, dict):
    evidence = closeout_summary.get("evidence") or {}
    if evidence.get("releaseEvidenceManifest"):
        manifest_file = Path(evidence["releaseEvidenceManifest"])
if manifest_file and not manifest_file.is_absolute():
    manifest_file = (repo_root / manifest_file).resolve()

manifest = read_json(manifest_file) if manifest_file else None
if manifest_file:
    if manifest is None:
        add_check(
            checks,
            "release evidence manifest exists",
            "HOLD",
            "missing",
            "present",
            display(manifest_file),
            "rerun generated run-local-closeout.sh",
        )
    elif manifest.get("_invalidJson"):
        add_check(
            checks,
            "release evidence manifest parses",
            "FAIL",
            manifest["_invalidJson"],
            "valid JSON",
            display(manifest_file),
        )
    else:
        add_check(
            checks,
            "release evidence manifest environment",
            "PASS" if manifest.get("environment") == "local" else "HOLD",
            manifest.get("environment"),
            "local",
            display(manifest_file),
        )
else:
    add_check(
        checks,
        "release evidence manifest exists",
        "HOLD",
        "missing manifest path",
        "present",
        display(closeout_summary_file) if closeout_summary_file else display(finalize_file),
        "rerun generated run-local-closeout.sh",
    )

failed = [item for item in checks if item["status"] == "FAIL"]
holds = [item for item in checks if item["status"] == "HOLD"]
if failed:
    status = "BLOCKED"
elif not holds:
    status = "COMPLETE"
elif api_key_status != "configured":
    status = "NEEDS_API_KEY"
else:
    status = "NEEDS_FINALIZER"

if status == "NEEDS_API_KEY":
    next_commands = [
        f"edit {display(local_env_file)} and replace only {api_key_env}",
        "bash scripts/production-infrastructure-local-finalize.sh",
        "bash scripts/production-infrastructure-local-completion-verify.sh",
    ]
elif status == "NEEDS_FINALIZER":
    next_commands = [
        "bash scripts/production-infrastructure-local-finalize.sh",
        "bash scripts/production-infrastructure-local-completion-verify.sh",
    ]
elif status == "BLOCKED":
    next_commands = [
        f"inspect {display(output_file)}",
        "bash scripts/production-infrastructure-local-readiness.sh",
    ]
else:
    next_commands = []

summary = {
    "summaryType": "pisces-production-infrastructure-local-completion-verify",
    "summaryVersion": 1,
    "generatedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "status": status,
    "targetEnvironment": "local",
    "apiKeyEnv": api_key_env,
    "apiKeyStatus": api_key_status,
    "finalizeSummary": display(finalize_file),
    "readinessSummary": display(readiness_file),
    "aiSmokeSummary": display(ai_smoke_file) if ai_smoke_file else None,
    "collectionSummary": display(collection_file) if collection_file else None,
    "closeoutSummary": display(closeout_summary_file) if closeout_summary_file else None,
    "closeoutReport": display(closeout_report_file) if closeout_report_file else None,
    "layoutAudit": display(layout_audit_file) if layout_audit_file else None,
    "screenshotDir": display(screenshot_dir) if screenshot_dir else None,
    "releaseEvidenceManifest": display(manifest_file) if manifest_file else None,
    "checks": checks,
    "failedChecks": failed,
    "holdChecks": holds,
    "nextCommands": next_commands,
}
output_file.write_text(json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
print(
    f"Production infrastructure local completion verify written: {output_file} status={status}",
    file=os.sys.stderr,
)
if status != "COMPLETE":
    raise SystemExit(1 if status != "BLOCKED" else 2)
PY
}

main "$@"
