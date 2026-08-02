# API 清单

基础前缀：`/api`

## API Key 权限域

请求头：`X-Pisces-Api-Key`

| Scope | 用途 |
| --- | --- |
| `runtime` | SDK 分流、曝光、事件上报和访客身份解析 |
| `analysis` | 统计、报告、AI 诊断/毕业、事件管道查询和治理 |
| `management` | 实验配置、生命周期、结论状态、变体生成、演示补数和 MAB 重置 |
| `admin` | 预留管理员权限，包含全部 scope |

生产推荐使用 `PISCES_API_KEY_SPECS=key|appId|owner|scope1+scope2` 配置；旧 `PISCES_API_KEYS` 仅作为兼容模式。

非 `admin` key 创建实验时，实验会归属于 key 绑定的 `appId` / `owner`。实验详情、列表、生命周期操作、分流、数据上报、分析、事件管道治理和 MAB 查询/重置都会按 `appId` 校验；跨应用访问返回 `FORBIDDEN`，列表接口只返回当前应用实验。`admin` key 可访问全部应用。

## 可观测性

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/actuator/health` | Spring Actuator 健康检查，跳过 Pisces API Key 鉴权 |
| `GET` | `/actuator/prometheus` | Prometheus 指标出口，跳过 Pisces API Key 鉴权；包含审批升级告警业务状态、outbox 投递状态、通道回执状态、dispatcher 目标数量、指标刷新健康度等 `pisces_approval_escalation_*` 指标，分流请求结果、分流耗时、Redis 缓存命中/异常等 `pisces_traffic_*` 指标，以及配置变更广播启用状态、发送/接收结果和 listener 异常等 `pisces_config_change_broadcast_*` 指标 |

## 应用空间

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/applications` | 查询当前 key 可见的应用空间目录；合并数据库注册表、API Key 配置和已有实验，返回 `appId`、展示名、默认负责人、审批人列表、审批通过人数、审批策略版本、审批 SLA、升级接收人、实验配额、启动审批开关、发布窗口、负责人、权限域、API Key 数量、实验数和运行中实验数 |
| `PUT` | `/applications/{appId}` | 注册或更新应用空间治理信息，支持 `displayName`、`defaultOwner`、`approvalOwners`、`approvalRequiredCount`、`approvalSlaHours`、`approvalEscalationOwners`、`experimentQuota`、`approvalRequired`、`releaseWindowEnabled`、`releaseWindowTimezone`、`releaseWindowDays`、`releaseWindowStartTime`、`releaseWindowEndTime`；非 `admin` key 只能更新自身应用 |
| `GET` | `/applications/{appId}/dictionary` | 查询应用级事件/指标字典；创建和更新实验成功后会把实验 `eventDefinitions` / `metricDefinitions` upsert 到该应用字典，非 `admin` key 只能查询自身应用 |

