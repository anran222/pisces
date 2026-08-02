# 领域模型

## Experiment

核心实验实体，包含：

- `id`
- `name`
- `description`
- `status`
- `startTime`
- `endTime`
- `appId`
- `owner`
- `creator`

## ExperimentMetadata

实验配置快照，包含：

- `experiment`
- `appId`
- `owner`
- `layerId`
- `groups`
- `traffic`
- `eventDefinitions`
- `metricDefinitions`
- `groupConfigSchema`
- `configVersion`
- `conclusionStatus`
- `conclusionUpdatedAt`
- `conclusionConfigVersion`
- `conclusionReportSnapshotVersion`
- `conclusionOperator`
- `conclusionComment`
- `approvalStatus`
- `approvalOperator`
- `approvalComment`
- `approvalUpdatedAt`

## ExperimentConfigVersion

实验配置发布版本，包含：

- `experimentId`
- `configVersion`
- `metadata`
- `publishedBy`
- `publishComment`
- `sourceConfigVersion`
- `sourceType`
- `publishedAt`

## ExperimentConfigDraft

实验配置草稿，包含：

- `experimentId`
- `draftVersion`
- `baseConfigVersion`
- `metadata`
- `updatedBy`
- `draftComment`
- `createdAt`
- `updatedAt`

## ExperimentConfigDraftApproval

配置草稿审批记录，包含：

- `experimentId`
- `draftVersion`
- `baseConfigVersion`
- `approvalStatus`
- `requestedBy`
- `draftComment`
- `approvalOperator`
- `approvalComment`
- `approvalUpdatedAt`
- `createdAt`
- `updatedAt`

配置草稿保存后会为当前 `draftVersion` 创建审批记录，发布草稿时校验该草稿版本的审批记录已通过；实验详情侧可按实验查询全部草稿审批记录，用于追溯每个草稿版本的审批结果。

## ExperimentResponse

实验详情响应会在核心实验字段基础上额外返回：

- `configVersion`
- `layerId`
- `groups`
- `traffic`
- `eventDefinitions`
- `metricDefinitions`
- `groupConfigSchema`
- `conclusionStatus`
- `conclusionUpdatedAt`
- `conclusionConfigVersion`
- `conclusionReportSnapshotVersion`
- `conclusionOperator`
- `conclusionComment`
- `suggestedConclusionStatus`
- `suggestedConclusionUpdatedAt`
- `approvalStatus`
- `approvalOperator`
- `approvalComment`
- `approvalUpdatedAt`

## ApplicationSpace

数据库化应用空间注册信息，包含：

- `appId`
- `displayName`
- `defaultOwner`
- `approvalOwners`
- `approvalRequiredCount`
- `approvalPolicyVersion`
- `approvalSlaHours`
- `approvalEscalationOwners`
- `experimentQuota`
- `approvalRequired`
- `releaseWindowEnabled`
- `releaseWindowTimezone`
- `releaseWindowDays`
- `releaseWindowStartTime`
- `releaseWindowEndTime`
- `createdBy`
- `updatedBy`
- `createdAt`
- `updatedAt`

## ApplicationSpaceResponse

应用空间目录响应，当前由数据库注册表、API Key 配置和已有实验归并生成，包含：

- `appId`
- `displayName`
- `defaultOwner`
- `experimentQuota`
- `quotaUsed`
- `quotaRemaining`
- `approvalRequired`
- `approvalOwners`
- `approvalRequiredCount`
- `approvalPolicyVersion`
- `approvalSlaHours`
- `approvalEscalationOwners`
- `releaseWindowEnabled`
- `releaseWindowTimezone`
- `releaseWindowDays`
- `releaseWindowStartTime`
- `releaseWindowEndTime`
- `owners`
- `scopes`
- `configured`
- `registered`
- `apiKeyCount`
- `experimentCount`
- `runningExperimentCount`

## ReleaseWindow

