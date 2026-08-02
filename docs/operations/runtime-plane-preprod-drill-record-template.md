# Runtime Plane Preprod Drill Record Template

本文档是运行时分流平面预发演练记录模板。每次影响 runtime 配置接口、SDK 配置解析、分流热路径、Redis 缓存/广播或观测资产的发布，都应复制本模板形成发布证据。

## Release Metadata

| 字段 | 值 |
| --- | --- |
| Release ID |  |
| 变更摘要 |  |
| 预发日期 |  |
| 操作人 |  |
| 代码版本 Git SHA |  |
| CI Run URL |  |
| Release Package Report | `target/pisces-runtime-release-package-check/report.json` |
| Release Evidence Manifest |  |
| Post-Release SLO Summary |  |
| Experiment Impact Sampling Summary |  |
| Staged Rollout Decision Summary |  |
| Staged Rollout Acceptance Record |  |
| Production Acceptance Summary |  |
| Event Pipeline Replay Audit Summary |  |
| Incident Review Record |  |
| 预发环境 |  |
| Pisces 实例 |  |
| Redis 集群 / Channel |  |
| Runtime API Key 来源 | 只记录密钥来源，不记录明文 |
| Management API Key 来源 | 只记录密钥来源，不记录明文 |

## 1. Release Package Gate

| 检查项 | 结果 | 证据 |
| --- | --- | --- |
| CI workflow `Runtime Plane Release Package` 通过 |  |  |
| `PISCES_RELEASE_PACKAGE_RUN_TESTS=true` |  |  |
| `PISCES_RELEASE_PACKAGE_REQUIRE_PROMTOOL=true` |  |  |
| `report.json` 已上传为 CI artifact |  |  |
| `gitDirty=false` 或已解释 |  |  |

记录 `report.json` 关键字段：

```json
{
  "status": "PASS",
  "gitSha": "",
  "gitDirty": "false",
  "checksPassed": 0,
  "warnings": 0
}
```

## 2. Runtime Contract Smoke

| 接口 | 期望 | 结果 | 证据 |
| --- | --- | --- | --- |
| `GET /api/runtime/experiments/{id}/config` | 返回 `configVersion`、`groups`、`traffic`、事件/指标定义 |  |  |
| `GET /api/runtime/experiments/{id}/config/version?knownVersion=<version>&waitMillis=1000` | 返回 `currentVersion` 和 `changed` |  |  |
| `POST /api/traffic/assign/trace` | 返回 `groupId`、`source`、`reason`、`configVersion` |  |  |

## 3. Release Drill

执行命令：

```bash
PISCES_INSTANCE_URLS="<preprod-instance-a>/api,<preprod-instance-b>/api" \
PISCES_EXPERIMENT_ID="<experimentId>" \
PISCES_RUNTIME_API_KEY="<runtime-key>" \
PISCES_MANAGEMENT_API_KEY="<management-key>" \
PISCES_RELEASE_ACTION="publish-current" \
PISCES_VERSION_WAIT_MILLIS=25000 \
PISCES_CONVERGENCE_TIMEOUT_SECONDS=60 \
bash scripts/runtime-plane-release-drill.sh
```

| 指标 | 值 |
| --- | --- |
| Baseline configVersion |  |
| Target configVersion |  |
| 收敛耗时 |  |
| Assignment requests |  |
| Assignment concurrency |  |
| Assignment failed |  |
| Assignment P95 / P99 |  |
| 异常摘要 |  |

## 4. Capacity Baseline

| 字段 | 值 |
| --- | --- |
| JSONL 文件 |  |
| 归档 manifest |  |
| Max errorRate |  |
| Max P95 ms |  |
| Max P99 ms |  |
| 与上一基线对比 |  |

## 5. Redis Fault Injection

| 阶段 | 期望 | 结果 | 证据 |
| --- | --- | --- | --- |
| baseline | Redis 正常，分流失败为 0 |  |  |
| during-fault | Redis 不可用时分流不整体失败，缓存错误指标增长 |  |  |
| recovery | Redis 恢复后缓存错误停止增长，延迟回落 |  |  |

## 6. Observability