## 实验管理

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/experiments` | 创建实验，支持可选 `layerId` 绑定流量分层 |
| `PUT` | `/experiments/{id}` | 更新实验，支持可选 `layerId` 绑定流量分层；运行中实验更新会校验应用发布窗口，更新到互斥层时会检查冲突 |
| `GET` | `/experiments/{id}` | 获取实验详情，返回 `layerId` |
| `GET` | `/experiments/{id}/audit-logs` | 查询实验管理审计日志 |
| `GET` | `/experiments/{id}/config-versions` | 查询实验配置发布历史 |
| `POST` | `/experiments/{id}/config-versions/publish` | 发布当前实验配置快照，写入配置版本历史和审计日志 |
| `POST` | `/experiments/{id}/config-versions/rollback` | 从已发布配置版本回滚，校验应用发布窗口后生成新的当前 `configVersion`，写入配置版本历史和审计日志 |
| `GET` | `/experiments/{id}/config-draft` | 查询当前待发布配置草稿；无草稿时返回空数据；有草稿时返回草稿审批状态 |
| `GET` | `/experiments/{id}/config-draft/approvals` | 查询实验配置草稿审批历史，按 `draftVersion` 倒序返回 `approvalStatus`、提交人、审批人、审批策略快照和备注 |
| `PUT` | `/experiments/{id}/config-draft` | 保存配置草稿，只写草稿表，不影响 SDK 运行时配置；应用启用审批时会为当前 `draftVersion` 创建 `PENDING` 草稿审批记录 |
| `POST` | `/experiments/{id}/config-draft/publish` | 发布配置草稿；校验草稿基线版本未过期、应用发布窗口，且应用启用审批时当前 `draftVersion` 的审批记录必须已 `APPROVED`，之后生成新的当前 `configVersion` |
| `GET` | `/experiments/approval-tasks` | 查询实验配置/启动审批任务，默认返回 `PENDING`，支持 `appId`、`owner`、`approvalStatus` 过滤；返回 `approvalType`、`draftVersion`、`baseConfigVersion`、`approvalRequestedBy`、`approvalOwner`、`approvalOwners`、`approvalRequiredCount`、`approvalApprovedCount`、`approvalRejectedCount`、`approvalProgressText`、审批 SLA/升级上下文、审批风险上下文、`approvable` 和 `approvalDisabledReason`；待办中的审批人和通过人数来自任务提交时的策略快照，非 `admin` key 仍只能看到自身应用 |
| `POST` | `/experiments/approval-escalations/scan` | 扫描当前身份可见的逾期 `PENDING` 审批任务，幂等创建审批升级告警 outbox 记录，支持 `appId`、`owner` 过滤；后台调度也可按 `pisces.approval-escalation.scan-*` 配置定时执行 |
| `GET` | `/experiments/approval-escalations/status` | 查询当前身份可见的审批升级告警状态汇总，支持 `appId`、`owner` 过滤；返回业务状态计数、outbox 投递状态计数、通道 receipt 投递状态计数、未送达数、健康标记、整体状态，以及 dispatcher 是否启用、目标数量和通道名 |
| `POST` | `/experiments/approval-escalations/dead/retry` | 批量重投当前身份可见的 `DEAD` 投递状态告警，支持 `appId`、`owner`、`operator` 参数；只会重新入队 `OPEN`/`ACKNOWLEDGED` 告警 |
| `GET` | `/experiments/approval-escalations` | 查询审批升级告警记录，默认返回 `OPEN`，支持 `appId`、`owner`、`escalationStatus` 过滤；返回告警通道、消息载荷、升级接收人、`notificationStatus`、投递尝试次数、下次重试/送达/最近错误、每通道 `notificationDeliveries` 以及确认/关闭信息 |
| `POST` | `/experiments/approval-escalations/{escalationId}/ack` | 确认 `OPEN` 状态审批升级告警，写入确认人、确认备注和确认时间；审批最终通过或拒绝后会自动把同任务告警关闭为 `RESOLVED` |
| `POST` | `/experiments/approval-escalations/{escalationId}/notification/retry` | 重投单条 `DEAD` 投递状态告警；会校验当前 key 的应用访问权限，已 `RESOLVED` 告警不可重投 |
| `GET` | `/experiments` | 查询实验列表，支持 `status`、`statuses`、`appId`、`owner` 过滤 |
| `GET` | `/experiments/status/{status}` | 按状态查询 |
| `POST` | `/experiments/{id}/start` | 启动实验；应用启用审批时需要 `APPROVED`，应用启用发布窗口时必须在窗口内，互斥层内同一应用已有运行中实验时拒绝启动 |
| `POST` | `/experiments/{id}/stop` | 停止实验 |
| `POST` | `/experiments/{id}/pause` | 暂停实验 |
| `POST` | `/experiments/{id}/resume` | 恢复实验；应用启用审批时需要 `APPROVED`，应用启用发布窗口时必须在窗口内，互斥层内同一应用已有运行中实验时拒绝恢复 |
| `POST` | `/experiments/{id}/approval-status` | 更新实验配置/启动审批投票，支持 `APPROVED` / `REJECTED`，写入审计日志；非 `admin` key 必须在当前审批任务的策略快照审批人列表内，且不能审批自己提交的变更；`APPROVED` 会校验最新报告风险，存在 SRM 或护栏异常时拒绝通过；`admin` 可显式提交 `riskOverride=true` 和 `riskOverrideReason` 豁免阻断风险；随后按快照中的 `approvalRequiredCount` 聚合，未达人数保持 `PENDING`；`REJECTED` 会立即终止当前审批 |
| `POST` | `/experiments/{id}/conclusion-status` | 更新人工结论状态；推进到 `READY_FOR_REVIEW`、`GRADUATED` 或 `REJECTED` 时需提交 `expectedConfigVersion` 和 `reportSnapshotVersion`，服务端校验当前配置版本与最新报告快照版本一致，并把绑定证据写入实验详情和审计日志；缺少证据版本、无报告快照、报告未分析就绪、证据版本过期、毕业报告存在 SRM 或护栏异常时会拒绝提交且不改变当前结论 |
| `DELETE` | `/experiments/{id}` | 删除实验 |
| `POST` | `/experiments/batch/pause` | 批量暂停 |
| `POST` | `/experiments/batch/stop` | 批量停止 |
| `POST` | `/experiments/batch/resume` | 批量恢复 |
| `POST` | `/experiments/batch/delete` | 批量删除 |

## 流量分配

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/runtime/experiments/{experimentId}/config` | SDK 运行时实验配置拉取，使用 `runtime` scope，返回配置版本、实验组配置、流量配置、事件定义和指标定义 |
| `GET` | `/runtime/experiments/{experimentId}/config/version` | SDK 运行时配置版本检查，支持 `knownVersion` 和 `waitMillis` 查询参数；`waitMillis` 用于版本未变化时的 bounded long-poll，服务端最多等待 30 秒，并通过配置变更序列提前唤醒，避免变更通知发生在等待前时丢失唤醒；多实例生产部署可启用 Redis 配置变更广播推进各实例本地序列 |
| `POST` | `/traffic/assign` | 分配访客到实验组 |
| `POST` | `/traffic/assign/trace` | 分配访客到实验组，并返回命中原因、来源、策略和配置版本 |
| `GET` | `/traffic/experiment/{experimentId}/mab/beta` | 查询 Thompson 参数 |
| `GET` | `/traffic/experiment/{experimentId}/mab/stats` | 查询组统计 |
| `GET` | `/traffic/experiment/{experimentId}/mab/probabilities` | 查询分配概率 |
| `GET` | `/traffic/experiment/{experimentId}/mab/summary` | 查询 MAB 摘要；返回 `totalTrials`、`totalObservedRewards`、`ucbSelectionTrials` 和各组 `observedRewardCount` / `observedSuccesses` / `observedFailures`；主 RATE 指标会按观测键同时纳入 denominator 失败和 numerator 成功 |
| `POST` | `/traffic/experiment/{experimentId}/mab/reset` | 重置 MAB 状态 |

