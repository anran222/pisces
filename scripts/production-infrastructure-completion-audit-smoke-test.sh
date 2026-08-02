#!/usr/bin/env bash

set -euo pipefail

require_command() {
  command -v "$1" >/dev/null 2>&1 || {
    printf 'Missing command: %s\n' "$1" >&2
    exit 1
  }
}

require_command python3

smoke_root="${PISCES_COMPLETION_AUDIT_SMOKE_ROOT:-target/pisces-production-infrastructure-completion-audit-smoke}"
run_id="$(date -u '+%Y%m%dT%H%M%SZ')-$$"
input_dir="$smoke_root/input-$run_id"
output_dir="$smoke_root/output-$run_id"
release_id="release-completion-audit-smoke-$run_id"
git_sha="completion-audit-smoke-git-sha"

mkdir -p "$input_dir/screenshots" "$output_dir"

python3 - "$input_dir" "$release_id" "$git_sha" <<'PY'
import json
import binascii
import struct
import sys
import zlib
from pathlib import Path

input_dir = Path(sys.argv[1])
release_id = sys.argv[2]
git_sha = sys.argv[3]


def png_chunk(chunk_type, data):
    chunk_name = chunk_type.encode("ascii")
    return (
        struct.pack(">I", len(data))
        + chunk_name
        + data
        + struct.pack(">I", binascii.crc32(chunk_name + data) & 0xFFFFFFFF)
    )


def write_png(path, width=1440, height=900):
    rows = []
    for y in range(height):
        row = bytearray()
        for x in range(width):
            row.extend((
                (x + y) % 256,
                (x * 3 + y * 5) % 256,
                (x * 7 + y * 11) % 256,
            ))
        rows.append(b"\x00" + bytes(row))
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    payload = (
        b"\x89PNG\r\n\x1a\n"
        + png_chunk("IHDR", ihdr)
        + png_chunk("IDAT", zlib.compress(b"".join(rows), 6))
        + png_chunk("IEND", b"")
    )
    path.write_bytes(payload)

(input_dir / "release-package-report.json").write_text(json.dumps({
    "reportType": "pisces-runtime-plane-release-package-check",
    "status": "PASS",
    "gitSha": git_sha,
    "gitDirty": "false",
    "runTests": "true",
    "requirePromtool": "true",
    "requireRuby": "true",
    "checksPassed": 316,
    "warnings": 0,
}, indent=2) + "\n", encoding="utf-8")

(input_dir / "preprod-record-check-summary.json").write_text(json.dumps({
    "summaryType": "pisces-runtime-plane-preprod-drill-record-check",
    "summaryVersion": 1,
    "status": "PASS",
    "releaseId": release_id,
    "gitSha": git_sha,
    "requirements": {
        "strictPackageCi": True,
        "evidenceArchive": True,
        "capacityBaseline": True,
        "redisFault": True,
        "observability": True,
        "eventReplay": True,
    },
    "gates": [
        {
            "name": "preprod_record_gate",
            "status": "PASS",
        },
    ],
}, indent=2) + "\n", encoding="utf-8")

(input_dir / "release-evidence-manifest.json").write_text(json.dumps({
    "manifestType": "pisces-runtime-plane-release-evidence",
    "manifestVersion": 1,
    "releaseId": release_id,
    "environment": "prod",
    "releasePackage": {
        "status": "PASS",
        "gitSha": git_sha,
        "gitDirty": "false",
        "runTests": "true",
        "requirePromtool": "true",
        "requireRuby": "true",
    },
    "evidence": {
        "releasePackageReport": {
            "sha256": "a" * 64,
        },
        "preprodDrillRecord": {
            "sha256": "b" * 64,
        },
        "capacityBaselineManifest": {
            "sha256": "c" * 64,
        },
        "redisFaultRecord": {
            "sha256": "d" * 64,
        },
        "eventPipelineReplayAuditSummary": {
            "sha256": "e" * 64,
        },
    },
    "capacityBaseline": {
        "environment": "prod",
        "experimentId": "exp_completion_audit_smoke",
        "releaseId": release_id,
        "gitSha": git_sha,
        "maxErrorRate": 0,
        "maxP95Ms": 120,
        "maxP99Ms": 190,
    },
    "eventPipelineReplayAudit": {
        "status": "PASS",
        "experimentId": "exp_completion_audit_smoke",
        "repairSegmentIndex": 1,
        "failedGateCount": 0,
        "segmentSummary": {
            "segmentGateStatus": "PASS",
            "segmentCount": 2,
            "maxSegmentUnmaterializedCountBefore": 3,
            "maxSegmentUnmaterializedCountAfter": 0,
        },
    },
}, indent=2) + "\n", encoding="utf-8")

(input_dir / "production-acceptance-summary.json").write_text(json.dumps({
    "summaryType": "pisces-runtime-plane-production-acceptance-check",
    "summaryVersion": 1,
    "status": "PASS",
    "decision": "ACCEPT",
    "releaseId": release_id,
    "environment": "prod",
    "stage": "full",
    "gates": [
        {
            "name": "production_acceptance_gate",
            "status": "PASS",
        },
    ],
}, indent=2) + "\n", encoding="utf-8")

