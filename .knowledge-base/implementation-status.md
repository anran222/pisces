# 实现边界

## 当前可用

- 实验增删改查和状态流转
- 多策略分流
- 事件采集、曝光采集、统计聚合
- MAB、贝叶斯分析、SRM、报告快照
- 结构化 AI 设计 / 诊断 / 毕业接口
- 统一候选变体生成
- 演示实验生成
- 为已有实验补充真实事件数据
- Java / JS 运行时 SDK
- 应用接入链路检查与真实事实聚合
- 实验创建前完整预检与创建门禁

## 当前重要约束

### 配置与安全

- MySQL 密码、通义 API Key、Pisces 管理 API Key 不在仓库中提供固定默认值，必须通过环境变量注入。
- 本地生产脚本统一按“依赖栈默认配置 -> `config/pisces-local.env` 用户配置”加载，用户显式填写的数据库、Redis、Zookeeper、千问和实例地址不会再被自动生成的栈配置覆盖。
- 本地服务管理会根据健康地址解析端口并核对真实监听 PID，可识别和接管已运行的 Pisces 后端；服务摘要仅记录脱敏依赖地址、监听归属和配置指纹，不写入数据库密码、API Key 或完整敏感配置。
- `TONGYI_API_KEY` 未配置时，AI 相关接口直接失败；非 AI 主链路不依赖该配置。
- 文本生成默认使用 `TONGYI_MODEL=qwen3.7-max` 和 DashScope 通道，适配只替换普通百炼/千问 `TONGYI_API_KEY` 的本地流程；如账号已开通 Token Plan preview，可显式覆盖为 `TONGYI_MODEL=qwen3.8-max-preview` 和 `TONGYI_API_MODE=openai-compatible`，回退模型仍保持 `TONGYI_FALLBACK_MODEL=qwen3.7-max`。
- `PISCES_API_KEY_SPECS` 是生产推荐配置，格式为 `key|appId|owner|scope1+scope2`，多条使用英文逗号分隔。
- 支持 `runtime`、`analysis`、`management`、`admin` 四类 API Key scope；带 scope 注解的接口会优先校验 scope，历史 `@NoTokenRequired` 不再绕过这些接口。
- API Key principal 会进入请求上下文；非 `admin` key 只能访问同 `appId` 实验，`admin` key 可跨应用治理。
- `GET /applications` 提供应用空间目录，当前从数据库注册表、API Key 配置和已有实验归并生成；非 `admin` key 只返回自身应用。
- `PUT /applications/{appId}` 可注册或更新应用空间展示名、默认负责人、审批人列表、审批通过人数、审批 SLA 小时数、升级接收人、实验配额、配置/启动审批开关和发布窗口；审批策略变化时会递增 `approvalPolicyVersion`，非 `admin` key 只能更新自身应用。
- `GET /applications/{appId}/dictionary` 提供应用级事件/指标字典；非 `admin` key 只能查询自身应用。
- `GET /applications/{appId}/integration-health` 按当前应用一次性聚合应用登记、访问身份、业务字典、实验配置、用户分流、变体曝光和业务事件七个阶段；分流、曝光、事件分别使用一次批量 SQL，响应只提供中文说明、证据数量和下一步动作，不返回密钥内容。
- 管理台 `/applications` 提供应用空间治理入口，可编辑展示名、默认负责人、审批人列表、审批通过人数、审批 SLA、升级接收人、实验配额、`approvalRequired` 和发布窗口，可查看审批策略版本、集中处理配置/启动审批待办，并可按应用查看事件/指标字典。
- 管理台 `/applications` 增加“接入检查”一级页签，只按需读取当前应用状态，在横屏中集中展示七阶段链路、阻断摘要和处理入口。
- `PISCES_API_KEYS` 使用英文逗号分隔，作为兼容模式仍可用；兼容 key 拥有全部 scope，不建议生产长期使用。
- 前端管理台可通过 `VITE_PISCES_API_KEY` 注入 `X-Pisces-Api-Key` 请求头。

### AI

