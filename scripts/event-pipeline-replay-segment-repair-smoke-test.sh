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
experiment_id = "exp_segment_repair_smoke"
expected_scope = {
    "startTime": "2026-07-30T00:00:00",
    "endTime": "2026-07-30T01:00:00",
    "eventTypes": ["PAY_SUCCESS"],
    "includeEvents": True,
    "includeExposures": False,
    "segmentCount": 2,
}
requests_seen = []
plan_requests = 0


def base_response(data):
    return json.dumps({"code": 200, "message": "ok", "data": data}).encode("utf-8")


def replay_plan(unmaterialized_count):
    second_action = "REPAIR_MATERIALIZATION_SEGMENT" if unmaterialized_count else "NONE"
    return {
        "experimentId": experiment_id,
        "replayMode": "FILTERED_DERIVED_COPY_REPLAY",
        "fullDerivedReplay": False,
        "eventTypes": ["PAY_SUCCESS"],
        "includeEvents": True,
        "includeExposures": False,
        "eventCount": 120,
        "materializedEventCount": 120 - unmaterialized_count,
        "unmaterializedEventCount": unmaterialized_count,
        "exposureCount": 0,
        "materializedExposureCount": 0,
        "unmaterializedExposureCount": 0,
        "affectedCount": 120,
        "materializedCount": 120 - unmaterialized_count,
        "unmaterializedCount": unmaterialized_count,
        "groupCount": 2,
        "requestedSegmentCount": 2,
        "segmentCount": 2,
        "segmentRecoverySupported": True,
        "maxSegmentAffectedCount": 60,
        "maxSegmentUnmaterializedCount": unmaterialized_count,
        "groups": [],
        "segments": [
            {
                "segmentIndex": 0,
                "segmentKey": "segment-000",
                "startTime": "2026-07-30T00:00:00",
                "endTime": "2026-07-30T00:30:00",
                "eventCount": 60,
                "exposureCount": 0,
                "affectedCount": 60,
                "materializedCount": 60,
                "unmaterializedCount": 0,
                "recommendedAction": "NONE",
            },
            {
                "segmentIndex": 1,
                "segmentKey": "segment-001",
                "startTime": "2026-07-30T00:30:00.000000001",
                "endTime": "2026-07-30T01:00:00",
                "eventCount": 60,
                "exposureCount": 0,
                "affectedCount": 60,
                "materializedCount": 60 - unmaterialized_count,
                "unmaterializedCount": unmaterialized_count,
                "recommendedAction": second_action,
            },
        ],
        "generatedAt": "2026-07-30T00:00:11",
    }


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
        repair_path = (
            f"/api/analysis/experiment/{experiment_id}"
            "/events/replay/materialization/repair/segments/1"
        )
        if parsed.path == plan_path:
            plan_requests += 1
            self._write_json(base_response(replay_plan(2 if plan_requests == 1 else 0)))
            return
        if parsed.path == repair_path:
            self._write_json(base_response({
                "experimentId": experiment_id,
                "operation": "REPAIR_MATERIALIZATION",
                "operator": "segment-smoke",
                "status": "SUCCESS",
                "affectedCount": 60,
                "eventCount": 60,
                "exposureCount": 0,
                "groupCount": 2,
                "mabRewardCount": 60,
                "replayMode": "FILTERED_DERIVED_COPY_REPLAY",
                "scopeStartTime": "2026-07-30T00:30:00.000000001",
                "scopeEndTime": "2026-07-30T01:00:00",
                "eventTypes": ["PAY_SUCCESS"],
                "includeEvents": True,
                "includeExposures": False,
                "fullDerivedReplay": False,
                "message": "segment repair ok",
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
            "PISCES_ANALYSIS_API_KEY": "segment-smoke-key",
            "PISCES_EVENT_REPLAY_OUTPUT_FILE": str(summary_file),
            "PISCES_EVENT_REPLAY_REPAIR_MATERIALIZATION": "true",
            "PISCES_EVENT_REPLAY_REPAIR_SEGMENT_INDEX": "1",
            "PISCES_EVENT_REPLAY_FETCH_STATISTICS": "false",
            "PISCES_EVENT_REPLAY_FETCH_PLAN": "true",
            "PISCES_EVENT_REPLAY_REQUIRE_HEALTHY_AFTER": "true",
            "PISCES_EVENT_REPLAY_MAX_AFFECTED_PLAN": "120",
            "PISCES_EVENT_REPLAY_MAX_UNMATERIALIZED_PLAN": "0",
            "PISCES_EVENT_REPLAY_START_TIME": expected_scope["startTime"],
            "PISCES_EVENT_REPLAY_END_TIME": expected_scope["endTime"],
            "PISCES_EVENT_REPLAY_EVENT_TYPES": "PAY_SUCCESS",
            "PISCES_EVENT_REPLAY_INCLUDE_EVENTS": "true",
            "PISCES_EVENT_REPLAY_INCLUDE_EXPOSURES": "false",
            "PISCES_EVENT_REPLAY_SEGMENT_COUNT": "2",
            "PISCES_EVENT_REPLAY_OPERATOR": "segment-smoke",
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
    or item["path"].endswith("/events/replay/materialization/repair/segments/1")
]
if len(post_bodies) != 3:
    raise SystemExit(f"expected 3 segmented POST bodies, got {len(post_bodies)}: {requests_seen}")
for body in post_bodies:
    if body != expected_scope:
        raise SystemExit(f"unexpected replay segment scope body: {body}")
repair_paths = [
    item["path"]
    for item in requests_seen
    if item["path"].endswith("/events/replay/materialization/repair/segments/1")
]
if len(repair_paths) != 1:
    raise SystemExit(f"expected exactly one segmented repair request, got {repair_paths}")
if summary.get("status") != "PASS":
    raise SystemExit(f"summary status should be PASS: {summary.get('status')}")
if summary.get("repairSegmentIndex") != 1:
    raise SystemExit(f"repairSegmentIndex should be preserved: {summary.get('repairSegmentIndex')}")
if summary.get("replayScopeRequest") != expected_scope:
    raise SystemExit(f"summary replayScopeRequest mismatch: {summary.get('replayScopeRequest')}")
gates = {gate.get("name"): gate for gate in summary.get("gates", [])}
if gates.get("replay_plan_segments_generated", {}).get("status") != "PASS":
    raise SystemExit("segment generation gate should pass")
if gates.get("post_repair_replay_plan_unmaterialized_count", {}).get("status") != "PASS":
    raise SystemExit("post repair segment coverage gate should pass")
if summary.get("replayPlan", {}).get("maxSegmentUnmaterializedCount") != 2:
    raise SystemExit("pre-repair segment gap count should be 2")
if summary.get("replayPlanAfterRepair", {}).get("maxSegmentUnmaterializedCount") != 0:
    raise SystemExit("post-repair segment gap count should be 0")
print("event replay segmented repair smoke test passed")
PY
