# Runtime Plane Preprod Drill Record Sample

本文档是预发演练记录填写样例。真实发布时应复制 `runtime-plane-preprod-drill-record-template.md`，替换为当次发布的环境、证据路径、CI run 和审批信息。

## Release Metadata

| 字段 | 值 |
| --- | --- |
| Release ID | release-20260730-runtime-plane |
| 变更摘要 | runtime 配置长轮询、事件 replay 分段恢复和生产验收门禁发布 |
| 预发日期 | 2026-07-30 |
| 操作人 | release-operator |
| 代码版本 Git SHA | preprod-drill-sample-git-sha |
| CI Run URL | https://github.example.com/pisces/actions/runs/20260730001 |
| Release Package Report | target/pisces-runtime-release-package-check/report.json |
| Release Evidence Manifest | target/pisces-runtime-release-evidence-archive/20260730T090000Z-preprod-release-20260730-runtime-plane/manifest.json |
| Post-Release SLO Summary | target/pisces-runtime-post-release-slo-review/summary.json |
| Experiment Impact Sampling Summary | target/pisces-runtime-experiment-impact-sampling/summary.json |
| Staged Rollout Decision Summary | target/pisces-runtime-staged-rollout-decision/summary.json |
| Staged Rollout Acceptance Record | docs/operations/releases/release-20260730-runtime-plane-canary-acceptance.json |
| Production Acceptance Summary | target/pisces-runtime-production-acceptance/summary.json |
| Event Pipeline Replay Audit Summary | target/pisces-event-pipeline-replay-audit/summary.json |
| Incident Review Record | N/A |
| 预发环境 | preprod |
| Pisces 实例 | https://pre-a.example.com/api, https://pre-b.example.com/api |
| Redis 集群 / Channel | redis-preprod-a / pisces-config-change-preprod |
| Runtime API Key 来源 | Vault secret `pisces/preprod/runtime-key` |
| Management API Key 来源 | Vault secret `pisces/preprod/management-key` |

## 1. Release Package Gate

| 检查项 | 结果 | 证据 |
| --- | --- | --- |
| CI workflow `Runtime Plane Release Package` 通过 | PASS | https://github.example.com/pisces/actions/runs/20260730001 |
| `PISCES_RELEASE_PACKAGE_RUN_TESTS=true` | PASS | `report.json.runTests=true` |
| `PISCES_RELEASE_PACKAGE_REQUIRE_PROMTOOL=true` | PASS | `report.json.requirePromtool=true` |
| `report.json` 已上传为 CI artifact | PASS | artifact `runtime-plane-release-package-report` |
| `gitDirty=false` 或已解释 | PASS | `report.json.gitDirty=false` |

记录 `report.json` 关键字段：

```json
{
  "status": "PASS",
  "gitSha": "preprod-drill-sample-git-sha",
  "gitDirty": "false",
  "checksPassed": 305,
  "warnings": 0
}
```

## 2. Runtime Contract Smoke

| 接口 | 期望 | 结果 | 证据 |
| --- | --- | --- | --- |
| `GET /api/runtime/experiments/{id}/config` | 返回 `configVersion`、`groups`、`traffic`、事件/指标定义 | PASS | `runtime-config-smoke.json` |
| `GET /api/runtime/experiments/{id}/config/version?knownVersion=<version>&waitMillis=1000` | 返回 `currentVersion` 和 `changed` | PASS | `runtime-version-smoke.json` |
| `POST /api/traffic/assign/trace` | 返回 `groupId`、`source`、`reason`、`configVersion` | PASS | `traffic-assign-trace-smoke.json` |

## 3. Release Drill

执行命令：