| 观测项 | 结果 | 链接或截图 |
| --- | --- | --- |
| Prometheus scrape 正常 |  |  |
| Grafana runtime dashboard 已导入 |  |  |
| `pisces_traffic_assignment_requests_total{result="ERROR"}` 不增长 |  |  |
| `pisces_traffic_cache_events_total{result="ERROR"}` 不持续增长 |  |  |
| `pisces_config_change_broadcast_published_total{result="ERROR"}` 不增长 |  |  |
| `pisces_config_change_broadcast_received_total{result="INVALID"}` 不增长 |  |  |
| SDK 本地 `requestFailureCount`、`retryCount`、`staleExperimentConfigFallbackCount` 无异常增长 |  |  |

## 7. Decision

| 项 | 值 |
| --- | --- |
| 是否允许进入生产发布 |  |
| 必须先修复的问题 |  |
| 可接受风险 |  |
| 回滚条件 |  |
| 审批人 |  |
| 审批时间 |  |

## 8. Evidence Archive

执行命令：

```bash
PISCES_RELEASE_ID="<releaseId>" \
PISCES_RELEASE_PACKAGE_REPORT_FILE="target/pisces-runtime-release-package-check/report.json" \
PISCES_PREPROD_DRILL_RECORD_FILE="<this-record-file>" \
PISCES_CAPACITY_BASELINE_MANIFEST_FILE="<capacity-baseline-manifest.json>" \
PISCES_REDIS_FAULT_RECORD_FILE="<redis-fault-record>" \
PISCES_EVENT_REPLAY_AUDIT_SUMMARY_FILE="<event-replay-audit-summary.json>" \
bash scripts/runtime-plane-release-evidence-archive.sh
```

| 归档项 | 值 |
| --- | --- |
| Archive directory |  |
| Manifest path |  |
| Manifest sha256 |  |
| Compare manifest |  |
| Compare status |  |
| Event replay audit summary |  |

## 9. Post-Release SLO Review

执行命令：

```bash
PISCES_RELEASE_EVIDENCE_MANIFEST_FILE="<release-evidence-manifest.json>" \
PISCES_POST_RELEASE_METRICS_FILE="<post-release-metrics.json>" \
bash scripts/runtime-plane-post-release-slo-review.sh
```

| 回看项 | 值 |
| --- | --- |
| Observation window |  |
| Summary path |  |
| SLO status |  |
| Failed gates |  |
| Follow-up action |  |

## 10. Experiment Impact Sampling

执行命令：

```bash
PISCES_INSTANCE_URLS="<prod-instance-a>/api,<prod-instance-b>/api" \
PISCES_EXPERIMENT_IDS="<experimentIdA>,<experimentIdB>" \
PISCES_RUNTIME_API_KEY="<runtime-key>" \
bash scripts/runtime-plane-experiment-impact-sampling.sh
```

| 抽样项 | 值 |
| --- | --- |
| Summary path |  |
| Impact sampling status |  |
| Experiments |  |
| Instances |  |
| Trace enabled |  |
| Failed gates |  |

## 11. Incident Review

如果发布后 SLO 回看或实验级影响面抽样未通过，复制 `docs/operations/runtime-plane-post-release-incident-review-template.md` 建立复盘记录。

| 复盘项 | 值 |
| --- | --- |
| Incident ID |  |
| Review record path |  |
| Owner |  |
| Close criteria status |  |

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
| Acceptance record path |  |
| Decision summary path |  |
| Stage |  |
| Decision | PROCEED / HOLD / ROLLBACK |
| Failed or hold gates |  |
| Next action |  |

## 13. Rollback Decision Drill

按 `docs/operations/runtime-plane-rollback-decision-drill-template.md` 至少演练一次 `HOLD` 和一次 `ROLLBACK` 决策。

| 演练项 | 值 |
| --- | --- |
| Drill record path |  |
| HOLD scenario result |  |
| ROLLBACK scenario result |  |
| 发布平台是否能识别退出码 `2` |  |

## 14. Event Pipeline Replay Audit

如果本次变更影响事件采集、异步物化、统计派生数据或 MAB 奖励，执行：

```bash
PISCES_API_BASE_URL="<preprod-instance>/api" \
PISCES_EXPERIMENT_ID="<experimentId>" \
PISCES_ANALYSIS_API_KEY="<analysis-or-management-key>" \
PISCES_EVENT_REPLAY_MAX_UNMATERIALIZED_PLAN=0 \
bash scripts/event-pipeline-replay-audit.sh
```

