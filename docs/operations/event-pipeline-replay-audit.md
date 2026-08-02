# Event Pipeline Replay Audit

本文档用于生产环境事件管道的重放审计与校验。目标是在修复事实已落库但 Redis 派生计数或 MAB 奖励不一致的场景时，形成一份可归档、可复核的证据。

脚本默认只读：查询事件管道状态、统计总览和重放计划，不触发重放。重放计划会记录匹配事实数、已物化事实数、缺账本事实数和可选时间分段巡检结果。只有显式设置 `PISCES_EVENT_REPLAY_EXECUTE=true` 时，才会调用 `POST /analysis/experiment/{id}/events/replay` 提交异步 replay job，并轮询 `/events/replay/jobs/{replayJobId}` 到终态后校验 Redis 派生数据和 MAB 奖励重建计数；等价全量 scope 会执行全量派生重建，带时间范围、事件类型或事件/曝光开关的 scope 会执行复制型 replay，不清空现有 Redis/MAB 派生数据。只有显式设置 `PISCES_EVENT_REPLAY_REPAIR_MATERIALIZATION=true` 时，才会调用 `POST /analysis/experiment/{id}/events/replay/materialization/repair` 按安全边界修复缺失派生物化账本；同时设置 `PISCES_EVENT_REPLAY_REPAIR_SEGMENT_INDEX` 时，只调用 `/events/replay/materialization/repair/segments/{segmentIndex}` 恢复指定时间分段。

## 执行

只读审计：

```bash
PISCES_API_BASE_URL="http://prod.example.com/api" \
PISCES_EXPERIMENT_ID="<experimentId>" \
PISCES_ANALYSIS_API_KEY="<analysis-or-management-key>" \
bash scripts/event-pipeline-replay-audit.sh
```

执行重放并校验：

```bash
PISCES_API_BASE_URL="http://prod.example.com/api" \
PISCES_EXPERIMENT_ID="<experimentId>" \
PISCES_ANALYSIS_API_KEY="<analysis-or-management-key>" \
PISCES_EVENT_REPLAY_EXECUTE=true \
PISCES_EVENT_REPLAY_OPERATOR="<operator>" \
bash scripts/event-pipeline-replay-audit.sh
```

修复缺失派生物化账本并校验修复后覆盖：

```bash
PISCES_API_BASE_URL="http://prod.example.com/api" \
PISCES_EXPERIMENT_ID="<experimentId>" \
PISCES_ANALYSIS_API_KEY="<analysis-or-management-key>" \
PISCES_EVENT_REPLAY_REPAIR_MATERIALIZATION=true \
PISCES_EVENT_REPLAY_MAX_UNMATERIALIZED_PLAN=0 \
PISCES_EVENT_REPLAY_OPERATOR="<operator>" \
bash scripts/event-pipeline-replay-audit.sh
```

按范围修复缺失派生物化账本：

```bash
PISCES_API_BASE_URL="http://prod.example.com/api" \
PISCES_EXPERIMENT_ID="<experimentId>" \
PISCES_ANALYSIS_API_KEY="<analysis-or-management-key>" \
PISCES_EVENT_REPLAY_REPAIR_MATERIALIZATION=true \
PISCES_EVENT_REPLAY_START_TIME="2026-07-30T00:00:00" \
PISCES_EVENT_REPLAY_END_TIME="2026-07-30T01:00:00" \
PISCES_EVENT_REPLAY_EVENT_TYPES="PAY_SUCCESS" \
PISCES_EVENT_REPLAY_INCLUDE_EVENTS=true \
PISCES_EVENT_REPLAY_INCLUDE_EXPOSURES=false \
PISCES_EVENT_REPLAY_MAX_UNMATERIALIZED_PLAN=0 \
PISCES_EVENT_REPLAY_OPERATOR="<operator>" \
bash scripts/event-pipeline-replay-audit.sh
```

默认输出：

```text
target/pisces-event-pipeline-replay-audit/summary.json
```

## 门禁

脚本输出 `status=PASS|FAIL`，任一 `FAIL` gate 会导致非零退出。