```bash
PISCES_INSTANCE_URLS="https://pre-a.example.com/api,https://pre-b.example.com/api" \
PISCES_EXPERIMENT_ID="exp_checkout_001" \
PISCES_RUNTIME_API_KEY="<runtime-key-from-vault>" \
PISCES_MANAGEMENT_API_KEY="<management-key-from-vault>" \
PISCES_RELEASE_ACTION="publish-current" \
PISCES_VERSION_WAIT_MILLIS=25000 \
PISCES_CONVERGENCE_TIMEOUT_SECONDS=60 \
bash scripts/runtime-plane-release-drill.sh
```

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
| JSONL 文件 | target/pisces-runtime-capacity-baseline/preprod-exp_checkout_001.jsonl |
| 归档 manifest | target/pisces-runtime-baseline-archive/20260730T083000Z-preprod-exp_checkout_001-release-20260730-runtime-plane/manifest.json |
| Max errorRate | 0 |
| Max P95 ms | 120 |
| Max P99 ms | 190 |
| 与上一基线对比 | PASS |

## 5. Redis Fault Injection

| 阶段 | 期望 | 结果 | 证据 |
| --- | --- | --- | --- |
| baseline | Redis 正常，分流失败为 0 | PASS | `redis-fault-baseline.json` |
| during-fault | Redis 不可用时分流不整体失败，缓存错误指标增长 | PASS | `redis-fault-during.json` |
| recovery | Redis 恢复后缓存错误停止增长，延迟回落 | PASS | `redis-fault-recovery.json` |

## 6. Observability

| 观测项 | 结果 | 链接或截图 |
| --- | --- | --- |
| Prometheus scrape 正常 | PASS | https://prometheus.example.com/targets?search=pisces-preprod |
| Grafana runtime dashboard 已导入 | PASS | https://grafana.example.com/d/pisces-runtime-plane/preprod |
| `pisces_traffic_assignment_requests_total{result="ERROR"}` 不增长 | PASS | `prometheus-assignment-errors.png` |
| `pisces_traffic_cache_events_total{result="ERROR"}` 不持续增长 | PASS | `prometheus-cache-errors.png` |
| `pisces_config_change_broadcast_published_total{result="ERROR"}` 不增长 | PASS | `prometheus-broadcast-publish.png` |
| `pisces_config_change_broadcast_received_total{result="INVALID"}` 不增长 | PASS | `prometheus-broadcast-receive.png` |
| SDK 本地 `requestFailureCount`、`retryCount`、`staleExperimentConfigFallbackCount` 无异常增长 | PASS | `sdk-metrics-preprod.json` |

## 7. Decision

| 项 | 值 |
| --- | --- |
| 是否允许进入生产发布 | PROCEED |
| 必须先修复的问题 | 无 |
| 可接受风险 | canary 前 30 分钟保持 10% 流量，观察 assignment error、cache error 和 SLO |
| 回滚条件 | SLO 回看失败、影响面抽样失败、配置版本不收敛或 Redis 降级异常 |
| 审批人 | runtime-owner, business-owner |
| 审批时间 | 2026-07-30T17:30:00+08:00 |

## 8. Evidence Archive

执行命令：

```bash
PISCES_RELEASE_ID="release-20260730-runtime-plane" \
PISCES_RELEASE_PACKAGE_REPORT_FILE="target/pisces-runtime-release-package-check/report.json" \
PISCES_PREPROD_DRILL_RECORD_FILE="docs/operations/releases/release-20260730-runtime-plane.md" \
PISCES_CAPACITY_BASELINE_MANIFEST_FILE="target/pisces-runtime-baseline-archive/20260730T083000Z-preprod-exp_checkout_001-release-20260730-runtime-plane/manifest.json" \
PISCES_REDIS_FAULT_RECORD_FILE="target/pisces-runtime-redis-fault-injection/summary.json" \
PISCES_EVENT_REPLAY_AUDIT_SUMMARY_FILE="target/pisces-event-pipeline-replay-audit/summary.json" \
bash scripts/runtime-plane-release-evidence-archive.sh
```

| 归档项 | 值 |
| --- | --- |
| Archive directory | target/pisces-runtime-release-evidence-archive/20260730T090000Z-preprod-release-20260730-runtime-plane |
| Manifest path | target/pisces-runtime-release-evidence-archive/20260730T090000Z-preprod-release-20260730-runtime-plane/manifest.json |
| Manifest sha256 | 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef |
| Compare manifest | target/pisces-runtime-release-evidence-archive/previous/manifest.json |
| Compare status | PASS |
| Event replay audit summary | target/pisces-event-pipeline-replay-audit/summary.json |

