#!/usr/bin/env bash

set -euo pipefail

require_command() {
  command -v "$1" >/dev/null 2>&1 || {
    printf 'Missing command: %s\n' "$1" >&2
    exit 1
  }
}

require_command node
require_command npm
require_command python3

smoke_root="${PISCES_LOCAL_FRONTEND_EVIDENCE_SMOKE_ROOT:-target/pisces-production-infrastructure-local-frontend-evidence-smoke}"
run_id="$(date -u '+%Y%m%dT%H%M%SZ')-$$"
workspace="$smoke_root/frontend-evidence-smoke-$run_id"
web_dir="$workspace/pisces-web"
plan_summary="$workspace/plan-summary.json"
pass_summary="$workspace/pass-summary.json"
missing_summary="$workspace/missing-summary.json"
screenshot_dir="$workspace/screenshots"
server_port="$(
  python3 <<'PY'
import socket

with socket.socket() as sock:
    sock.bind(("127.0.0.1", 0))
    print(sock.getsockname()[1])
PY
)"

mkdir -p "$web_dir"

cat >"$web_dir/package.json" <<'JSON'
{
  "name": "pisces-web-frontend-evidence-smoke",
  "private": true,
  "version": "1.0.0",
  "scripts": {
    "dev": "node dev-server.cjs",
    "capture:core": "node capture-core-functions.cjs",
    "audit:prod-high": "node audit-prod-high.cjs"
  }
}
JSON

cat >"$web_dir/dev-server.cjs" <<'JS'
const http = require('http');

let host = '127.0.0.1';
let port = 3040;
for (let index = 2; index < process.argv.length; index += 1) {
  if (process.argv[index] === '--host') {
    host = process.argv[index + 1];
    index += 1;
  } else if (process.argv[index] === '--port') {
    port = Number(process.argv[index + 1]);
    index += 1;
  }
}

const server = http.createServer((request, response) => {
  response.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
  response.end('<html><body>pisces web smoke</body></html>');
});

server.listen(port, host, () => {
  console.log(`smoke frontend listening on ${host}:${port}`);
});

process.on('SIGTERM', () => server.close(() => process.exit(0)));
process.on('SIGINT', () => server.close(() => process.exit(0)));
JS

cat >"$web_dir/audit-prod-high.cjs" <<'JS'
console.log('smoke production high severity audit passed');
JS

cat >"$web_dir/capture-core-functions.cjs" <<'JS'
const fs = require('fs');
const path = require('path');

const outDir = process.env.PISCES_WEB_SCREENSHOT_DIR;
if (!outDir) {
  throw new Error('PISCES_WEB_SCREENSHOT_DIR is required');
}
const layoutAuditFile = process.env.PISCES_WEB_LAYOUT_AUDIT_FILE;
if (!layoutAuditFile) {
  throw new Error('PISCES_WEB_LAYOUT_AUDIT_FILE is required');
}
fs.mkdirSync(outDir, { recursive: true });

const png = Buffer.from(
  '89504e470d0a1a0a0000000d4948445200000001000000010802000000907753de0000000c49444154789c6360f8cf00000301010118dd8db00000000049454e44ae426082',
  'hex',
);
const names = [
  '01-ai-center-priority-workspace.png',
  '02-experiment-workbench-list.png',
  '03-experiment-detail-data-nav.png',
  '03b-experiment-config-version-governance.png',
  '03c-experiment-conclusion.png',
  '03c2-experiment-approval.png',
  '03d-experiment-runtime-structure.png',
  '03e-experiment-statistics-mab.png',
  '04-data-pipeline-status-and-running-replay.png',
  '05-data-pipeline-replay-plan.png',
  '05b-data-pipeline-segment-repair.png',
  '06-ai-decision-workspace.png',
  '07-application-space-governance.png',
  '08-ai-design-structured-draft.png',
  '09-variant-lab-tongyi-model-evidence.png',
];
for (const name of names) {
  fs.writeFileSync(path.join(outDir, name), png);
}
fs.writeFileSync(layoutAuditFile, JSON.stringify({
  summaryType: 'pisces-web-core-layout-audit',
  summaryVersion: 1,
  status: 'PASS',
  strict: true,
  enforcedCount: 8,
  screenshotCount: names.length,
  failedCount: 0,
  failedScreens: [],
  records: names.map(name => ({
    fileName: name,
    status: 'PASS',
    enforced: name === '09-variant-lab-tongyi-model-evidence.png',
  })),
}, null, 2) + '\n');
console.log(`wrote ${names.length} smoke screenshots to ${outDir}`);
JS

