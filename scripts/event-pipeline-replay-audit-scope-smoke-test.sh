#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

python3 - "$repo_root" <<'PY'
import json
import os
import subprocess
import sys
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse

repo_root = Path(sys.argv[1])
experiment_id = "exp_scope_smoke"
expected_scope = {
    "startTime": "2026-07-30T00:00:00",
    "endTime": "2026-07-30T01:00:00",
    "eventTypes": ["PAY_SUCCESS", "PRODUCT_VIEW"],
    "includeEvents": True,
    "includeExposures": False,
}
requests_seen = []
plan_requests = 0


def base_response(data):
    return json.dumps({"code": 200, "message": "ok", "data": data}).encode("utf-8")


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _read_json_body(self):
        content_length = int(self.headers.get("Content-Length", "0"))
        if content_length == 0:
            return {}
        return json.loads(self.rfile.read(content_length).decode("utf-8"))

    def _write_json(self, payload):
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self):
        parsed = urlparse(self.path)
        expected_status = f"/api/analysis/experiment/{experiment_id}/event-pipeline"
        if parsed.path != expected_status:
            self.send_error(404)
            return
        self._write_json(base_response({
            "experimentId": experiment_id,
            "totalCount": 0,
            "pendingCount": 0,
            "processingCount": 0,
            "retryCount": 0,
            "doneCount": 0,
            "deadCount": 0,
            "rejectedCount": 0,
            "unfinishedCount": 0,
            "maxPendingSeconds": 0,
            "healthy": True,
            "status": "DONE",
            "generatedAt": "2026-07-30T00:00:10",
        }))

    def do_POST(self):
        global plan_requests
        parsed = urlparse(self.path)
        body = self._read_json_body()
        requests_seen.append({"path": parsed.path, "body": body})
        plan_path = f"/api/analysis/experiment/{experiment_id}/events/replay/plan"
        repair_path = f"/api/analysis/experiment/{experiment_id}/events/replay/materialization/repair"
        if parsed.path == plan_path:
            plan_requests += 1
            unmaterialized_count = 2 if plan_requests == 1 else 0
            self._write_json(base_response({
                "experimentId": experiment_id,
                "replayMode": "FILTERED_DERIVED_COPY_REPLAY",
                "fullDerivedReplay": False,
                "eventTypes": ["PAY_SUCCESS", "PRODUCT_VIEW"],
                "includeEvents": True,
                "includeExposures": False,
                "eventCount": 2,
                "materializedEventCount": 0 if plan_requests == 1 else 2,
                "unmaterializedEventCount": unmaterialized_count,
                "exposureCount": 0,
                "materializedExposureCount": 0,
                "unmaterializedExposureCount": 0,
                "affectedCount": 2,
                "materializedCount": 0 if plan_requests == 1 else 2,
                "unmaterializedCount": unmaterialized_count,
                "groupCount": 1,
                "groups": [],
                "generatedAt": "2026-07-30T00:00:11",
            }))
            return
        if parsed.path == repair_path:
            self._write_json(base_response({
                "experimentId": experiment_id,
                "operation": "REPAIR_MATERIALIZATION",
                "operator": "scope-smoke",
                "status": "SUCCESS",
                "affectedCount": 2,
                "eventCount": 2,
                "exposureCount": 0,
                "groupCount": 1,
                "mabRewardCount": 1,
                "replayMode": "FILTERED_DERIVED_COPY_REPLAY",
                "scopeStartTime": "2026-07-30T00:00:00",
                "scopeEndTime": "2026-07-30T01:00:00",
                "eventTypes": ["PAY_SUCCESS", "PRODUCT_VIEW"],
                "includeEvents": True,
                "includeExposures": False,
                "fullDerivedReplay": False,
                "message": "scoped repair ok",
                "operatedAt": "2026-07-30T00:00:12",
            }))
            return
        self.send_error(404)

    def log_message(self, _format, *_args):
        return


server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
thread = threading.Thread(target=server.serve_forever, daemon=True)
thread.start()
try:
    with tempfile.TemporaryDirectory() as temp_dir:
        summary_file = Path(temp_dir) / "summary.json"
        env = os.environ.copy()
        env.update({
            "PISCES_API_BASE_URL": f"http://127.0.0.1:{server.server_port}/api",
            "PISCES_EXPERIMENT_ID": experiment_id,
            "PISCES_ANALYSIS_API_KEY": "scope-smoke-key",
            "PISCES_EVENT_REPLAY_OUTPUT_FILE": str(summary_file),
            "PISCES_EVENT_REPLAY_REPAIR_MATERIALIZATION": "true",
            "PISCES_EVENT_REPLAY_FETCH_STATISTICS": "false",
            "PISCES_EVENT_REPLAY_FETCH_PLAN": "true",
            "PISCES_EVENT_REPLAY_REQUIRE_HEALTHY_AFTER": "true",
            "PISCES_EVENT_REPLAY_MAX_AFFECTED_PLAN": "10",
            "PISCES_EVENT_REPLAY_MAX_UNMATERIALIZED_PLAN": "0",
            "PISCES_EVENT_REPLAY_START_TIME": expected_scope["startTime"],
            "PISCES_EVENT_REPLAY_END_TIME": expected_scope["endTime"],
            "PISCES_EVENT_REPLAY_EVENT_TYPES": "PAY_SUCCESS, PRODUCT_VIEW",
            "PISCES_EVENT_REPLAY_INCLUDE_EVENTS": "true",
            "PISCES_EVENT_REPLAY_INCLUDE_EXPOSURES": "false",
            "PISCES_EVENT_REPLAY_OPERATOR": "scope-smoke",
        })
        subprocess.run(
            ["bash", "scripts/event-pipeline-replay-audit.sh"],
            cwd=repo_root,
            env=env,
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        summary = json.loads(summary_file.read_text(encoding="utf-8"))
finally:
    server.shutdown()
    server.server_close()

post_bodies = [
    item["body"]
    for item in requests_seen
    if item["path"].endswith("/events/replay/plan")
    or item["path"].endswith("/events/replay/materialization/repair")
]
if len(post_bodies) != 3:
    raise SystemExit(f"expected 3 scoped POST bodies, got {len(post_bodies)}: {requests_seen}")
for body in post_bodies:
    if body != expected_scope:
        raise SystemExit(f"unexpected replay scope body: {body}")
if summary.get("status") != "PASS":
    raise SystemExit(f"summary status should be PASS: {summary.get('status')}")
if summary.get("replayScopeRequest") != expected_scope:
    raise SystemExit(f"summary replayScopeRequest mismatch: {summary.get('replayScopeRequest')}")
gates = {gate.get("name"): gate for gate in summary.get("gates", [])}
if gates.get("replay_plan_affected_count", {}).get("status") != "PASS":
    raise SystemExit("plan affected count gate should pass")
if gates.get("post_repair_replay_plan_unmaterialized_count", {}).get("status") != "PASS":
    raise SystemExit("post repair coverage gate should pass")
print("event replay audit scoped request smoke test passed")
PY
