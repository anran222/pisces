#!/usr/bin/env bash

set -euo pipefail

require_command() {
  command -v "$1" >/dev/null 2>&1 || {
    printf 'Missing command: %s\n' "$1" >&2
    exit 1
  }
}

require_command python3

smoke_root="${PISCES_LOCAL_EVIDENCE_COLLECT_SMOKE_ROOT:-target/pisces-production-infrastructure-local-evidence-collect-smoke}"
run_id="$(date -u '+%Y%m%dT%H%M%SZ')-$$"
release_id="local-evidence-collect-smoke-$run_id"
workspace="$smoke_root/$release_id/workspace"
summary_file="$smoke_root/$release_id/collection-plan.json"

(
  unset PISCES_EXPERIMENT_ID PISCES_LOCAL_SERVICE_SUMMARY_FILE PISCES_LOCAL_COLLECT_REDIS_FAULT_MODE PISCES_REDIS_DOCKER_CONTAINER PISCES_FAULT_CONFIRM
  PISCES_RELEASE_ID="$release_id" \
  PISCES_EXPERIMENT_ID="collect-smoke-experiment" \
  PISCES_LOCAL_COLLECT_PLAN_ONLY=true \
  PISCES_LOCAL_EVIDENCE_WORKSPACE_DIR="$workspace" \
  PISCES_LOCAL_EVIDENCE_COLLECT_OUTPUT_FILE="$summary_file" \
  bash scripts/production-infrastructure-local-evidence-collect.sh >/dev/null
)

python3 - "$summary_file" "$workspace" <<'PY'
import json
import sys
from pathlib import Path

summary_file = Path(sys.argv[1])
workspace = Path(sys.argv[2])
summary = json.loads(summary_file.read_text(encoding="utf-8"))

if summary.get("summaryType") != "pisces-production-infrastructure-local-evidence-collect":
    raise SystemExit("collector summary type mismatch")
if summary.get("status") != "PLAN_ONLY":
    raise SystemExit(f"collector smoke should be plan-only: {summary.get('status')}")

required_evidence = {
    "preprodDrillRecord",
    "capacityBaselineManifest",
    "redisFaultRecord",
    "eventReplayAuditSummary",
    "postReleaseMetrics",
    "experimentImpactSummary",
    "rolloutAcceptanceRecord",
    "productionAcceptanceRecord",
    "screenshotDir",
}
evidence = summary.get("evidence") or {}
missing = sorted(required_evidence - set(evidence))
if missing:
    raise SystemExit(f"collector plan missing evidence entries: {missing}")
workspace_evidence = required_evidence - {"screenshotDir"}
if not all(str(workspace) in evidence[name] for name in workspace_evidence):
    raise SystemExit("collector plan evidence paths should point into the workspace")
if not str(evidence.get("screenshotDir") or "").endswith("pisces-web/target/screenshots/core-functions-current"):
    raise SystemExit("collector plan should record frontend screenshot evidence directory")
if summary.get("screenshotDir") != evidence.get("screenshotDir"):
    raise SystemExit("collector plan top-level screenshotDir should match evidence screenshotDir")

commands = summary.get("commands") or []
for expected in (
    "bash scripts/production-infrastructure-local-service.sh start",
    "scripts/runtime-plane-release-drill.sh",
    "scripts/runtime-plane-capacity-baseline.sh",
    "scripts/event-pipeline-replay-audit.sh",
    "scripts/runtime-plane-experiment-impact-sampling.sh",
    "scripts/production-infrastructure-local-evidence-validate.sh",
):
    if expected not in commands:
        raise SystemExit(f"collector plan missing command: {expected}")
if not any(str(workspace) in command and command.endswith("/run-local-closeout.sh") for command in commands):
    raise SystemExit("collector plan missing generated closeout wrapper command")
validate_wrapper = summary.get("validateWrapper") or ""
closeout_wrapper = summary.get("closeoutWrapper") or ""
if str(workspace) not in validate_wrapper or not validate_wrapper.endswith("/validate-local-evidence.sh"):
    raise SystemExit("collector plan validate wrapper path mismatch")
if str(workspace) not in closeout_wrapper or not closeout_wrapper.endswith("/run-local-closeout.sh"):
    raise SystemExit("collector plan closeout wrapper path mismatch")