## 数据上报

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/data/event` | 上报实验定义事件；兼容 `VIEW` / `CLICK` / `CONVERT` 快捷事件 |
| `POST` | `/data/exposure` | 上报曝光 |

## 分析

### 当前主入口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/analysis/experiment/{id}/statistics` | 统计总览 |
| `GET` | `/analysis/experiment/{id}/compare` | 组间对比 |
| `GET` | `/analysis/sample-size` | 样本量计算 |
| `GET` | `/analysis/experiment/{id}/bayesian` | 贝叶斯分析 |
| `GET` | `/analysis/experiment/{id}/early-stop` | 早停判断 |
| `GET` | `/analysis/experiment/{id}/report` | 导出报告 |
| `POST` | `/analysis/experiment/{id}/report/snapshots` | 生成报告快照 |
| `GET` | `/analysis/experiment/{id}/report/snapshots` | 查询报告快照 |
| `GET` | `/analysis/experiment/{id}/timeline` | 时间线 |
| `GET` | `/analysis/experiment/{id}/ai-diagnosis` | 结构化 AI 诊断；响应包含数据质量、统计和最新报告快照证据 `evidence` |
| `GET` | `/analysis/experiment/{id}/ai-graduation-decision` | 结构化 AI 毕业决策；响应包含数据质量、统计和最新报告快照证据 `evidence` |
| `POST` | `/analysis/experiment/ai-design/v2` | 结构化 AI 实验设计；单接口，内部执行 Schema Planning + Draft Filling，两阶段返回 `schemaPlanning` / `draftGeneration` |
| `GET` | `/analysis/experiment/{id}/event-pipeline` | 查询异步事件管道状态，返回 pending、processing、retry、done、dead、rejected、最大积压秒数和健康状态 |
| `POST` | `/analysis/experiment/{id}/event-pipeline/drain` | 同步物化该实验当前 due inbox 记录，返回 `DRAIN_INBOX` 操作结果；用于演示、排障和受控运维场景 |
| `POST` | `/analysis/experiment/{id}/event-pipeline/dead/retry` | 按实验维度重新投递 DEAD inbox 记录 |
| `POST` | `/analysis/experiment/{id}/events/replay` | 提交异步任务按事实表重建 Redis/MAB 派生数据，返回 `REPLAY_DERIVED` / `RUNNING` 操作结果、`replayJobId`、`replayJobStatus=RUNNING` 和重放 scope；等价全量范围使用 `FULL_DERIVED_REBUILD` 清空并重建派生数据，带时间范围、事件类型或事件/曝光开关的筛选范围使用 `FILTERED_DERIVED_COPY_REPLAY` 复制型 replay，不清空现有 Redis/MAB 派生数据；筛选复制型 replay 按 `PISCES_EVENT_REPLAY_BATCH_SIZE` 分页处理事实，并受 `PISCES_EVENT_REPLAY_MAX_FILTERED_COPY_FACTS` 影响事实数上限保护，超限时创建 job 前拒绝；同实验已有运行中或取消中的重放任务时返回 HTTP 409 / 业务码 409 |
| `POST` | `/analysis/experiment/{id}/events/replay/plan` | 生成只读重放计划，支持按时间范围、事件类型、事件/曝光开关统计匹配事实数，并返回已物化/缺账本计数和分组明细；请求带 `segmentCount > 1` 且同时指定 `startTime/endTime` 时，会返回 `segments`、`segmentRecoverySupported`、`maxSegmentAffectedCount` 和 `maxSegmentUnmaterializedCount`，用于分区巡检和失败重试分段恢复；不会修改 Redis/MAB 派生数据 |
| `POST` | `/analysis/experiment/{id}/events/replay/materialization/repair` | 按重放计划修复缺失派生物化账本，返回 `REPAIR_MATERIALIZATION` 操作结果；无缺口时 no-op，存在缺口且等价全量计划时通过全量派生重放修复，筛选计划存在缺口时只对缺账本事实做局部补物化：Redis 列表已存在同一 `eventId` / `exposureId` 时只补账本，否则补对应派生并补账本；广义 Redis/MAB 派生漂移仍需全量重放 |
| `POST` | `/analysis/experiment/{id}/events/replay/materialization/repair/segments/{segmentIndex}` | 按重放计划中的 0 基 `segmentIndex` 单独修复某个时间分段；请求体应与生成 plan 的 `segmentCount`、`startTime/endTime`、事件类型和事实范围保持一致；仅对该段缺账本事实做局部补物化，返回操作结果和该分段 replay scope，适合失败后只重试失败分段 |
| `GET` | `/analysis/experiment/{id}/events/replay/jobs` | 查询最近事件重放任务，支持 `limit`，返回任务状态、操作者、重放模式、scope、计划事实数 `plannedAffectedCount`、运行中事实进度 `progressPercent`、影响行数、事件/曝光/MAB 重建计数、失败原因和起止时间；运行中计数为批处理安全点累计值，终态为最终值 |
| `GET` | `/analysis/experiment/{id}/events/replay/jobs/{replayJobId}` | 查询单个事件重放任务详情，返回同最近任务列表一致的状态、scope、计划事实数、进度百分比、计数和错误信息；运行中计数为批处理安全点累计值，终态为最终值 |
| `POST` | `/analysis/experiment/{id}/events/replay/jobs/{replayJobId}/cancel` | 请求取消运行中的事件重放任务，先写为 `CANCEL_REQUESTED` 并保留同实验互斥键，后台 worker 在一致性安全点转为 `CANCELLED` 并释放互斥；`CANCEL_REQUESTED` 重复调用保持幂等成功，已终态任务返回 HTTP 409 / 业务码 409 |