| Gate | 说明 |
| --- | --- |
| `before_status_request_success` | 重放前事件管道状态可查询 |
| `before_statistics_request_success` | 重放前统计总览可查询 |
| `replay_plan_request_success` | 重放计划可查询 |
| `replay_plan_affected_count` | 设置 `PISCES_EVENT_REPLAY_MAX_AFFECTED_PLAN` 时，重放计划匹配事实数不超过阈值 |
| `replay_plan_unmaterialized_count` | 设置 `PISCES_EVENT_REPLAY_MAX_UNMATERIALIZED_PLAN` 且未开启修复时，重放计划缺账本事实数不超过阈值；开启修复时该 gate 只记录修复前缺口并跳过 |
| `replay_plan_segments_generated` | 设置 `PISCES_EVENT_REPLAY_SEGMENT_COUNT > 1` 时，重放计划必须生成可恢复时间分段 |
| `repair_materialization_request_success` | 开启补物化修复时，修复接口请求成功 |
| `repair_materialization_operation_success` | 开启补物化修复时，操作返回 `REPAIR_MATERIALIZATION/SUCCESS` |
| `post_repair_replay_plan_request_success` | 开启补物化修复且采集 plan 时，修复后重放计划可查询 |
| `post_repair_replay_plan_unmaterialized_count` | 开启补物化修复且设置 `PISCES_EVENT_REPLAY_MAX_UNMATERIALIZED_PLAN` 时，修复后缺账本事实数不超过阈值 |
| `replay_request_success` | 开启重放时，重放接口请求成功 |
| `replay_operation_success` | 开启重放时，操作返回 `REPLAY_DERIVED/RUNNING`；兼容旧同步实现的 `REPLAY_DERIVED/SUCCESS` |
| `replay_job_request_success` | 开启重放且接口返回 `replayJobId` 时，job detail 可查询 |
| `replay_job_terminal_success` | 开启重放且接口返回 `replayJobId` 时，job 终态为 `SUCCEEDED` |
| `replay_rebuilt_fact_count` | 开启重放时，按 job 终态计数校验重建事实数不低于 `PISCES_EVENT_REPLAY_MIN_REBUILT_FACTS`；无 `replayJobId` 时兼容读取操作响应计数 |
| `after_status_request_success` | 重放后事件管道状态可查询 |
| `after_pipeline_healthy` | 重放后管道 `healthy=true` |
| `after_unfinished_count` | 重放后未完成 inbox 数不超过阈值 |
| `after_retry_count` | 重放后 retry 数不超过阈值 |
| `after_dead_count` | 重放后 dead 数不超过阈值 |
| `after_rejected_count` | 重放后 rejected 数不超过阈值 |
| `after_max_pending_seconds` | 重放后最大积压秒数不超过阈值 |
| `statistics_totalAssignments_not_decreased` | 重放后分流总数不小于重放前 |
| `statistics_totalExposures_not_decreased` | 重放后曝光总数不小于重放前 |
| `statistics_totalEvents_not_decreased` | 重放后事件总数不小于重放前 |

