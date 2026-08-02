#!/usr/bin/env bash

set -euo pipefail

require_command() {
  command -v "$1" >/dev/null 2>&1 || {
    printf 'Missing command: %s\n' "$1" >&2
    exit 1
  }
}

require_command python3

smoke_root="${PISCES_PREPROD_DRILL_RECORD_SMOKE_ROOT:-target/pisces-runtime-preprod-drill-record-smoke}"
run_id="$(date -u '+%Y%m%dT%H%M%SZ')-$$"
input_dir="$smoke_root/input-$run_id"
output_dir="$smoke_root/output-$run_id"
release_id="release-preprod-drill-smoke-$run_id"
git_sha="preprod-drill-smoke-git-sha"

mkdir -p "$input_dir" "$output_dir"

python3 - "$input_dir" "$release_id" "$git_sha" <<'PY'
import json
import sys
from pathlib import Path

input_dir = Path(sys.argv[1])
release_id = sys.argv[2]
git_sha = sys.argv[3]

(input_dir / "report.json").write_text(json.dumps({
    "reportType": "pisces-runtime-plane-release-package-check",
    "status": "PASS",
    "gitSha": git_sha,
    "gitDirty": "false",
    "runTests": "true",
    "requirePromtool": "true",
    "requireRuby": "true",
    "checksPassed": 305,
    "warnings": 0,
}, indent=2) + "\n", encoding="utf-8")

(input_dir / "capacity-manifest.json").write_text(json.dumps({
    "environment": "preprod",
    "experimentId": "exp_preprod_drill_smoke",
    "releaseId": release_id,
    "gitSha": git_sha,
    "maxErrorRate": 0,
    "maxP95Ms": 120,
    "maxP99Ms": 190,
}, indent=2) + "\n", encoding="utf-8")

(input_dir / "event-replay-summary.json").write_text(json.dumps({
    "summaryType": "pisces-event-pipeline-replay-audit",
    "status": "PASS",
    "experimentId": "exp_preprod_drill_smoke",
    "executeReplay": False,
    "repairMaterialization": True,
    "repairSegmentIndex": 1,
    "replayScopeRequest": {
        "startTime": "2026-07-30T00:00:00",
        "endTime": "2026-07-30T01:00:00",
        "eventTypes": ["PAY_SUCCESS"],
        "includeEvents": True,
        "includeExposures": False,
        "segmentCount": 2,
    },
    "replayPlan": {
        "requestedSegmentCount": 2,
        "segmentCount": 2,
        "segmentRecoverySupported": True,
        "maxSegmentAffectedCount": 80,
        "maxSegmentUnmaterializedCount": 3,
        "segments": [
            {
                "segmentIndex": 0,
                "segmentKey": "segment-000",
                "affectedCount": 40,
                "unmaterializedCount": 0,
                "recommendedAction": "NONE",
            },
            {
                "segmentIndex": 1,
                "segmentKey": "segment-001",
                "affectedCount": 40,
                "unmaterializedCount": 3,
                "recommendedAction": "REPAIR_MATERIALIZATION_SEGMENT",
            },
        ],
    },
    "replayPlanAfterRepair": {
        "requestedSegmentCount": 2,
        "segmentCount": 2,
        "segmentRecoverySupported": True,
        "maxSegmentAffectedCount": 80,
        "maxSegmentUnmaterializedCount": 0,
        "segments": [
            {
                "segmentIndex": 0,
                "segmentKey": "segment-000",
                "affectedCount": 40,
                "unmaterializedCount": 0,
                "recommendedAction": "NONE",
            },
            {
                "segmentIndex": 1,
                "segmentKey": "segment-001",
                "affectedCount": 40,
                "unmaterializedCount": 0,
                "recommendedAction": "NONE",
            },
        ],
    },
    "gates": [
        {
            "name": "replay_plan_segments_generated",
            "status": "PASS",
        },
        {
            "name": "repair_materialization_operation_success",
            "status": "PASS",
        },
        {
            "name": "post_repair_replay_plan_unmaterialized_count",
            "status": "PASS",
        },
    ],
}, indent=2) + "\n", encoding="utf-8")