### 仍保留的兼容接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/analysis/experiment/{id}/significance` | 显著性检验 |
| `POST` | `/analysis/experiment/{id}/causal-inference` | 因果推断（仅 `DID` / `PSM`） |
| `GET` | `/analysis/experiment/{id}/ai-insights` | 旧 AI 解读接口 |
| `POST` | `/analysis/experiment/ai-design` | 旧 AI 设计接口 |
| `GET` | `/analysis/experiment/{id}/auto-graduate` | 旧自动毕业接口 |
| `GET` | `/analysis/experiment/{id}/predict-completion` | 完成时间预测 |
| `GET` | `/analysis/experiment/{id}/srm` | SRM 检测 |
| `GET` | `/analysis/experiment/{id}/sequential` | 序贯检验 |

## 变体生成

### 当前推荐入口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/variants/generate` | 统一生成候选变体，支持 `TEXT` / `IMAGE` |

### 兼容入口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/variants/text/generate` | 文本候选生成 |
| `POST` | `/variants/image/generate` | 图片候选生成 |

## 演示与补数

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/experiments/generator/demo` | 生成固定演示实验，返回 AI 毕业决策结果和示例实验结构摘要 |
| `POST` | `/experiments/generator/{experimentId}/simulate` | 为已有实验补充真实事件数据，事件类型会遵循实验自己的 `eventDefinitions` / `metricDefinitions` |