## 变量

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `PISCES_API_BASE_URL` | `http://localhost:9990/api` | 服务 base URL |
| `PISCES_EXPERIMENT_ID` | 空 | 必填，实验 ID |
| `PISCES_ANALYSIS_API_KEY` | `ops-key` | `analysis` 或 `management` scope API Key |
| `PISCES_EVENT_REPLAY_OUTPUT_FILE` | `target/pisces-event-pipeline-replay-audit/summary.json` | 输出文件 |
| `PISCES_EVENT_REPLAY_EXECUTE` | `false` | 是否调用 `/events/replay`；全量 scope 清空重建派生数据，筛选 scope 执行复制型 replay |
| `PISCES_EVENT_REPLAY_RETRY_DEAD_FIRST` | `false` | 是否先调用 `/event-pipeline/dead/retry` |
| `PISCES_EVENT_REPLAY_REPAIR_MATERIALIZATION` | `false` | 是否调用 `/events/replay/materialization/repair` 修复缺失派生物化账本；全量计划走全量重放，筛选计划只补缺账本事实 |
| `PISCES_EVENT_REPLAY_OPERATOR` | `event-replay-audit` | 操作人 |
| `PISCES_EVENT_REPLAY_FETCH_STATISTICS` | `true` | 是否采集重放前后统计总览 |
| `PISCES_EVENT_REPLAY_FETCH_PLAN` | `true` | 是否采集只读重放计划 |
| `PISCES_EVENT_REPLAY_START_TIME` | 空 | 可选，传给 replay plan / repair / replay 请求体的 `startTime` |
| `PISCES_EVENT_REPLAY_END_TIME` | 空 | 可选，传给 replay plan / repair / replay 请求体的 `endTime` |
| `PISCES_EVENT_REPLAY_EVENT_TYPES` | 空 | 可选，逗号分隔事件类型，传给请求体 `eventTypes` |
| `PISCES_EVENT_REPLAY_INCLUDE_EVENTS` | 空 | 可选，传给请求体 `includeEvents`；空值表示使用服务端默认 |
| `PISCES_EVENT_REPLAY_INCLUDE_EXPOSURES` | 空 | 可选，传给请求体 `includeExposures`；空值表示使用服务端默认 |
| `PISCES_EVENT_REPLAY_SEGMENT_COUNT` | 空 | 可选，传给 replay plan 请求体的 `segmentCount`；大于 1 且同时设置开始/结束时间时生成可恢复时间分段 |
| `PISCES_EVENT_REPLAY_REPAIR_SEGMENT_INDEX` | 空 | 可选，开启补物化修复时调用 `/events/replay/materialization/repair/segments/{segmentIndex}` 只恢复指定 0-based 分段 |
| `PISCES_EVENT_REPLAY_MAX_AFFECTED_PLAN` | 空 | 可选，重放计划中最大允许匹配事实数；为空时只记录不设门禁 |
| `PISCES_EVENT_REPLAY_MAX_UNMATERIALIZED_PLAN` | 空 | 可选，重放计划中最大允许缺账本事实数；为空时只记录不设门禁 |
| `PISCES_EVENT_REPLAY_REQUIRE_HEALTHY_BEFORE` | `false` | 是否要求重放前管道健康 |
| `PISCES_EVENT_REPLAY_REQUIRE_HEALTHY_AFTER` | `true` | 是否要求重放后管道健康 |
| `PISCES_EVENT_REPLAY_MAX_UNFINISHED_AFTER` | `0` | 重放后最大未完成 inbox 数 |
| `PISCES_EVENT_REPLAY_MAX_RETRY_AFTER` | `0` | 重放后最大 retry 数 |
| `PISCES_EVENT_REPLAY_MAX_DEAD_AFTER` | `0` | 重放后最大 dead 数 |
| `PISCES_EVENT_REPLAY_MAX_REJECTED_AFTER` | `0` | 重放后最大 rejected 数 |
| `PISCES_EVENT_REPLAY_MAX_PENDING_SECONDS` | `300` | 重放后最大积压秒数 |
| `PISCES_EVENT_REPLAY_MIN_REBUILT_FACTS` | `0` | 开启重放时最小重建事实数 |
| `PISCES_EVENT_REPLAY_TIMEOUT_SECONDS` | `10` | 单次 HTTP 超时 |
| `PISCES_EVENT_REPLAY_JOB_TIMEOUT_SECONDS` | `300` | 开启重放时等待 replay job 进入终态的最大秒数 |
| `PISCES_EVENT_REPLAY_JOB_POLL_INTERVAL_SECONDS` | `2` | replay job 终态轮询间隔秒数 |

## 操作建议