- AI 只输出建议，不自动执行
- `ai-design/v2` 已升级为单接口、内部分两阶段：先做 Schema Planning，再做 Draft Filling
- 设计链优先复用 `baselineConfig`，并要求对照组、实验组都返回完整配置值
- `diagnosis` 动作统一是 `MANUAL_ONLY`
- `graduation` 会被数据质量门禁修正
- `diagnosis` 和 `graduation` 响应会绑定 `evidence`，包含 `analysisReady`、SRM、样本量、阻断项、主指标、分组快照、统计事实和最新报告快照版本；旧 `autoGraduateDecision` 桥接结果也会透出同一份 `evidence`，调用方可审计 AI 建议、数据质量门禁与报告快照依据
- 本地最终验收会通过 `production-infrastructure-local-ai-smoke.sh` 调用 `/api/variants/generate`，要求 TongYi 文本模型真实返回候选变体，并在响应和 smoke summary 中记录实际命中的 `aiModel`、`aiApiMode`、是否回退和尝试模型列表后才允许完成。
- 管理台 `/variants-lab` 会在候选输出区以紧凑横向状态条展示实际命中的 TongYi 模型、调用协议、是否回退和尝试模型链路；核心截图 `09-variant-lab-tongyi-model-evidence.png` 纳入横屏布局审计，并作为本地完成验收的必需前端证据。
- 本地最终验收默认运行真实 Playwright 实验闭环：在 `shop-app` 中从页面调用千问生成完整方案，将全部方案填充到基础、字典、事件、指标、字段和分组配置，完成预检、创建、启动、数据生成、事实分析、报告快照及待审核结论；临时实验清理、实验数量恢复和零页面运行时错误均为 `COMPLETE` 的硬门禁。

### 实验配置