如只读计划发现缺账本，改用受控修复模式并复查修复后覆盖：

```bash
PISCES_API_BASE_URL="<preprod-instance>/api" \
PISCES_EXPERIMENT_ID="<experimentId>" \
PISCES_ANALYSIS_API_KEY="<analysis-or-management-key>" \
PISCES_EVENT_REPLAY_REPAIR_MATERIALIZATION=true \
PISCES_EVENT_REPLAY_MAX_UNMATERIALIZED_PLAN=0 \
bash scripts/event-pipeline-replay-audit.sh
```

如只需要修复特定窗口或事件类型的缺账本，补充 scope 变量：

```bash
PISCES_EVENT_REPLAY_START_TIME="<start-local-date-time>" \
PISCES_EVENT_REPLAY_END_TIME="<end-local-date-time>" \
PISCES_EVENT_REPLAY_EVENT_TYPES="PAY_SUCCESS" \
PISCES_EVENT_REPLAY_INCLUDE_EVENTS=true \
PISCES_EVENT_REPLAY_INCLUDE_EXPOSURES=false \
bash scripts/event-pipeline-replay-audit.sh
```

| 审计项 | 值 |
| --- | --- |
| Summary path |  |
| Execute replay |  |
| Repair materialization |  |
| Replay scope request |  |
| Segment count |  |
| Repair segment index |  |
| Max segment affected count |  |
| Max segment unmaterialized before / after |  |
| Replay audit status |  |
| Before pipeline status |  |
| After pipeline status |  |
| Replay plan unmaterialized count |  |
| Post-repair replay plan unmaterialized count |  |
| Rebuilt event / exposure / MAB reward count |  |
| Failed gates |  |

## 15. Production Acceptance

full rollout 或 post-release 观察窗口结束后执行最终生产验收：

```bash
PISCES_RELEASE_EVIDENCE_MANIFEST_FILE="<release-evidence-manifest.json>" \
PISCES_POST_RELEASE_SLO_SUMMARY_FILE="<slo-summary.json>" \
PISCES_EXPERIMENT_IMPACT_SUMMARY_FILE="<impact-summary.json>" \
PISCES_STAGED_ROLLOUT_DECISION_SUMMARY_FILE="<staged-rollout-decision-summary.json>" \
PISCES_PRODUCTION_ACCEPTANCE_RECORD_FILE="<production-acceptance-record.json>" \
bash scripts/runtime-plane-production-acceptance-check.sh
```

| 验收项 | 值 |
| --- | --- |
| Production acceptance record |  |
| Production acceptance summary |  |
| Decision | ACCEPT / HOLD / ROLLBACK |
| Required event replay evidence |  |
| Required trace sampling |  |
| Failed or hold gates |  |
| Final archive location |  |

## 16. Preprod Record Check

归档发布证据前后都应执行预发记录校验。归档前可先不要求 evidence archive 字段；归档后应设置 `PISCES_PREPROD_REQUIRE_EVIDENCE_ARCHIVE=true` 复查 manifest 路径和 sha256。涉及事件管道时设置 `PISCES_PREPROD_REQUIRE_EVENT_REPLAY=true`。

```bash
PISCES_PREPROD_DRILL_RECORD_FILE="<this-record-file>" \
PISCES_RELEASE_ID="<releaseId>" \
PISCES_EXPECTED_GIT_SHA="<git-sha>" \
PISCES_RELEASE_PACKAGE_REPORT_FILE="target/pisces-runtime-release-package-check/report.json" \
PISCES_CAPACITY_BASELINE_MANIFEST_FILE="<capacity-baseline-manifest.json>" \
PISCES_EVENT_REPLAY_AUDIT_SUMMARY_FILE="<event-replay-audit-summary.json>" \
PISCES_PREPROD_REQUIRE_EVIDENCE_ARCHIVE=true \
PISCES_PREPROD_REQUIRE_EVENT_REPLAY=true \
bash scripts/runtime-plane-preprod-drill-record-check.sh
```

| 检查项 | 值 |
| --- | --- |
| Preprod record check summary |  |
| Preprod record check status | PASS / HOLD / FAIL |