screenshot_names = [
    "01-ai-center-priority-workspace.png",
    "02-experiment-workbench-list.png",
    "03-experiment-detail-data-nav.png",
    "03b-experiment-config-version-governance.png",
    "03c-experiment-conclusion.png",
    "03c2-experiment-approval.png",
    "03d-experiment-runtime-structure.png",
    "03e-experiment-statistics-mab.png",
    "04-data-pipeline-status-and-running-replay.png",
    "05-data-pipeline-replay-plan.png",
    "05b-data-pipeline-segment-repair.png",
    "06-ai-decision-workspace.png",
    "07-application-space-governance.png",
    "08-ai-design-structured-draft.png",
]
for screenshot_name in screenshot_names:
    write_png(input_dir / "screenshots" / screenshot_name)

(input_dir / "screenshots" / "layout-audit.json").write_text(json.dumps({
    "summaryType": "pisces-web-core-layout-audit",
    "summaryVersion": 1,
    "status": "PASS",
    "strict": True,
    "screenshotCount": len(screenshot_names),
    "enforcedCount": 11,
    "failedCount": 0,
    "failedScreens": [],
    "records": [],
}, indent=2) + "\n", encoding="utf-8")
PY

summary_file="$output_dir/summary.json"
PISCES_COMPLETION_REQUIRE_REAL_ENV_EVIDENCE=true \
PISCES_COMPLETION_RELEASE_PACKAGE_REPORT_FILE="$input_dir/release-package-report.json" \
PISCES_COMPLETION_PREPROD_RECORD_CHECK_SUMMARY_FILE="$input_dir/preprod-record-check-summary.json" \
PISCES_COMPLETION_RELEASE_EVIDENCE_MANIFEST_FILE="$input_dir/release-evidence-manifest.json" \
PISCES_COMPLETION_PRODUCTION_ACCEPTANCE_SUMMARY_FILE="$input_dir/production-acceptance-summary.json" \
PISCES_COMPLETION_SCREENSHOT_DIR="$input_dir/screenshots" \
PISCES_COMPLETION_AUDIT_OUTPUT_FILE="$summary_file" \
bash scripts/production-infrastructure-completion-audit.sh >/dev/null

closeout_dir="$output_dir/closeout"
PISCES_COMPLETION_RELEASE_PACKAGE_REPORT_FILE="$input_dir/release-package-report.json" \
PISCES_COMPLETION_PREPROD_RECORD_CHECK_SUMMARY_FILE="$input_dir/preprod-record-check-summary.json" \
PISCES_COMPLETION_RELEASE_EVIDENCE_MANIFEST_FILE="$input_dir/release-evidence-manifest.json" \
PISCES_COMPLETION_PRODUCTION_ACCEPTANCE_SUMMARY_FILE="$input_dir/production-acceptance-summary.json" \
PISCES_COMPLETION_SCREENSHOT_DIR="$input_dir/screenshots" \
PISCES_PRODUCTION_CLOSEOUT_DIR="$closeout_dir" \
bash scripts/production-infrastructure-closeout.sh >/dev/null

python3 - "$summary_file" "$closeout_dir" <<'PY'
import json
import sys
from pathlib import Path

summary = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
closeout_dir = Path(sys.argv[2])
if summary.get("summaryType") != "pisces-production-infrastructure-completion-audit":
    raise SystemExit("completion audit summary type mismatch")
if summary.get("status") != "PASS":
    raise SystemExit(f"completion audit status must be PASS: {summary.get('status')}")
if summary.get("completionStatus") != "COMPLETE":
    raise SystemExit(f"completionStatus must be COMPLETE: {summary.get('completionStatus')}")
if summary.get("staticStatus") != "PASS":
    raise SystemExit("staticStatus must be PASS")
if summary.get("realEnvironmentStatus") != "PASS":
    raise SystemExit("realEnvironmentStatus must be PASS")
if not (summary.get("evidence") or {}).get("layoutAudit"):
    raise SystemExit("completion audit summary must expose layoutAudit evidence")
bad_gates = [
    gate for gate in summary.get("gates", [])
    if gate.get("status") not in {"PASS", "SKIP"}
]
if bad_gates:
    raise SystemExit(f"completion audit has blocking gates: {bad_gates}")
required_gate_names = {
    "core frontend layout audit file",
    "core frontend layout audit contract",
    "core frontend layout audit status",
    "core frontend layout audit failed count",
    "core frontend layout audit enforced screens",
}
gate_names = {gate.get("name") for gate in summary.get("gates", [])}
missing_gate_names = sorted(required_gate_names - gate_names)
if missing_gate_names:
    raise SystemExit(f"completion audit missing layout gates: {missing_gate_names}")
closeout_summary = json.loads((closeout_dir / "completion-summary.json").read_text(encoding="utf-8"))
if closeout_summary.get("completionStatus") != "COMPLETE":
    raise SystemExit("closeout summary must be COMPLETE")
closeout_report = (closeout_dir / "closeout-report.md").read_text(encoding="utf-8")
if "Verdict: **COMPLETE**" not in closeout_report:
    raise SystemExit("closeout report must contain COMPLETE verdict")
