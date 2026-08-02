# Runtime Plane Release Checklist

本清单用于运行时分流平面变更发布，包括 runtime 配置接口、配置变更广播、SDK 缓存/重试、分流热路径和观测资产变更。

## 发布前

- [ ] 确认本次变更是否影响 `/api/runtime/experiments/{id}/config`、`/config/version`、`/traffic/assign` 或 `/traffic/assign/trace`。
- [ ] 确认 CI workflow `Runtime Plane Release Package` 已通过，并归档 `runtime-plane-release-package-report` artifact。
- [ ] 生产发布前确认该 artifact 来自严格模式：`PISCES_RELEASE_PACKAGE_RUN_TESTS=true`、`PISCES_RELEASE_PACKAGE_REQUIRE_PROMTOOL=true`、`PISCES_RELEASE_PACKAGE_REQUIRE_RUBY=true`，且归档时可通过 `PISCES_RELEASE_EVIDENCE_REQUIRE_CLEAN_GIT=true` 验证发布包报告中的 `gitDirty=false`。
- [ ] 执行发布包静态检查，确认 runtime 契约、SDK、脚本、监控资产和文档没有漏发：

```bash
bash scripts/runtime-plane-release-package-check.sh
```

- [ ] 确认生产环境 `PISCES_API_KEY_SPECS` 中 runtime key 只包含 `runtime` scope，management key 与 runtime key 分离。
- [ ] 多实例环境确认 `PISCES_CONFIG_CHANGE_REDIS_BROADCAST_ENABLED=true`。
- [ ] 确认所有实例使用同一 Redis 集群，并为当前环境使用独立 `PISCES_CONFIG_CHANGE_REDIS_CHANNEL`。
- [ ] 确认 Prometheus 已抓取 `/api/actuator/prometheus`。
- [ ] 导入或更新 `docs/observability/grafana/pisces-runtime-plane-dashboard.json`。
- [ ] 业务侧 SDK 如已接入，确认有本地 `getMetricsSnapshot()` 指标上报。
- [ ] 如果本次变更影响运行时热路径，执行 `docs/operations/runtime-plane-capacity-baseline.md` 中的容量基线，并确认 P95/P99 不回退。
- [ ] 容量基线完成后，执行 `docs/operations/runtime-plane-baseline-archive.md` 中的归档步骤。
- [ ] 如果本次变更影响事件采集、异步事件管道、统计派生数据或 MAB 奖励，执行 `docs/operations/event-pipeline-replay-audit.md` 中的只读审计，并设置 `PISCES_EVENT_REPLAY_MAX_UNMATERIALIZED_PLAN=0` 验证缺账本计数为 0；审计 `summary.json` 需要作为 `PISCES_EVENT_REPLAY_AUDIT_SUMMARY_FILE` 进入发布证据归档。
- [ ] 如果只读重放计划发现缺失派生物化账本，使用 `PISCES_EVENT_REPLAY_REPAIR_MATERIALIZATION=true` 执行受控修复；需要按窗口或事件类型处理时设置 `PISCES_EVENT_REPLAY_START_TIME`、`PISCES_EVENT_REPLAY_END_TIME`、`PISCES_EVENT_REPLAY_EVENT_TYPES`、`PISCES_EVENT_REPLAY_INCLUDE_EVENTS`、`PISCES_EVENT_REPLAY_INCLUDE_EXPOSURES`，大窗口修复还应设置 `PISCES_EVENT_REPLAY_SEGMENT_COUNT` 并在必要时使用 `PISCES_EVENT_REPLAY_REPAIR_SEGMENT_INDEX` 指定失败分段，确认 `replay_plan_segments_generated=PASS` 和 `post_repair_replay_plan_unmaterialized_count=PASS` 后再关闭发布前门禁。
- [ ] 如果本次变更影响 Redis 缓存、广播或降级路径，执行 `docs/operations/runtime-plane-redis-fault-injection.md` 中的 Redis 故障注入演练。
- [ ] 使用 `docs/operations/runtime-plane-preprod-drill-record-template.md` 建立预发演练记录。
- [ ] 归档发布证据前执行 `scripts/runtime-plane-preprod-drill-record-check.sh` 校验预发演练记录；涉及事件管道时设置 `PISCES_PREPROD_REQUIRE_EVENT_REPLAY=true`，正式归档后设置 `PISCES_PREPROD_REQUIRE_EVIDENCE_ARCHIVE=true` 复查 manifest 路径和 sha256。
- [ ] 使用 `scripts/runtime-plane-release-evidence-archive.sh` 归档发布证据；涉及事件管道时传入 `PISCES_EVENT_REPLAY_AUDIT_SUMMARY_FILE`，并在需要时通过 `PISCES_RELEASE_BATCH_COMPARE_MANIFEST_FILE` 比对发布批次 manifest。
- [ ] 在预发环境执行只读演练：

```bash
PISCES_INSTANCE_URLS="http://pre-a.example.com/api,http://pre-b.example.com/api" \
PISCES_EXPERIMENT_ID="<experimentId>" \
PISCES_RUNTIME_API_KEY="<runtime-key>" \
PISCES_ASSIGNMENT_REQUESTS=1000 \
PISCES_ASSIGNMENT_CONCURRENCY=32 \
bash scripts/runtime-plane-release-drill.sh
```

## 发布中

- [ ] 滚动发布时保持至少一个旧实例和一个新实例同时连接相同 Redis channel，观察广播兼容性。
- [ ] 如本次包含配置发布，在发布窗口执行：