应用级发布窗口策略，当前存放在 `ApplicationSpace` 中，包含：

- `releaseWindowEnabled`
- `releaseWindowTimezone`
- `releaseWindowDays`，1 表示周一，7 表示周日
- `releaseWindowStartTime`，格式 `HH:mm`
- `releaseWindowEndTime`，格式 `HH:mm`

未启用时不拦截。启用后，启动、恢复、运行中配置更新、配置草稿发布和配置回滚必须落在窗口内；单纯保存配置草稿和记录当前配置快照不受窗口限制。

## ApprovalSlaContext

审批待办基于应用空间策略和提交时间派生 SLA/升级上下文，当前作为 `ExperimentApprovalTaskResponse` 字段返回，包含：

- `approvalSubmittedAt`
- `approvalElapsedHours`
- `approvalSlaHours`
- `approvalSlaStatus`：`ON_TRACK` / `DUE_SOON` / `OVERDUE`
- `approvalEscalationOwners`
- `approvalEscalationReason`

`approvalSlaHours` 为空时不启用 SLA 状态。超过 SLA 的 80% 会标记 `DUE_SOON`，达到或超过 SLA 会标记 `OVERDUE`。若应用未显式配置 `approvalEscalationOwners`，待办响应会回退当前任务审批人作为升级接收人。该上下文只用于展示和外部告警集成，不会自动改变审批状态。

## ExperimentApprovalEscalation

审批升级告警 outbox 记录，包含：

- `escalationId`
- `experimentId`
- `approvalType`
- `draftVersion`
- `appId`
- `owner`
- `experimentName`
- `approvalSubmittedAt`
- `approvalElapsedHours`
- `approvalSlaHours`
- `approvalSlaStatus`
- `escalationOwners`
- `escalationReason`
- `notificationChannel`
- `notificationPayload`
- `notificationStatus`：`PENDING` / `DISPATCHING` / `SENT` / `RETRY` / `DEAD`
- `notificationDeliveries`
- `notificationAttemptCount`
- `notificationLastAttemptAt`
- `notificationNextAttemptAt`
- `notificationDeliveredAt`
- `notificationLastError`
- `escalationStatus`：`OPEN` / `ACKNOWLEDGED` / `RESOLVED`
- `acknowledgedBy`
- `acknowledgedComment`
- `acknowledgedAt`
- `resolvedBy`
- `resolvedReason`
- `resolvedAt`
- `createdAt`
- `updatedAt`

投递状态由调度器推进：新 outbox 默认为 `PENDING`，领取投递时进入 `DISPATCHING` 并设置短锁时间；调度器会把当前 dispatcher 目标注册为通道回执，逐通道投递并更新 `notificationDeliveries`。outbox 的 `notificationStatus` 由当前启用回执聚合：任一通道 `DEAD` 则整体 `DEAD`，任一通道 `RETRY` 则整体 `RETRY`，任一通道 `DISPATCHING/PENDING` 则整体保持待投递，全部通道 `SENT` 后整体 `SENT`。`escalationStatus` 与审批业务闭环独立，人工确认只改变 `OPEN -> ACKNOWLEDGED`，审批最终通过或拒绝会自动把同任务打开/已确认告警关闭为 `RESOLVED`。

死信重投不会重新创建告警记录，只会把活跃告警及其当前启用 `DEAD` 通道回执重置为 `RETRY`，清空最近错误并把下一次投递时间设置为当前操作时间。已 `RESOLVED` 的历史告警不参与重投和活跃投递健康计算。

## ExperimentApprovalEscalationDelivery

审批升级告警通道投递回执，包含：

- `escalationId`
- `channelName`
- `targetKey`
- `notificationStatus`：`PENDING` / `DISPATCHING` / `SENT` / `RETRY` / `DEAD`
- `notificationAttemptCount`
- `notificationLastAttemptAt`
- `notificationNextAttemptAt`
- `notificationDeliveredAt`
- `notificationLastError`
- `active`
- `createdAt`
- `updatedAt`