- 实验管理操作已写入 `pisces_audit_log` 审计日志，覆盖创建、更新、启动、暂停、恢复、停止、删除和人工结论状态流转。
- 人工结论推进到 `READY_FOR_REVIEW`、`GRADUATED` 或 `REJECTED` 时必须绑定当前 `configVersion` 和最新报告 `snapshotVersion`；缺少证据版本、无报告快照、报告未分析就绪、证据版本过期、毕业报告存在 SRM 或护栏异常时会拒绝提交且不改变当前结论；配置变更、草稿发布和配置回滚会把旧人工结论重置为 `NOT_READY` 并清空旧证据，避免新配置继续沿用旧报告结论。
- 实验首次启动时会把结论状态从 `NOT_READY` 自动同步为 `RUNNING`；暂停实验恢复运行时，会把未终态结论重新同步为 `RUNNING` 并清除待审核状态绑定的旧配置版本和报告快照。已经 `GRADUATED` 或 `REJECTED` 的终态实验不能恢复运行。
- 实验创建时会持久化 `appId` 和 `owner`；非 `admin` key 不能通过请求体伪造其他应用归属。`admin` 创建实验未指定负责人时，会优先使用应用空间默认负责人；已配置实验配额的应用会在创建前校验额度。
- `POST /experiments/preflight` 复用实验创建请求模型，在不写入实验、配置和审计记录的前提下，一次返回基础信息、应用字典、字段分组、流量、指标和治理策略的全部阻断项与提醒项；创建接口继续执行同源强校验，避免绕过预检直接写入无效配置。
- 管理台新建实验页使用右侧“创建前检查”抽屉集中展示结果，检查项可定位到现有配置页签；草案变化后旧结果立即失效，存在阻断项时禁止创建，只有提醒项时需再次确认。
- 应用空间启用 `approvalRequired` 后，新建、更新或保存配置草稿会进入 `PENDING` 审批状态；提交审批时会把审批人、通过人数和策略版本写入任务快照；保存配置草稿时会为当前 `draftVersion` 生成带策略快照的草稿审批记录；`approvalRequiredCount` 定义至少几名审批人通过才最终 `APPROVED`，保存应用空间时会校验该人数不超过有效审批人数量。
- `POST /experiments/{id}/approval-status` 会写入 `pisces_experiment_approval_vote` 投票记录并聚合当前审批结果：`APPROVED` 会先检查最新报告快照，存在 SRM 或护栏异常时拒绝通过；`admin` 可显式提交 `riskOverride=true` 和 `riskOverrideReason` 豁免阻断风险，豁免信息会进入审批审计详情；票数未达到任务快照 `approvalRequiredCount` 时保持 `PENDING` 并写入进度，达到后才更新实验/草稿审批为 `APPROVED`；任一 `REJECTED` 会立即终止为 `REJECTED`。非 `admin` key 必须在任务快照审批人列表内才能审批，且不能审批自己提交的配置/启动变更；历史任务缺少快照时回退当前应用空间策略。
- `GET /experiments/approval-tasks` 提供当前身份可见的配置/启动审批待办列表，默认返回 `PENDING`，支持按应用、负责人和审批状态过滤，并通过 `approvalType` 区分 `CONFIG_DRAFT` 与 `EXPERIMENT_START`；响应会返回 `approvalRequestedBy`、`approvalOwner`、`approvalOwners`、审批所需/已通过/已拒绝人数、进度文案、提交时间、已等待小时数、SLA 状态、升级接收人、最新报告风险上下文、`approvable` 和 `approvalDisabledReason` 供管理台展示和禁用无权/高风险操作，其中审批人和通过人数优先来自提交时策略快照。
- `POST /experiments/approval-escalations/scan` 会扫描逾期 `PENDING` 审批待办并幂等写入 `pisces_experiment_approval_escalation`；该表作为审批升级告警 outbox，保存 `notificationChannel=APPROVAL_ESCALATION_OUTBOX`、消息载荷、升级接收人、告警状态、投递状态和确认/关闭信息。`pisces_experiment_approval_escalation_delivery` 保存每个当前启用目标的通道投递回执。`GET /experiments/approval-escalations` 查询告警记录及 `notificationDeliveries`，`GET /experiments/approval-escalations/status` 查询业务状态、outbox 投递状态、通道 receipt 状态汇总和 dispatcher 目标元数据，`POST /experiments/approval-escalations/{escalationId}/ack` 把 `OPEN` 告警确认成 `ACKNOWLEDGED`；审批最终 `APPROVED` 或 `REJECTED` 后会自动把同任务打开/已确认告警关闭为 `RESOLVED`。
- `ApprovalEscalationScheduler` 已接入 `@Scheduled`：默认定时扫描逾期审批，外部投递默认关闭；启用 `PISCES_APPROVAL_ESCALATION_DISPATCH_ENABLED=true` 且配置 `PISCES_APPROVAL_ESCALATION_WEBHOOK_URL` 或 `PISCES_APPROVAL_ESCALATION_WEBHOOK_URLS` 后，会批量领取 `PENDING/RETRY/超时DISPATCHING` outbox，注册当前 dispatcher 目标，按目标 webhook 扇出 JSON 载荷，payload 包含 `dispatchChannel`。单个目标成功后不再重复投递，失败目标独立进入 `RETRY` 或 `DEAD`；outbox 总投递状态由当前启用通道回执聚合得出。
- `POST /experiments/approval-escalations/{escalationId}/notification/retry` 和 `POST /experiments/approval-escalations/dead/retry` 支持单条或批量手动重投 `DEAD` 告警，会把仍处于 `OPEN`/`ACKNOWLEDGED` 的 outbox 死信及当前启用 `DEAD` 通道回执重置为 `RETRY` 且立即可被调度领取；非 `admin` key 批量重投会自动限定到自身应用。
- 服务已引入 Spring Actuator 和 Prometheus registry，`/api/actuator/prometheus` 暴露审批升级告警指标。`ApprovalEscalationMetricsBinder` 默认每 30 秒刷新一次，输出业务状态、outbox 投递状态、通道回执状态、dispatcher 是否启用、目标数量、指标刷新健康度和刷新失败计数；刷新频率可用 `PISCES_APPROVAL_ESCALATION_METRICS_REFRESH_DELAY_MS` / `PISCES_APPROVAL_ESCALATION_METRICS_REFRESH_INITIAL_DELAY_MS` 调整。`docs/observability` 已提供 Prometheus 告警规则、Grafana 仪表盘模板和告警响应 Runbook，覆盖死信、未送达积压、指标刷新异常、指标缺失和 dispatcher 目标异常。
- `GET /experiments/{id}/config-draft/approvals` 提供实验配置草稿审批历史，按 `draftVersion` 倒序返回每个草稿版本的提交和审批记录。
- `GET /experiments/{id}/config-versions`、`POST /experiments/{id}/config-versions/publish`、`POST /experiments/{id}/config-versions/rollback` 提供配置发布历史和回滚能力；回滚会校验应用发布窗口，从已发布快照生成新的当前 `configVersion`，并保留当前生命周期状态和应用归属。
- `GET /experiments/{id}/config-draft`、`PUT /experiments/{id}/config-draft`、`POST /experiments/{id}/config-draft/publish` 提供配置草稿面；保存草稿不会触发运行时配置变更，但启用审批的应用会为当前草稿版本生成 `PENDING` 审批记录，发布草稿时会校验 `baseConfigVersion` 与当前版本一致且当前 `draftVersion` 的审批记录已 `APPROVED`，防止旧草稿或未审批草稿覆盖新配置。
- 启用审批的应用在发布配置草稿、启动或恢复实验前必须处于 `APPROVED`；启用发布窗口的应用在启动、恢复、运行中配置更新、配置草稿发布和配置回滚前必须处于窗口内；运行中实验不允许直接发布草稿，需要先暂停，审批通过后发布并恢复。
- 配置版本历史表为 `pisces_experiment_config_version`，需要执行 `pisces_experiment_config_version.sql` 建表。
- 配置草稿表为 `pisces_experiment_config_draft`，需要执行 `pisces_experiment_config_draft.sql` 建表。
- 配置草稿审批表为 `pisces_experiment_config_draft_approval`，需要执行 `pisces_experiment_config_draft_approval.sql` 建表；表内保存草稿审批策略快照，避免待审批草稿受后续应用空间策略变更影响。
- 审批投票表为 `pisces_experiment_approval_vote`，需要执行 `pisces_experiment_approval_vote.sql` 建表；审批升级告警表和通道回执表为 `pisces_experiment_approval_escalation` / `pisces_experiment_approval_escalation_delivery`，需要执行 `pisces_experiment_approval_escalation.sql` 建表；已有数据库也可执行 `pisces_application_space_approval_required_migration.sql` 一次性补齐应用空间审批字段、策略版本、SLA/升级字段、发布窗口字段、草稿审批快照字段、投票表、升级告警表、outbox 投递状态字段和通道回执表。
- 实验详情、列表、状态筛选、生命周期操作、审计查询和结论状态更新均按应用隔离。
- `GET /experiments` 支持 `status`、`statuses`、`appId`、`owner` 组合过滤；非 `admin` key 指定其他 `appId` 时只会返回空集合。
- 管理台实验列表提供应用 ID / 负责人筛选，并使用 `/applications` 作为应用 ID 候选来源。
- `GET /experiments/{id}/audit-logs` 返回最近审计日志；前端实验详情页展示操作时间、操作类型、操作人、状态变化、摘要和配置版本。
- 前端实验详情页展示配置草稿、草稿审批历史、配置版本历史、配置/启动审批、审计日志和人工结论证据；人工结论区会展示已绑定配置版本、已绑定报告快照版本、操作人、备注和最新报告快照，可直接生成报告快照，并在提交待审核或终态结论时带上 `expectedConfigVersion`、`reportSnapshotVersion` 和人工依据。编辑保存已切到配置草稿 API，草稿审批通过并发布后才会生成新的运行时配置版本，历史版本仍支持回滚。管理台应用页可维护审批人列表、通过人数、审批 SLA、升级接收人和发布窗口，会展示审批策略版本、审批提交人、审批负责人、审批进度、SLA 状态、升级原因、最新报告风险和无权审批原因；风险阻断时普通审批人只可拒绝，`admin` 填写豁免原因后可执行“豁免通过”。管理台应用页还提供审批升级告警 outbox，可手动扫描逾期审批、查看投递健康、dispatcher 目标数量、通道名、未送达/重试/死信计数、通道未送达数、每通道回执状态、`OPEN` 告警的投递状态、尝试次数、下次重试/送达时间、最近错误，并支持单条或批量重投死信、确认处理；统一监控侧可通过 Prometheus 拉取 `pisces_approval_escalation_*` 指标建立告警。
- 审计日志当前与 Zookeeper 配置写入不是同一事务；配置操作成功后审计写入失败会记录 WARN，不回滚已成功的配置变更。
- 实验创建、更新和详情响应支持 `layerId`；启动、恢复和运行中实验更新会检查同应用同互斥层是否已有 `RUNNING` 实验，存在冲突时拒绝变更。
- `groupConfigSchema` 是实验级可选字段定义
- `eventDefinitions` 和 `metricDefinitions` 是实验级必填定义
- 事件 key 和指标 key 都要求使用大写英文下划线格式
- 创建和更新实验时，会校验指标引用的事件是否已定义
- 创建和更新实验成功后，会把当前实验的事件定义和指标定义 upsert 到应用级字典；字典写入失败只记录 WARN，不回滚已保存的实验配置。
- 应用级字典表为 `pisces_application_event_definition` 和 `pisces_application_metric_definition`，需要执行 `pisces_application_dictionary.sql` 建表。
- 管理台新建实验页可填写 `appId` / `layerId`，并从应用字典导入未存在的事件与指标定义。
- 智能诊断与毕业建议在前端独立更新；任一请求失败不会清空另一项成功结果，页面会明确区分生成中、失败和已完成。
- 实验详情页把当前运行版本与发布历史分开表达，旧实验没有快照时显示“尚未记录发布快照”，不再以版本 0 表示。
- 定义 schema 后，实验组配置会按类型校验和归一化