```bash
PISCES_INSTANCE_URLS="http://prod-a.example.com/api,http://prod-b.example.com/api" \
PISCES_EXPERIMENT_ID="<experimentId>" \
PISCES_RUNTIME_API_KEY="<runtime-key>" \
PISCES_MANAGEMENT_API_KEY="<management-key>" \
PISCES_RELEASE_ACTION="publish-current" \
PISCES_VERSION_WAIT_MILLIS=25000 \
PISCES_CONVERGENCE_TIMEOUT_SECONDS=60 \
bash scripts/runtime-plane-release-drill.sh
```

- [ ] 确认脚本输出 `All instances converged to configVersion=<target>`。
- [ ] 确认 `Assignment load summary` 中 `failed=0`。
- [ ] 确认 dashboard 中 Assignment Error Rate 为 0。
- [ ] 确认 Cache Error Rate 没有持续增长。
- [ ] 确认 Config Broadcast Events 中 publish success 和 receive applied 符合预期。

## 发布后

- [ ] 观察 `pisces_traffic_assignment_latency_seconds` P95/P99 是否回到发布前基线。
- [ ] 观察 `pisces_traffic_assignment_requests_total{result="ERROR"}` 是否不增长。
- [ ] 观察 `pisces_traffic_cache_events_total{result="ERROR"}` 是否不增长。
- [ ] 观察 `pisces_config_change_broadcast_published_total{result="ERROR"}` 是否不增长。
- [ ] 观察 `pisces_config_change_broadcast_received_total{result="INVALID"}` 是否不增长。
- [ ] 观察 `pisces_config_change_broadcast_listener_errors_total` 是否不增长。
- [ ] 业务 SDK 指标中 `requestFailureCount`、`retryCount`、`staleExperimentConfigFallbackCount` 没有异常增长。
- [ ] 随机抽取一个实验，调用 `/traffic/assign/trace` 确认 `configVersion`、`source`、`reason` 可解释。
- [ ] 使用 `scripts/runtime-plane-post-release-slo-review.sh` 生成发布后 SLO 验收摘要，并归档 `summary.json`。
- [ ] 使用 `scripts/runtime-plane-experiment-impact-sampling.sh` 对本次涉及的实验生成影响面抽样摘要，并归档 `summary.json`。
- [ ] 使用 `scripts/runtime-plane-staged-rollout-decision.sh` 生成当前阶段准入或回滚决策摘要，并归档 `summary.json`。
- [ ] full rollout 或 post-release 观察窗口结束后，使用 `scripts/runtime-plane-production-acceptance-check.sh` 生成最终生产验收摘要；只有 `decision=ACCEPT` 才关闭发布。
- [ ] 关闭“生产级实验基础设施”目标前，使用 `scripts/production-infrastructure-completion-audit.sh` 汇总 Control/Data/Event/Decision/Operations 五类证据；只有 `completionStatus=COMPLETE` 才算完成。
- [ ] 如发布观察发现事实表与派生数据不一致，使用 `scripts/event-pipeline-replay-audit.sh` 执行重放审计，并通过 `PISCES_EVENT_REPLAY_AUDIT_SUMMARY_FILE` 归档 `summary.json`。
- [ ] 如发布观察只发现派生物化账本缺口，优先使用 `PISCES_EVENT_REPLAY_REPAIR_MATERIALIZATION=true` 修复缺账本；必要时使用 replay scope 变量限定窗口、事件类型或事实类型。确认需要修复 Redis/MAB 派生数据时才设置 `PISCES_EVENT_REPLAY_EXECUTE=true`：执行前设置 `PISCES_EVENT_REPLAY_MAX_AFFECTED_PLAN` 校验计划影响面，并确认服务端 `PISCES_EVENT_REPLAY_MAX_FILTERED_COPY_FACTS` 符合当次窗口；全量 scope 会清空重建，筛选 scope 走复制型 replay，不清空现有派生数据。
- [ ] 将容量基线 JSONL 和 Redis 故障注入三阶段摘要归档到发布记录。
- [ ] 如果 SLO 回看或影响面抽样 `status != PASS`，复制 `docs/operations/runtime-plane-post-release-incident-review-template.md` 建立异常复盘记录。

发布后实验级影响面抽样：

```bash
PISCES_INSTANCE_URLS="http://prod-a.example.com/api,http://prod-b.example.com/api" \
PISCES_EXPERIMENT_IDS="<experimentIdA>,<experimentIdB>" \
PISCES_RUNTIME_API_KEY="<runtime-key>" \
PISCES_IMPACT_EXPECTED_CONFIG_VERSIONS="<experimentIdA>:<version>,<experimentIdB>:<version>" \
bash scripts/runtime-plane-experiment-impact-sampling.sh
```

分批发布准入与回滚决策：

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

## 回滚触发条件

满足任一条件应进入回滚或止血：

- [ ] 分流错误持续增长，且无法在一个发布观察窗口内定位为非发布因素。
- [ ] 多实例配置版本无法在 `PISCES_CONVERGENCE_TIMEOUT_SECONDS` 内收敛。
- [ ] Redis 广播 publish error 或 listener error 持续增长。
- [ ] SDK stale fallback 快速增长，且业务已经依赖旧配置快照。
- [ ] 分流 P99 明显高于发布前基线并影响业务链路。

回滚后重新执行只读演练，确认 runtime 配置版本、分流热路径和指标恢复。