`targetKey` 是投递目标匿名标识，不暴露 webhook URL。dispatcher 配置变化时，当前目标会被注册为 `active=1`；不再启用的旧目标会被置为 `active=0`，不参与后续整体状态聚合。

## ExperimentApprovalEscalationStatus

审批升级告警投递状态汇总，包含：

- `totalCount`
- `openCount`
- `acknowledgedCount`
- `resolvedCount`
- `pendingCount`
- `dispatchingCount`
- `sentCount`
- `retryCount`
- `deadCount`
- `undeliveredCount`
- `deliveryPendingCount`
- `deliveryDispatchingCount`
- `deliverySentCount`
- `deliveryRetryCount`
- `deliveryDeadCount`
- `deliveryUndeliveredCount`
- `healthy`
- `status`：`NO_DATA` / `PENDING` / `RETRY` / `DEAD` / `SENT`
- `dispatcherEnabled`
- `dispatcherTargetCount`
- `dispatcherChannels`
- `generatedAt`

扫描接口只为 `OVERDUE` 且仍为 `PENDING` 的审批任务创建告警。告警按 `(experimentId, approvalType, draftVersion, approvalSubmittedAt)` 幂等；管理台或外部系统确认后进入 `ACKNOWLEDGED`，审批任务最终通过或拒绝后自动关闭为 `RESOLVED`。

## ExperimentApprovalVote

配置/启动审批投票记录，包含：

- `experimentId`
- `approvalType`
- `draftVersion`
- `approvalStatus`
- `approvalOperator`
- `approvalComment`
- `createdAt`
- `updatedAt`

投票按 `(experimentId, approvalType, draftVersion, approvalOperator)` 唯一。启动审批的 `draftVersion` 固定为 `0`；配置草稿审批使用对应草稿版本。审批通过票数达到应用空间 `approvalRequiredCount` 后才最终通过，拒绝票会立即终止当前审批。

## ApprovalRiskContext

审批任务基于最新 `ExperimentReportSnapshot` 派生的风险上下文，当前作为 `ExperimentApprovalTaskResponse` 字段返回，包含：

- `approvalRiskLevel`：`UNKNOWN` / `CLEAR` / `WARNING` / `BLOCKED`
- `approvalRiskFlags`：例如 `ANALYSIS_NOT_READY`、`SRM`、`GUARDRAIL_BREACHED`
- `guardrailStatus`
- `analysisReady`
- `hasSrm`
- `breachedGuardrails`
- `latestReportSnapshotVersion`
- `latestReportGeneratedAt`
- `approvalRiskDisabledReason`
- `riskOverrideRequired`
- `riskOverrideAllowed`

没有报告快照时风险等级为 `UNKNOWN`，不阻塞审批。最新报告存在 SRM 或护栏异常时风险等级为 `BLOCKED`，普通 `APPROVED` 操作会被拒绝；`admin` 显式提交 `riskOverride=true` 和 `riskOverrideReason` 可豁免，`REJECTED` 仍允许执行。

## ApprovalPolicySnapshot

审批任务提交时固化的治理策略快照，当前分别存放在 `ExperimentMetadata` 和 `ExperimentConfigDraftApproval` 中，包含：

- `approvalOwnersSnapshot`
- `approvalRequiredCountSnapshot`
- `approvalPolicyVersion`

审批操作、待办展示和投票聚合优先使用快照；老数据没有快照时回退当前 `ApplicationSpace` 策略。应用空间审批策略变化会递增 `approvalPolicyVersion`，但不会改变已经提交的待审批任务。

## ApplicationDictionaryResponse

应用级事件和指标字典响应，包含：

- `appId`
- `eventDefinitions`
- `metricDefinitions`

字典由实验创建和更新流程自动沉淀，用于把多个实验复用的业务事件和指标口径收敛到应用空间维度。

## ApplicationEventDefinition