### 数据

- `POST /traffic/assign` 仍保持兼容，只返回 groupId；`POST /traffic/assign/trace` 返回 groupId、命中原因、来源、策略和配置版本，供 SDK 与排障使用
- `/runtime/experiments/{id}/config`、`/runtime/experiments/{id}/config/version`、`/traffic/assign`、`/traffic/assign/trace`、`/data/event`、`/data/exposure` 和 `/identity/**` 需要 `runtime` scope。
- `/analysis/**` 和 MAB 查询接口需要 `analysis` scope；事件管道死信重投与派生重放允许 `analysis` 或 `management` scope。
- `/experiments/**`、`/variants/**`、MAB 重置和演示补数接口需要 `management` scope。
- 分流、数据上报、分析、事件管道治理和 MAB 操作会先校验实验所属 `appId`，避免跨应用读写实验数据。
- Java SDK 与 JS SDK 均支持 `assignGroupWithTrace`
- `GET /runtime/experiments/{id}/config` 返回 SDK 运行时配置和 `configVersion`；Java SDK 与 JS SDK 已切到该 runtime 接口，不再依赖管理端实验详情接口。
- `GET /runtime/experiments/{id}/config/version` 支持 SDK 轻量检查配置是否变化，并支持可选 `waitMillis` bounded long-poll；服务端在版本未变化时最多等待 30 秒，内部使用配置变更序列提前唤醒，避免变更通知发生在等待前时丢失唤醒，减少 TTL 过期后的完整配置下载和无效轮询。
- 多实例部署时可设置 `PISCES_CONFIG_CHANGE_REDIS_BROADCAST_ENABLED=true` 开启 Redis Pub/Sub 配置变更广播，并可通过 `PISCES_CONFIG_CHANGE_REDIS_CHANNEL` 覆盖通道名。当前实例保存或删除实验配置后会推进本地变更序列并广播实验 ID，其他实例收到远端消息后只推进本地变更序列和监听器，不二次广播；本地开发默认关闭该 listener，避免无 Redis 时启动噪音。广播链路会输出 `pisces_config_change_broadcast_*` 指标，覆盖启用状态、发送成功/失败/跳过、接收应用/忽略自身/非法消息、listener 异常以及最近成功发送/接收时间。
- Java SDK 与 JS SDK 会缓存实验配置快照；默认 TTL 为 60 秒，可关闭缓存，也可显式开启刷新失败时返回最后一次成功快照。TTL 过期后会先检查版本，未变化则续期缓存；可通过 `configVersionLongPollMillis` 让版本检查请求携带 `waitMillis`，默认关闭；`getGroupConfig` 会用 trace 返回的 `configVersion` 判断本地快照是否需要刷新。若 trace 版本较新但新配置拉取失败，只有在旧快照仍包含命中组配置时才允许 stale fallback，避免把缺失配置静默下发给业务。runtime 配置接口对可选事件定义、指标定义、组配置 schema、实验组 map 和组内 config 统一返回空集合或空 map，避免 SDK 侧拿到 `null`；API 层已补 `RuntimeConfigControllerContractTest` 固化 HTTP JSON 契约和 `knownVersion` / `waitMillis` 参数绑定。两端 SDK 默认不重试请求，生产可显式配置 `maxRetries`、`retryInitialBackoffMillis`、`retryMaxBackoffMillis` 和 `retryBackoffJitterRatio`，仅对网络异常、超时、空响应、HTTP `408/429/5xx` 和业务响应码 `408/429/5xx` 做指数退避重试；两端均暴露 `getMetricsSnapshot()` / `resetMetrics()`，便于业务侧上报请求尝试、成功、失败、重试、stale fallback、配置缓存命中/未命中和版本检查计数。`docs/operations/runtime-config-contract-matrix.md` 固化 runtime 配置契约和测试证据。`scripts/runtime-plane-release-drill.sh` 可对多实例 runtime 配置版本收敛、`assign/trace` 热路径和 Prometheus 运行时指标做发布演练；`scripts/runtime-plane-capacity-baseline.sh` 可输出分流热路径容量基线 JSONL；`scripts/runtime-plane-archive-baseline.sh` 可校验并归档容量基线 manifest；`scripts/runtime-plane-redis-fault-injection.sh` 可在手工或 Docker Redis 故障下注入演练降级恢复路径；`scripts/runtime-plane-release-package-check.sh` 可在发布前离线检查 runtime 契约、SDK、脚本、监控资产和文档入口是否随包完整，并输出 `target/pisces-runtime-release-package-check/report.json` 作为 CI 证据；`.github/workflows/runtime-plane-release-package.yml` 会在 PR/push 中运行测试模式并上传 `runtime-plane-release-package-report` artifact；`scripts/runtime-plane-release-evidence-archive.sh` 可把发布包报告、预发演练记录、容量基线 manifest 和 Redis 故障记录归档为发布批次 `manifest.json`，并通过 `PISCES_RELEASE_BATCH_COMPARE_MANIFEST_FILE` 与上一版或期望 manifest 做稳定字段比对；`scripts/runtime-plane-post-release-slo-review.sh` 可读取发布证据 manifest 和发布后指标快照，按错误率、P95/P99、缓存错误、广播错误、SDK 失败和 stale fallback 阈值输出自动化验收摘要；`scripts/runtime-plane-experiment-impact-sampling.sh` 可按实验和实例抽样 runtime config、version 一致性，并在显式开启 trace 时校验 `groupId`、`source`、`reason` 与 `configVersion`，输出实验级影响面摘要；`scripts/runtime-plane-staged-rollout-decision.sh` 可合并发布证据、SLO 回看、影响面抽样和人工准入记录，输出 `PROCEED`、`HOLD` 或 `ROLLBACK`，用于分批发布阶段门禁和回滚演练。`docs/observability/sdk-metrics-integration.md` 提供 Java Micrometer 与 JS Prometheus text 接入示例；`docs/observability/grafana/pisces-runtime-plane-dashboard.json`、`docs/operations/runtime-plane-release-checklist.md`、`docs/operations/runtime-plane-release-package-check.md`、`docs/operations/runtime-plane-preprod-drill-record-template.md`、`docs/operations/runtime-plane-release-evidence-archive.md`、`docs/operations/runtime-plane-post-release-slo-review.md`、`docs/operations/runtime-plane-experiment-impact-sampling.md`、`docs/operations/runtime-plane-staged-rollout-decision.md`、`docs/operations/runtime-plane-rollback-decision-drill-template.md` 和 `docs/operations/runtime-plane-post-release-incident-review-template.md` 覆盖运行时发布观测、发布门禁、预发证据记录、发布证据归档、发布后 SLO 回看、实验级影响面抽样、分批发布准入、回滚决策演练和异常复盘。
- 分流路径对 Redis 缓存读写做了降级保护；缓存不可用时退回基于当前配置直接计算，并继续保存数据库分流事实
- `/api/actuator/prometheus` 暴露分流热路径指标：`pisces_traffic_assignment_requests_total` 按 `result/source/reason` 统计分流结果，`pisces_traffic_assignment_latency_seconds` 按 `result/source` 统计分流耗时，`pisces_traffic_cache_events_total` 按 `operation/result` 统计 Redis 缓存命中、未命中、写入成功和异常；指标不包含实验 ID 或访客 ID，避免高基数标签。`docs/observability/prometheus/pisces-runtime-plane-alerts.yml` 和 `docs/observability/runtime-plane-runbook.md` 已提供运行时分流与配置广播告警模板、排查步骤和演练清单。
- 示例实验允许固定演示数据；配置 AI 时使用 AI 毕业决策，AI 不可用且返回 `AI_UNAVAILABLE` 风险时，达标演示样例会使用本地确定性演示结论，避免本地/预发环境因为缺少外部 AI Key 无法生成核心样例。
- `GET /traffic/experiment/{id}/mab/summary` 的分配概率计算已改为一次性读取 Redis Beta 参数并在内存中模拟，Gamma 采样使用常数复杂度实现；摘要同时暴露 `totalObservedRewards`、`ucbSelectionTrials`、组级 `observedRewardCount`、`observedSuccesses`、`observedFailures`、`ucbTrials` 和兼容字段 `totalTrials`，避免奖励观测口径与 UCB 选择口径混淆。
- MAB 奖励已切到观测键口径：主 `RATE + EVENT_COUNT` 指标的 denominator 事件先记录失败，numerator 事件按同一 `mabObservationId` 或 visitor 观测键升级为成功，成功不会被后续失败降级；事实表重放会重建 Redis 派生数据、MAB Beta 参数和奖励观测去重状态。
- 其余实验创建、统计和补数都应使用真实数据链路
- `scripts/local-experiment-workflow-smoke.sh` 使用当前本地 `shop-app` 应用字典创建临时二手手机实验，验证预检、创建、启动、分流、曝光、事件上报、异步物化、统计、报告快照和人工结论门禁，并在完成或失败后清理临时实验；摘要写入 `target/pisces-local-experiment-workflow-smoke/summary.json`，不保存 API Key。
- 已有实验补数时，会按实验自己的事件定义生成事件，而不是回退到固定 `VIEW` / `CLICK` / `CONVERT`
- 统计结果总览暴露 `totalAssignments`、`totalExposures`、`totalEvents`，分组统计暴露 `assignmentCount`、`exposureCount` 和事件计数
- 前端实验详情页与决策页展示数据链路状态，用于判断 assignment、exposure、event、analysis readiness 和异步事件管道是否可用
- 前端事件重放计划会在提交前校验时间范围完整性、先后顺序和分段要求，错误时不发送请求并使用中文提示。
- 事件上报使用 `properties.clientEventId` 做服务端幂等；同一实验内重复 `clientEventId` 不重复进入 inbox
- `/data/event` 与 `/data/exposure` 已改为写入 MySQL inbox，由后台消费者异步物化事件事实、曝光事实、Redis 派生计数和 MAB 奖励
- 后台消费者已写入 `pisces_event_materialization` 事实派生物化账本；事实写入成功但 Redis/MAB 派生失败后重试时，会回查事实表中的真实 `eventId` / `exposureId` 后补写派生数据和账本，账本存在时跳过重复派生，避免后续复制型 replay 使用新 inbox ID 污染事实账本。
- `GET /analysis/experiment/{id}/event-pipeline` 返回 pending、processing、retry、done、dead、rejected、最大积压秒数和健康状态
- `POST /analysis/experiment/{id}/event-pipeline/dead/retry` 可按实验维度重新投递 DEAD 记录
- `POST /analysis/experiment/{id}/event-pipeline/drain` 会按实验维度同步领取并物化当前 due inbox 记录，适用于演示生成、排障和受控运维前置 drain。
- `POST /analysis/experiment/{id}/events/replay` 当前提交后台 worker 按事实表重建 Redis 派生数据和 MAB 奖励，用于修复事实已落库但派生数据不一致的场景；每次重放会写入 `pisces_event_replay_job`，记录 `replayMode`、时间范围、事件类型、事件/曝光开关和是否全量派生重建，接口返回 `REPLAY_DERIVED/RUNNING`、`replayJobId` / `replayJobStatus` / scope，并通过 `active_key` 唯一约束拒绝同实验并发重放，超时 `RUNNING` / `CANCEL_REQUESTED` 任务会按 `pisces.event-pipeline.replay.job-timeout-minutes` 标记失败后释放互斥；等价全量范围使用 `FULL_DERIVED_REBUILD` 清空并重建派生数据，筛选范围使用 `FILTERED_DERIVED_COPY_REPLAY` 复制型 replay：按 `PISCES_EVENT_REPLAY_BATCH_SIZE` 分页遍历 scope 内事实，Redis 列表未包含同一 `eventId` / `exposureId` 时才补派生，并以 `REPLAY_COPY` 刷新物化账本。服务端通过 `PISCES_EVENT_REPLAY_MAX_FILTERED_COPY_FACTS` 限制筛选复制型 replay 最大匹配事实数，默认 50000，超过上限会在创建 job 前拒绝。
- `POST /analysis/experiment/{id}/events/replay/plan` 会只读统计重放范围内的事实数，支持时间范围、事件类型、事件/曝光开关、分组明细、已物化计数和缺账本计数；请求带 `segmentCount > 1` 且同时指定 `startTime/endTime` 时，会返回时间分段 `segments`、`segmentRecoverySupported`、最大单段影响面和最大单段缺口。前端数据链路状态区会展示物化覆盖率和分段巡检表，便于执行重放前判断修复缺口。筛选计划对应复制型 replay，不清空现有 Redis/MAB 派生数据；广义派生漂移仍建议通过全量 replay 关闭。
- `POST /analysis/experiment/{id}/events/replay/materialization/repair` 会按重放计划修复缺失派生物化账本；无缺口时 no-op，存在缺口且等价全量计划时通过全量派生重放修复，存在缺口的筛选计划会走局部补物化：仅查询缺账本事实，Redis 列表已存在同一 `eventId` / `exposureId` 时只补账本，否则补对应派生和账本；广义 Redis/MAB 派生漂移仍需全量重放。`/events/replay/materialization/repair/segments/{segmentIndex}` 支持按计划分段只恢复单个时间窗口，适合失败重试分段恢复。
- `GET /analysis/experiment/{id}/events/replay/jobs` 与 `GET /analysis/experiment/{id}/events/replay/jobs/{replayJobId}` 返回 replay job 状态、操作者、重放模式、scope、计划事实数、运行中进度百分比、影响行数、事件/曝光/MAB 计数、错误信息和起止时间；运行中的 `affectedCount`、`eventCount`、`exposureCount`、`groupCount` 和 `mabRewardCount` 会在批处理安全点累计更新，终态为最终值；`POST /analysis/experiment/{id}/events/replay/jobs/{replayJobId}/cancel` 会将运行中任务置为 `CANCEL_REQUESTED` 并保留互斥键，后台 worker 在一致性安全点转为 `CANCELLED` 后释放互斥，前端数据链路状态区会展示最近一次重放任务的模式、事实范围、事件类型、计划总量、已处理/剩余数量、进度条和运行中取消入口。
- 标准 HTTP 4xx 业务码会映射到同语义 HTTP 状态；例如 replay 并发互斥返回 HTTP 409，响应体仍保留 `code=409` 和业务消息。
- 重放 job 表为 `pisces_event_replay_job`，需要执行 `pisces_event_replay_job.sql` 建表；已有环境需要执行 `pisces_event_replay_job_scope_migration.sql` 补齐重放 scope 字段。
- 派生物化账本表为 `pisces_event_materialization`，需要执行 `pisces_event_materialization.sql` 建表。
- `scripts/event-pipeline-replay-audit.sh` 可在只读模式采集事件管道状态、重放计划物化覆盖、分段巡检和统计总览，也可显式执行 `/events/replay` 后轮询 replay job 终态，或执行 `/events/replay/materialization/repair` / `/events/replay/materialization/repair/segments/{segmentIndex}` 后校验操作结果、修复后物化覆盖、重放后健康状态、未完成/重试/死信/拒绝计数、积压秒数和统计总览不回退；脚本支持 `PISCES_EVENT_REPLAY_START_TIME`、`PISCES_EVENT_REPLAY_END_TIME`、`PISCES_EVENT_REPLAY_EVENT_TYPES`、`PISCES_EVENT_REPLAY_INCLUDE_EVENTS`、`PISCES_EVENT_REPLAY_INCLUDE_EXPOSURES`、`PISCES_EVENT_REPLAY_SEGMENT_COUNT` 和 `PISCES_EVENT_REPLAY_REPAIR_SEGMENT_INDEX` 构造同一份 `replayScopeRequest`，支持 `PISCES_EVENT_REPLAY_MAX_AFFECTED_PLAN` 对重放计划匹配事实数设门禁，并输出到 `target/pisces-event-pipeline-replay-audit/summary.json`。
- `scripts/runtime-plane-release-package-check.sh` 已把事件补物化修复、筛选复制型 replay 批量分页、运行中进度回写、计划总量/进度百分比契约和取消安全点纳入发布包门禁，检查修复开关、修复端点、修复后覆盖率 gate、样例证据、CI path trigger 和事件管道聚焦测试命令，并默认执行 `scripts/event-pipeline-replay-audit-scope-smoke-test.sh` 与 `scripts/runtime-plane-release-evidence-archive-smoke-test.sh`，分别验证修复前 plan、repair、修复后 plan 使用同一份 `replayScopeRequest`，以及事件 replay audit summary 可进入发布证据 manifest，避免事件审计脚本或证据模板漏随包发布。
- `scripts/runtime-plane-release-evidence-archive.sh` 支持 `PISCES_EVENT_REPLAY_AUDIT_SUMMARY_FILE` 归档事件 replay audit summary，校验 `summaryType=pisces-event-pipeline-replay-audit` 和 `status=PASS`，并在发布批次 manifest 中记录 `eventPipelineReplayAudit` 与 `evidence.eventPipelineReplayAuditSummary`。

### 兼容接口

系统仍保留部分旧接口，但当前推荐入口已经切到：

- `ai-design/v2`
- `ai-diagnosis`
- `ai-graduation-decision`
- `/variants/generate`

## 当前文档策略

只保留和当前代码一致的 Markdown，不再维护历史计划、旧版说明和测试记录。