- 正常发布观察期可先只读审计，不触发重放。
- 可用 `PISCES_EVENT_REPLAY_MAX_AFFECTED_PLAN` 给重放计划设置影响面上限；设置该值时必须采集 replay plan，否则 `replay_plan_affected_count` 会失败。
- 可用 `PISCES_EVENT_REPLAY_MAX_UNMATERIALIZED_PLAN=0` 将只读重放计划作为严格账本覆盖门禁。
- 可用 `PISCES_EVENT_REPLAY_SEGMENT_COUNT` 把显式时间范围拆成多个巡检分段；脚本会检查 `replay_plan_segments_generated`，并记录 `segments`、`maxSegmentAffectedCount` 和 `maxSegmentUnmaterializedCount`，便于大窗口发布前调整分段大小、影响面上限和 replay job 超时。
- 可用 `PISCES_EVENT_REPLAY_REPAIR_MATERIALIZATION=true` 修复缺失派生物化账本；脚本会在修复后重新获取 plan，并把 `PISCES_EVENT_REPLAY_MAX_UNMATERIALIZED_PLAN` 应用到修复后的缺账本计数。
- `PISCES_EVENT_REPLAY_START_TIME`、`PISCES_EVENT_REPLAY_END_TIME`、`PISCES_EVENT_REPLAY_EVENT_TYPES`、`PISCES_EVENT_REPLAY_INCLUDE_EVENTS` 和 `PISCES_EVENT_REPLAY_INCLUDE_EXPOSURES` 会形成同一份 `replayScopeRequest`，同时用于修复前 plan、补物化修复、修复后 plan 和显式 replay。
- 分段恢复只在显式 `startTime` / `endTime` 的计划中成立；某个分段失败后，保留同一份 `replayScopeRequest`，设置 `PISCES_EVENT_REPLAY_REPAIR_SEGMENT_INDEX` 重试该分段，避免把一次失败扩大成全窗口修复。
- 只有确认事实表可信、派生数据不一致时，才执行 `PISCES_EVENT_REPLAY_EXECUTE=true`。全量 scope 用于关闭广义 Redis/MAB 漂移；筛选 scope 是复制型 replay，只对 scope 内 Redis 列表缺失的 `eventId` / `exposureId` 补派生并刷新账本，不证明既有 MAB 奖励状态无漂移。
- 服务端默认通过 `PISCES_EVENT_REPLAY_MAX_FILTERED_COPY_FACTS=50000` 限制筛选复制型 replay 的最大匹配事实数；超过上限会拒绝创建 replay job。全量 replay 不受该配置限制，执行前应使用只读 plan、容量基线和发布审批确认影响面。
- 服务端默认通过 `PISCES_EVENT_REPLAY_BATCH_SIZE=1000` 分页处理 replay / repair 事实，并在批处理安全点回写 replay job 进度计数、检查取消请求；job 详情会返回 `plannedAffectedCount` 和 `progressPercent`，用于判断大实验长任务是否持续推进。大实验窗口应结合 Redis/MySQL 容量、`PISCES_EVENT_REPLAY_MAX_AFFECTED_PLAN` 和 job 轮询超时一起调参。
- 修复端点对带时间范围、事件类型或事件/曝光开关的筛选计划只处理缺账本事实：如果 Redis 列表已有同一 `eventId` / `exposureId`，只补物化账本；如果 Redis 未有该事实，再补对应派生和账本。这个路径只解决缺账本，不承诺修复广义 Redis/MAB 漂移，漂移修复仍需全量 replay。
- `PISCES_EVENT_REPLAY_RETRY_DEAD_FIRST=true` 会把 DEAD inbox 重置为 RETRY，通常需要等待后台消费者处理后再复查。
- 如果 `status=FAIL`，不要关闭发布观察；应记录失败 gate，并进入事件管道排障或发布后异常复盘。

## 发布记录

归档以下证据：

- `target/pisces-event-pipeline-replay-audit/summary.json`
- 操作人、操作时间和是否执行重放。
- `replayScopeRequest`，用于确认修复前 plan、修复操作和修复后 plan 使用同一事实范围。
- 重放前后 `EventPipelineStatusResponse`。
- 修复前 `EventReplayPlanResponse` 中的 `affectedCount`、`materializedCount`、`unmaterializedCount`、`segments`、`maxSegmentAffectedCount`、`maxSegmentUnmaterializedCount` 和分组覆盖明细。
- 如执行 `PISCES_EVENT_REPLAY_REPAIR_MATERIALIZATION=true`，归档 `repairMaterializationOperation`、`repairSegmentIndex`、`replayPlanAfterRepair`，并确认 `post_repair_replay_plan_unmaterialized_count=PASS`。
- 如执行 `PISCES_EVENT_REPLAY_EXECUTE=true`，归档 `EventPipelineOperationResponse` 中的 `operation`、`status`、`replayJobId` 和 `replayJobStatus`，以及 `replayJob` 运行中和终态中的 `jobStatus`、`plannedAffectedCount`、`progressPercent`、`eventCount`、`exposureCount`、`groupCount` 和 `mabRewardCount`；脚本会额外写入 `replayJobPollSummary.maxProgressPercent` / `maxAffectedCount`，运行中计划总量和计数用于证明长任务持续推进，终态计数用于发布证据。
- 如失败，关联 `docs/operations/runtime-plane-post-release-incident-review-template.md` 形成复盘记录。

## 本地验证

`scripts/event-pipeline-replay-audit-scope-smoke-test.sh` 会启动本地 mock API，执行一次 scoped 补物化审计，并断言修复前 plan、repair、修复后 plan 使用同一份 `replayScopeRequest`。`scripts/event-pipeline-replay-segment-repair-smoke-test.sh` 会模拟带 `segmentCount` 的计划、只修复 `/segments/1`、修复后分段缺口归零，并断言 `replay_plan_segments_generated=PASS` 与 `post_repair_replay_plan_unmaterialized_count=PASS`。这两个测试都不依赖真实服务、Redis、MySQL 或外部网络。