next_commands = summary.get("nextCommands") or []
if not any(str(workspace) in command and command.endswith("/run-local-closeout.sh") for command in next_commands):
    raise SystemExit("collector plan should expose next closeout command")

local_service = summary.get("localService") or {}
if "target/pisces-production-infrastructure-local-service/summary.json" not in (
    local_service.get("summaryFile") or ""
):
    raise SystemExit("collector plan should point to the local service summary file")
if local_service.get("requiredBeforeCollection") is not True:
    raise SystemExit("collector plan should require local service summary by default")
if local_service.get("requiredStatus") != "HEALTHY":
    raise SystemExit("collector plan should require HEALTHY local service status")
if local_service.get("requiredApiKeyStatus") != "configured":
    raise SystemExit("collector plan should require configured Qianwen key")
if local_service.get("requiredHealthStatus") != "UP":
    raise SystemExit("collector plan should require UP actuator health")

redis_fault = summary.get("redisFault") or {}
if redis_fault.get("mode") != "manual":
    raise SystemExit(f"collector plan should default Redis fault mode to manual: {redis_fault}")
if redis_fault.get("skip") is not False:
    raise SystemExit(f"collector plan should not skip Redis fault drill by default: {redis_fault}")
if redis_fault.get("faultConfirm") is not False:
    raise SystemExit(f"collector default plan should not auto-confirm external Redis faults: {redis_fault}")

if any((workspace / name).exists() for name in (
    "preprod-drill-record.md",
    "capacity-baseline-manifest.json",
    "production-acceptance-record.json",
)):
    raise SystemExit("plan-only collector must not create final evidence files")
PY

auto_release_id="${release_id}-auto-existing"
auto_workspace="$smoke_root/$auto_release_id/workspace"
auto_summary_file="$smoke_root/$auto_release_id/collection-plan.json"

(
  unset PISCES_EXPERIMENT_ID PISCES_LOCAL_SERVICE_SUMMARY_FILE PISCES_LOCAL_COLLECT_REDIS_FAULT_MODE PISCES_REDIS_DOCKER_CONTAINER PISCES_FAULT_CONFIRM
  PISCES_RELEASE_ID="$auto_release_id" \
  PISCES_LOCAL_COLLECT_PLAN_ONLY=true \
  PISCES_LOCAL_EVIDENCE_WORKSPACE_DIR="$auto_workspace" \
  PISCES_LOCAL_EVIDENCE_COLLECT_OUTPUT_FILE="$auto_summary_file" \
  bash scripts/production-infrastructure-local-evidence-collect.sh >/dev/null
)

python3 - "$auto_summary_file" "$auto_workspace" <<'PY'
import json
import sys
from pathlib import Path

summary_file = Path(sys.argv[1])
workspace = Path(sys.argv[2])
summary = json.loads(summary_file.read_text(encoding="utf-8"))
auto_demo = summary.get("autoDemo") or {}
selection = summary.get("experimentSelection") or {}

if summary.get("experimentId") is not None:
    raise SystemExit("auto-selection plan should not invent an experiment ID")
if selection.get("appId") != "shop-app":
    raise SystemExit(f"auto-selection should default to shop-app: {selection}")
if selection.get("autoSelectExisting") is not True:
    raise SystemExit(f"existing experiment selection should be enabled by default: {selection}")
if auto_demo.get("enabled") is not False:
    raise SystemExit("demo fallback should be disabled by default")
if auto_demo.get("willCreateExperiment") is not False:
    raise SystemExit("default collection must not plan demo data creation")
commands = summary.get("commands") or []
if "GET /experiments?appId=shop-app" not in commands:
    raise SystemExit("auto-selection plan should query real shop-app experiments")
if any("generator/demo" in command for command in commands):
    raise SystemExit("default auto-selection plan must not include demo generation")
closeout_wrapper = summary.get("closeoutWrapper") or ""
if str(workspace) not in closeout_wrapper or not closeout_wrapper.endswith("/run-local-closeout.sh"):
    raise SystemExit("auto-selection plan closeout wrapper path mismatch")