PY

dirty_input_dir="$smoke_root/dirty-input-$run_id"
cp -R "$input_dir" "$dirty_input_dir"
python3 - "$dirty_input_dir" <<'PY'
import json
import sys
from pathlib import Path

input_dir = Path(sys.argv[1])

release_report_file = input_dir / "release-package-report.json"
release_report = json.loads(release_report_file.read_text(encoding="utf-8"))
release_report["gitDirty"] = "true"
release_report_file.write_text(json.dumps(release_report, indent=2) + "\n", encoding="utf-8")

manifest_file = input_dir / "release-evidence-manifest.json"
manifest = json.loads(manifest_file.read_text(encoding="utf-8"))
manifest["releasePackage"]["gitDirty"] = "true"
manifest_file.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
PY

dirty_strict_summary_file="$output_dir/dirty-strict-summary.json"
set +e
PISCES_COMPLETION_REQUIRE_REAL_ENV_EVIDENCE=true \
PISCES_COMPLETION_RELEASE_PACKAGE_REPORT_FILE="$dirty_input_dir/release-package-report.json" \
PISCES_COMPLETION_PREPROD_RECORD_CHECK_SUMMARY_FILE="$dirty_input_dir/preprod-record-check-summary.json" \
PISCES_COMPLETION_RELEASE_EVIDENCE_MANIFEST_FILE="$dirty_input_dir/release-evidence-manifest.json" \
PISCES_COMPLETION_PRODUCTION_ACCEPTANCE_SUMMARY_FILE="$dirty_input_dir/production-acceptance-summary.json" \
PISCES_COMPLETION_SCREENSHOT_DIR="$dirty_input_dir/screenshots" \
PISCES_COMPLETION_AUDIT_OUTPUT_FILE="$dirty_strict_summary_file" \
bash scripts/production-infrastructure-completion-audit.sh >/dev/null
dirty_strict_status=$?
set -e

if [[ "$dirty_strict_status" -eq 0 ]]; then
  printf 'completion audit should reject dirty release evidence by default\n' >&2
  exit 1
fi

dirty_local_summary_file="$output_dir/dirty-local-summary.json"
PISCES_COMPLETION_REQUIRE_REAL_ENV_EVIDENCE=true \
PISCES_COMPLETION_REQUIRE_CLEAN_GIT=false \
PISCES_COMPLETION_TARGET_ENVIRONMENT=prod \
PISCES_COMPLETION_RELEASE_PACKAGE_REPORT_FILE="$dirty_input_dir/release-package-report.json" \
PISCES_COMPLETION_PREPROD_RECORD_CHECK_SUMMARY_FILE="$dirty_input_dir/preprod-record-check-summary.json" \
PISCES_COMPLETION_RELEASE_EVIDENCE_MANIFEST_FILE="$dirty_input_dir/release-evidence-manifest.json" \
PISCES_COMPLETION_PRODUCTION_ACCEPTANCE_SUMMARY_FILE="$dirty_input_dir/production-acceptance-summary.json" \
PISCES_COMPLETION_SCREENSHOT_DIR="$dirty_input_dir/screenshots" \
PISCES_COMPLETION_AUDIT_OUTPUT_FILE="$dirty_local_summary_file" \
bash scripts/production-infrastructure-completion-audit.sh >/dev/null

python3 - "$dirty_strict_summary_file" "$dirty_local_summary_file" <<'PY'
import json
import sys
from pathlib import Path

strict_summary = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
local_summary = json.loads(Path(sys.argv[2]).read_text(encoding="utf-8"))

if strict_summary.get("status") != "FAIL":
    raise SystemExit(f"dirty strict audit should fail: {strict_summary.get('status')}")
if strict_summary.get("requireCleanGit") is not True:
    raise SystemExit("dirty strict audit should require clean git")
strict_dirty_gates = [
    gate for gate in strict_summary.get("gates", [])
    if gate.get("name") in {
        "real release package gitDirty",
        "real release package gitDirty in manifest",
    }
]
if len(strict_dirty_gates) != 2 or any(gate.get("status") != "FAIL" for gate in strict_dirty_gates):
    raise SystemExit(f"dirty strict audit should fail both dirty gates: {strict_dirty_gates}")

if local_summary.get("status") != "PASS":
    raise SystemExit(f"dirty local audit should pass: {local_summary.get('status')}")
if local_summary.get("completionStatus") != "COMPLETE":
    raise SystemExit("dirty local audit should be complete")
if local_summary.get("requireCleanGit") is not False:
    raise SystemExit("dirty local audit should record requireCleanGit=false")
local_dirty_gates = [
    gate for gate in local_summary.get("gates", [])
    if gate.get("name") in {
        "real release package gitDirty",
        "real release package gitDirty in manifest",
    }
]
if len(local_dirty_gates) != 2 or any(gate.get("status") != "PASS" for gate in local_dirty_gates):
    raise SystemExit(f"dirty local audit should pass both dirty gates: {local_dirty_gates}")
PY

printf 'production infrastructure completion audit smoke test passed\n'
printf 'production infrastructure closeout smoke test passed\n'