record = f"""# Runtime Plane Preprod Drill Record

## Release Metadata

| 字段 | 值 |
| --- | --- |
| Release ID | {release_id} |
| 变更摘要 | runtime plane preprod drill smoke |
| 预发日期 | 2026-07-30 |
| 操作人 | release-operator |
| 代码版本 Git SHA | {git_sha} |
| CI Run URL | https://github.example.com/pisces/actions/runs/preprod-drill-smoke |
| Release Package Report | {input_dir / "report.json"} |
| Release Evidence Manifest | target/pisces-runtime-release-evidence-archive/preprod-drill-smoke/manifest.json |
| Post-Release SLO Summary | target/pisces-runtime-post-release-slo-review/summary.json |
| Experiment Impact Sampling Summary | target/pisces-runtime-experiment-impact-sampling/summary.json |
| Staged Rollout Decision Summary | target/pisces-runtime-staged-rollout-decision/summary.json |
| Staged Rollout Acceptance Record | target/pisces-runtime-staged-rollout-acceptance/record.json |
| Production Acceptance Summary | target/pisces-runtime-production-acceptance/summary.json |
| Event Pipeline Replay Audit Summary | {input_dir / "event-replay-summary.json"} |
| Incident Review Record | N/A |
| 预发环境 | preprod |
| Pisces 实例 | https://pre-a.example.com/api, https://pre-b.example.com/api |
| Redis 集群 / Channel | redis-preprod-a / pisces-config-change-preprod |
| Runtime API Key 来源 | Vault secret pisces/preprod/runtime-key |
| Management API Key 来源 | Vault secret pisces/preprod/management-key |

## 1. Release Package Gate

| 检查项 | 结果 | 证据 |
| --- | --- | --- |
| CI workflow `Runtime Plane Release Package` 已通过 | PASS | https://github.example.com/pisces/actions/runs/preprod-drill-smoke |
| `PISCES_RELEASE_PACKAGE_RUN_TESTS=true` | PASS | report.json.runTests=true |
| `PISCES_RELEASE_PACKAGE_REQUIRE_PROMTOOL=true` | PASS | report.json.requirePromtool=true |
| `report.json` 已上传为 CI artifact | PASS | runtime-plane-release-package-report |
| `gitDirty=false` 或已解释 | PASS | report.json.gitDirty=false |

## 2. Runtime Contract Smoke

| 接口 | 期望 | 结果 | 证据 |
| --- | --- | --- | --- |
| `GET /api/runtime/experiments/{{id}}/config` | 返回 `configVersion`、`groups`、`traffic`、事件/指标定义 | PASS | runtime-config-smoke.json |
| `GET /api/runtime/experiments/{{id}}/config/version?knownVersion=<version>&waitMillis=1000` | 返回 `currentVersion` 和 `changed` | PASS | runtime-version-smoke.json |
| `POST /api/traffic/assign/trace` | 返回 `groupId`、`source`、`reason`、`configVersion` | PASS | traffic-assign-trace-smoke.json |

## 3. Release Drill

| 指标 | 值 |
| --- | --- |
| Baseline configVersion | 6 |
| Target configVersion | 7 |
| 收敛耗时 | 18s |
| Assignment requests | 1000 |
| Assignment concurrency | 32 |
| Assignment failed | 0 |
| Assignment P95 / P99 | 118ms / 180ms |
| 异常摘要 | 无 |

## 4. Capacity Baseline

| 字段 | 值 |
| --- | --- |
| JSONL 文件 | target/pisces-runtime-capacity-baseline/preprod.jsonl |
| 归档 manifest | {input_dir / "capacity-manifest.json"} |
| Max errorRate | 0 |
| Max P95 ms | 120 |
| Max P99 ms | 190 |
| 与上一基线对比 | PASS |

## 5. Redis Fault Injection

| 阶段 | 期望 | 结果 | 证据 |
| --- | --- | --- | --- |
| baseline | Redis 正常，分流失败为 0 | PASS | redis-fault-baseline.json |
| during-fault | Redis 不可用时分流不整体失败，缓存错误指标增长 | PASS | redis-fault-during.json |
| recovery | Redis 恢复后缓存错误停止增长，延迟回落 | PASS | redis-fault-recovery.json |

## 6. Observability

| 观测项 | 结果 | 链接或截图 |
| --- | --- | --- |
| Prometheus scrape 正常 | PASS | prometheus-targets.png |
| Grafana runtime dashboard 已导入 | PASS | grafana-runtime-dashboard.png |
| `pisces_traffic_assignment_requests_total{{result="ERROR"}}` 不增长 | PASS | prometheus-assignment-errors.png |
| `pisces_traffic_cache_events_total{{result="ERROR"}}` 不持续增长 | PASS | prometheus-cache-errors.png |
| `pisces_config_change_broadcast_published_total{{result="ERROR"}}` 不增长 | PASS | prometheus-broadcast-publish.png |
| `pisces_config_change_broadcast_received_total{{result="INVALID"}}` 不增长 | PASS | prometheus-broadcast-receive.png |
| SDK 本地 `requestFailureCount`、`retryCount`、`staleExperimentConfigFallbackCount` 无异常增长 | PASS | sdk-metrics-preprod.json |

## 7. Decision

| 项 | 值 |
| --- | --- |
| 是否允许进入生产发布 | PROCEED |
| 必须先修复的问题 | 无 |
| 可接受风险 | canary 10% 观察 30 分钟 |
| 回滚条件 | SLO 回看失败、影响面抽样失败或配置版本不收敛 |
| 审批人 | runtime-owner, business-owner |
| 审批时间 | 2026-07-30T17:30:00+08:00 |

## 8. Evidence Archive

| 归档项 | 值 |
| --- | --- |
| Archive directory | target/pisces-runtime-release-evidence-archive/preprod-drill-smoke |
| Manifest path | target/pisces-runtime-release-evidence-archive/preprod-drill-smoke/manifest.json |
| Manifest sha256 | 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef |
| Compare manifest | target/pisces-runtime-release-evidence-archive/previous/manifest.json |
| Compare status | PASS |
| Event replay audit summary | {input_dir / "event-replay-summary.json"} |

## 9. Post-Release SLO Review

| 回看项 | 值 |
| --- | --- |
| Observation window | 2026-07-30T10:00:00Z / 2026-07-30T10:30:00Z |
| Summary path | target/pisces-runtime-post-release-slo-review/summary.json |
| SLO status | PASS |
| Failed gates | 0 |
| Follow-up action | 继续 canary |

## 10. Experiment Impact Sampling

| 抽样项 | 值 |
| --- | --- |
| Summary path | target/pisces-runtime-experiment-impact-sampling/summary.json |
| Impact sampling status | PASS |
| Experiments | exp_preprod_drill_smoke |
| Instances | prod-a, prod-b |
| Trace enabled | true |
| Failed gates | 0 |

## 11. Incident Review

| 复盘项 | 值 |
| --- | --- |
| Incident ID | N/A |
| Review record path | N/A |
| Owner | N/A |
| Close criteria status | N/A |

## 12. Staged Rollout Decision

| 决策项 | 值 |
| --- | --- |
| Acceptance record path | target/pisces-runtime-staged-rollout-acceptance/record.json |
| Decision summary path | target/pisces-runtime-staged-rollout-decision/summary.json |
| Stage | canary |
| Decision | PROCEED |
| Failed or hold gates | 0 |
| Next action | 推进 canary 10% |

## 13. Rollback Decision Drill

| 演练项 | 值 |
| --- | --- |
| Drill record path | target/pisces-runtime-rollback-drill/record.md |
| HOLD scenario result | PASS |
| ROLLBACK scenario result | PASS |
| 发布平台是否能识别退出码 `2` | PASS |

## 14. Event Pipeline Replay Audit

| 审计项 | 值 |
| --- | --- |
| Summary path | {input_dir / "event-replay-summary.json"} |
| Execute replay | false |
| Repair materialization | true |
| Replay scope request | start=2026-07-30T00:00:00, end=2026-07-30T01:00:00, eventTypes=PAY_SUCCESS, segmentCount=2 |
| Segment count | 2 |
| Repair segment index | 1 |
| Max segment affected count | 80 |
| Max segment unmaterialized before / after | 3 / 0 |
| Replay audit status | PASS |
| Before pipeline status | HEALTHY |
| After pipeline status | HEALTHY |
| Replay plan unmaterialized count | 3 |
| Post-repair replay plan unmaterialized count | 0 |
| Rebuilt event / exposure / MAB reward count | 0 / 0 / 0 |
| Failed gates | 0 |

## 15. Production Acceptance

| 验收项 | 值 |
| --- | --- |
| Production acceptance record | target/pisces-runtime-production-acceptance/record.json |
| Production acceptance summary | target/pisces-runtime-production-acceptance/summary.json |
| Decision | ACCEPT |
| Required event replay evidence | true |
| Required trace sampling | true |
| Failed or hold gates | 0 |
| Final archive location | s3://release-evidence/pisces/{release_id}/ |
"""