## 9. Post-Release SLO Review

执行命令：

```bash
PISCES_RELEASE_EVIDENCE_MANIFEST_FILE="<release-evidence-manifest.json>" \
PISCES_POST_RELEASE_METRICS_FILE="<post-release-metrics.json>" \
bash scripts/runtime-plane-post-release-slo-review.sh
```

| 回看项 | 值 |
| --- | --- |
| Observation window | 2026-07-30T10:00:00Z / 2026-07-30T10:30:00Z |
| Summary path | target/pisces-runtime-post-release-slo-review/summary.json |
| SLO status | PASS |
| Failed gates | 0 |
| Follow-up action | 继续 canary 观察 |

## 10. Experiment Impact Sampling

执行命令：

```bash
PISCES_INSTANCE_URLS="https://prod-a.example.com/api,https://prod-b.example.com/api" \
PISCES_EXPERIMENT_IDS="exp_checkout_001" \
PISCES_RUNTIME_API_KEY="<runtime-key-from-vault>" \
bash scripts/runtime-plane-experiment-impact-sampling.sh
```

| 抽样项 | 值 |
| --- | --- |
| Summary path | target/pisces-runtime-experiment-impact-sampling/summary.json |
| Impact sampling status | PASS |
| Experiments | exp_checkout_001 |
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

执行命令：

```bash
PISCES_RELEASE_STAGE="canary" \
PISCES_RELEASE_EVIDENCE_MANIFEST_FILE="<release-evidence-manifest.json>" \
PISCES_POST_RELEASE_SLO_SUMMARY_FILE="<slo-summary.json>" \
PISCES_EXPERIMENT_IMPACT_SUMMARY_FILE="<impact-summary.json>" \
PISCES_ROLLOUT_ACCEPTANCE_RECORD_FILE="<acceptance-record.json>" \
PISCES_ROLLOUT_TARGET_TRAFFIC_PERCENT=10 \
PISCES_ROLLOUT_MAX_TRAFFIC_PERCENT=10 \
bash scripts/runtime-plane-staged-rollout-decision.sh
```

| 决策项 | 值 |
| --- | --- |
| Acceptance record path | docs/operations/releases/release-20260730-runtime-plane-canary-acceptance.json |
| Decision summary path | target/pisces-runtime-staged-rollout-decision/summary.json |
| Stage | canary |
| Decision | PROCEED |
| Failed or hold gates | 0 |
| Next action | 推进 canary 10% |

## 13. Rollback Decision Drill

| 演练项 | 值 |
| --- | --- |
| Drill record path | docs/operations/releases/release-20260730-runtime-plane-rollback-drill.md |
| HOLD scenario result | PASS |
| ROLLBACK scenario result | PASS |
| 发布平台是否能识别退出码 `2` | PASS |

## 14. Event Pipeline Replay Audit

| 审计项 | 值 |
| --- | --- |
| Summary path | target/pisces-event-pipeline-replay-audit/summary.json |
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
| Production acceptance record | docs/operations/releases/release-20260730-runtime-plane-production-acceptance.json |
| Production acceptance summary | target/pisces-runtime-production-acceptance/summary.json |
| Decision | ACCEPT |
| Required event replay evidence | true |
| Required trace sampling | true |
| Failed or hold gates | 0 |
| Final archive location | s3://release-evidence/pisces/release-20260730-runtime-plane/ |

## 16. Preprod Record Check

```bash
PISCES_PREPROD_DRILL_RECORD_FILE="docs/operations/releases/release-20260730-runtime-plane.md" \
PISCES_RELEASE_ID="release-20260730-runtime-plane" \
PISCES_EXPECTED_GIT_SHA="preprod-drill-sample-git-sha" \
PISCES_PREPROD_REQUIRE_EVIDENCE_ARCHIVE=true \
PISCES_PREPROD_REQUIRE_EVENT_REPLAY=true \
bash scripts/runtime-plane-preprod-drill-record-check.sh
```

| 检查项 | 值 |
| --- | --- |
| Preprod record check summary | target/pisces-runtime-preprod-drill-record-check/summary.json |
| Preprod record check status | PASS |