if any((workspace / name).exists() for name in (
    "preprod-drill-record.md",
    "capacity-baseline-manifest.json",
    "production-acceptance-record.json",
)):
    raise SystemExit("auto-selection plan-only collector must not create final evidence files")
PY

demo_release_id="${release_id}-explicit-demo"
demo_workspace="$smoke_root/$demo_release_id/workspace"
demo_summary_file="$smoke_root/$demo_release_id/collection-plan.json"

(
  unset PISCES_EXPERIMENT_ID PISCES_LOCAL_SERVICE_SUMMARY_FILE PISCES_LOCAL_COLLECT_REDIS_FAULT_MODE PISCES_REDIS_DOCKER_CONTAINER PISCES_FAULT_CONFIRM
  PISCES_RELEASE_ID="$demo_release_id" \
  PISCES_LOCAL_COLLECT_PLAN_ONLY=true \
  PISCES_LOCAL_COLLECT_AUTO_SELECT_EXISTING=false \
  PISCES_LOCAL_COLLECT_AUTO_DEMO=true \
  PISCES_LOCAL_COLLECT_DEMO_CASE=unqualified \
  PISCES_LOCAL_EVIDENCE_WORKSPACE_DIR="$demo_workspace" \
  PISCES_LOCAL_EVIDENCE_COLLECT_OUTPUT_FILE="$demo_summary_file" \
  bash scripts/production-infrastructure-local-evidence-collect.sh >/dev/null
)

python3 - "$demo_summary_file" <<'PY'
import json
import sys
from pathlib import Path

summary = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
auto_demo = summary.get("autoDemo") or {}
selection = summary.get("experimentSelection") or {}
if selection.get("autoSelectExisting") is not False:
    raise SystemExit("explicit demo plan should disable existing experiment selection")
if auto_demo.get("enabled") is not True or auto_demo.get("case") != "unqualified":
    raise SystemExit(f"explicit demo fallback should preserve its configuration: {auto_demo}")
if auto_demo.get("willCreateExperiment") is not True:
    raise SystemExit("explicit demo plan should state that it will create demo data")
if not any("POST /experiments/generator/demo" in command for command in summary.get("commands") or []):
    raise SystemExit("explicit demo plan should include the demo generator command")
PY

docker_release_id="${release_id}-docker-redis"
docker_workspace="$smoke_root/$docker_release_id/workspace"
docker_summary_file="$smoke_root/$docker_release_id/collection-plan.json"

(
  unset PISCES_EXPERIMENT_ID PISCES_LOCAL_SERVICE_SUMMARY_FILE PISCES_LOCAL_COLLECT_REDIS_FAULT_MODE PISCES_REDIS_DOCKER_CONTAINER PISCES_FAULT_CONFIRM
  PISCES_RELEASE_ID="$docker_release_id" \
  PISCES_EXPERIMENT_ID="collect-smoke-experiment" \
  PISCES_LOCAL_COLLECT_PLAN_ONLY=true \
  PISCES_LOCAL_COLLECT_REDIS_FAULT_MODE=docker-stop \
  PISCES_REDIS_DOCKER_CONTAINER=pisces-local-smoke-redis-1 \
  PISCES_FAULT_CONFIRM=true \
  PISCES_LOCAL_EVIDENCE_WORKSPACE_DIR="$docker_workspace" \
  PISCES_LOCAL_EVIDENCE_COLLECT_OUTPUT_FILE="$docker_summary_file" \
  bash scripts/production-infrastructure-local-evidence-collect.sh >/dev/null
)

python3 - "$docker_summary_file" <<'PY'
import json
import sys
from pathlib import Path

summary = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
redis_fault = summary.get("redisFault") or {}

if redis_fault.get("mode") != "docker-stop":
    raise SystemExit(f"collector should preserve docker Redis fault mode: {redis_fault}")
if redis_fault.get("dockerContainer") != "pisces-local-smoke-redis-1":
    raise SystemExit(f"collector should record Redis Docker container: {redis_fault}")
if redis_fault.get("faultConfirm") is not True:
    raise SystemExit(f"collector should record Docker fault confirmation: {redis_fault}")
if redis_fault.get("skip") is not False:
    raise SystemExit(f"collector should still require Redis fault drill: {redis_fault}")
PY

printf 'production infrastructure local evidence collect smoke test passed\n'