(input_dir / "preprod-record.md").write_text(record, encoding="utf-8")
PY

summary_file="$output_dir/summary.json"
PISCES_PREPROD_DRILL_RECORD_FILE="$input_dir/preprod-record.md" \
PISCES_PREPROD_DRILL_RECORD_OUTPUT_FILE="$summary_file" \
PISCES_RELEASE_ID="$release_id" \
PISCES_EXPECTED_GIT_SHA="$git_sha" \
PISCES_RELEASE_PACKAGE_REPORT_FILE="$input_dir/report.json" \
PISCES_CAPACITY_BASELINE_MANIFEST_FILE="$input_dir/capacity-manifest.json" \
PISCES_EVENT_REPLAY_AUDIT_SUMMARY_FILE="$input_dir/event-replay-summary.json" \
PISCES_PREPROD_REQUIRE_EVIDENCE_ARCHIVE=true \
PISCES_PREPROD_REQUIRE_EVENT_REPLAY=true \
bash scripts/runtime-plane-preprod-drill-record-check.sh >/dev/null

python3 - "$summary_file" <<'PY'
import json
import sys
from pathlib import Path

summary = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
if summary.get("summaryType") != "pisces-runtime-plane-preprod-drill-record-check":
    raise SystemExit("preprod drill record summary type mismatch")
if summary.get("status") != "PASS":
    raise SystemExit(f"preprod drill record status must be PASS: {summary.get('status')}")
bad_gates = [
    gate for gate in summary.get("gates", [])
    if gate.get("status") not in {"PASS", "SKIP"}
]
if bad_gates:
    raise SystemExit(f"preprod drill record has blocking gates: {bad_gates}")
requirements = summary.get("requirements") or {}
for key in ("strictPackageCi", "evidenceArchive", "capacityBaseline", "redisFault", "observability", "eventReplay"):
    if requirements.get(key) is not True:
        raise SystemExit(f"preprod drill record requirement not enforced: {key}")
PY

printf 'runtime plane preprod drill record smoke test passed\n'