应用级事件定义，包含：

- `appId`
- `key`
- `label`
- `description`
- `category`
- `primary`
- `sourceExperimentId`
- `updatedBy`
- `createdAt`
- `updatedAt`

## ApplicationMetricDefinition

应用级指标定义，包含：

- `appId`
- `key`
- `name`
- `description`
- `aggregationType`
- `numeratorEventType`
- `denominatorType`
- `denominatorEventType`
- `primaryMetric`
- `guardrailMetric`
- `sourceExperimentId`
- `updatedBy`
- `createdAt`
- `updatedAt`

## RuntimeExperimentConfigVersionResponse

SDK 运行时配置版本检查响应，包含：

- `experimentId`
- `knownVersion`
- `currentVersion`
- `changed`
- `status`
- `generatedAt`

## ExperimentGroup

实验组定义，包含：

- `id`
- `name`
- `trafficRatio`
- `config`

`config` 是每个实验组的实际值，是否有结构由 `groupConfigSchema` 决定。

## EventDefinition

实验级事件定义，包含：

- `key`
- `label`
- `description`
- `category`
- `primary`

`key` 当前要求使用大写英文下划线格式。

## MetricDefinition

实验级指标定义，包含：

- `key`
- `name`
- `description`
- `aggregationType`
- `numeratorEventType`
- `denominatorType`
- `denominatorEventType`
- `primaryMetric`
- `guardrailMetric`

指标通过实验自己的事件定义计算，不再默认回退到固定点击/转化口径。

## GroupConfigFieldDefinition

实验级可选字段定义，包含：

- `key`
- `label`
- `valueType`
- `required`
- `description`
- `defaultValue`

当前支持的 `valueType`：

- `STRING`
- `INTEGER`
- `BOOLEAN`
- `OBJECT`
- `JSON`

## TrafficConfig

实验流量配置，包含：

- `totalTraffic`
- `strategy`
- `allocation`
- 可选 `rules`

## Event / Exposure

数据层核心输入：

- `experimentId`
- `visitorId`
- `eventType`
- `eventName`
- `properties`

`eventType` 既支持历史兼容事件，也支持实验定义的自定义事件 key。

## Statistics

统计聚合结果，包含：

- `summary`
- `groupStatistics`
- `dataQualityCheck`

`dataQualityCheck` 是当前分析与 AI 决策门禁的事实来源。

## AI 结果模型

### `AIDesignResponse`

- `decisionType`
- `summary`
- `confidence`
- `riskFlags`
- `guardrailStatus`
- `experimentDraft`

### `AIDiagnosisResponse`

- `decisionType`
- `summary`
- `confidence`
- `riskFlags`
- `guardrailStatus`
- `recommendedActions`
- `evidence`

### `AIGraduationDecisionResponse`

- `decisionType`
- `summary`
- `confidence`
- `riskFlags`
- `guardrailStatus`
- `decision`
- `evidence`

### `AIDecisionEvidenceResponse`

- `experimentId`
- `experimentName`
- `experimentStatus`
- `analysisReady`
- `hasSrm`
- `srmPValue`
- `sampleSizeReached`
- `requiredSampleSizePerGroup`
- `blockingIssues`
- `warnings`
- `primaryMetricKey`
- `bestPerformingGroup`
- `bestPrimaryMetricValue`
- `totalAssignments`
- `totalExposures`
- `totalEvents`
- `totalVisitors`
- `breachedGuardrails`
- `latestReportSnapshotVersion`
- `latestReportGeneratedAt`
- `latestReportConclusionStatus`
- `latestReportAnalysisReady`
- `latestReportHasSrm`
- `latestReportPrimaryMetricKey`
- `latestReportBestPerformingGroup`
- `latestReportWinningVariant`
- `latestReportBreachedGuardrails`
- `statisticsFacts`
- `groupMetricSnapshots`
- `dataQualityFacts`
- `reportSnapshotFacts`