PISCES_WEB_DIR="$web_dir" \
PISCES_WEB_PORT="$server_port" \
PISCES_LOCAL_FRONTEND_OUTPUT_FILE="$plan_summary" \
PISCES_LOCAL_FRONTEND_DRY_RUN=true \
bash scripts/production-infrastructure-local-frontend-evidence.sh >/dev/null

python3 - "$plan_summary" <<'PY'
import json
import sys
from pathlib import Path

summary = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
if summary.get("summaryType") != "pisces-production-infrastructure-local-frontend-evidence":
    raise SystemExit("frontend evidence summary type mismatch")
if summary.get("status") != "PLAN_ONLY":
    raise SystemExit(f"expected PLAN_ONLY: {summary.get('status')}")
if summary.get("dryRun") is not True:
    raise SystemExit("dry-run summary should set dryRun=true")
if any(step.get("status") != "NOT_RUN" for step in summary.get("steps") or []):
    raise SystemExit("dry-run frontend evidence must not run steps")
if not any("capture:core" in command for command in summary.get("commands") or []):
    raise SystemExit("dry-run summary should include capture command")
PY

PISCES_WEB_DIR="$web_dir" \
PISCES_WEB_PORT="$server_port" \
PISCES_COMPLETION_SCREENSHOT_DIR="$screenshot_dir" \
PISCES_LOCAL_FRONTEND_OUTPUT_FILE="$pass_summary" \
bash scripts/production-infrastructure-local-frontend-evidence.sh >/dev/null

python3 - "$pass_summary" <<'PY'
import json
import sys
from pathlib import Path

summary = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
if summary.get("status") != "PASS":
    raise SystemExit(f"expected PASS: {summary.get('status')}")
if summary.get("screenshotCount") != 15:
    raise SystemExit(f"expected 15 screenshots: {summary.get('screenshotCount')}")
layout_audit = summary.get("layoutAudit") or {}
if layout_audit.get("status") != "PASS":
    raise SystemExit(f"expected layout audit PASS: {layout_audit}")
if layout_audit.get("enforcedCount") != 8:
    raise SystemExit(f"expected layout audit enforced count: {layout_audit}")
required = {
    item.get("fileName"): item
    for item in summary.get("requiredScreenshots") or []
}
variant_evidence = required.get("09-variant-lab-tongyi-model-evidence.png")
if not variant_evidence:
    raise SystemExit("variant lab model evidence screenshot should be required")
if variant_evidence.get("present") is not True or variant_evidence.get("layoutStatus") != "PASS":
    raise SystemExit(f"variant lab model evidence screenshot should pass: {variant_evidence}")
if summary.get("missingRequiredScreenshots"):
    raise SystemExit(f"required screenshots should not be missing: {summary.get('missingRequiredScreenshots')}")
if summary.get("startedServer") is not True:
    raise SystemExit("frontend evidence smoke should start its temporary server")
steps = {step.get("name"): step for step in summary.get("steps") or []}
for name in (
    "frontend production high severity audit",
    "frontend dev server",
    "frontend core screenshot capture",
):
    if steps.get(name, {}).get("status") != "PASS":
        raise SystemExit(f"expected step PASS: {name}: {steps.get(name)}")
PY

set +e
PISCES_WEB_DIR="$workspace/missing-pisces-web" \
PISCES_LOCAL_FRONTEND_OUTPUT_FILE="$missing_summary" \
bash scripts/production-infrastructure-local-frontend-evidence.sh >/dev/null
missing_status=$?
set -e

if [[ "$missing_status" -eq 0 ]]; then
  printf 'frontend evidence should fail when web dir is missing\n' >&2
  exit 1
fi

python3 - "$missing_summary" <<'PY'
import json
import sys
from pathlib import Path

summary = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
if summary.get("status") != "FRONTEND_WEB_DIR_MISSING":
    raise SystemExit(f"expected FRONTEND_WEB_DIR_MISSING: {summary.get('status')}")
PY

printf 'production infrastructure local frontend evidence smoke test passed\n'
