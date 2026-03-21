# 领域模型

## Experiment

核心实验实体，包含：

- `id`
- `name`
- `description`
- `status`
- `startTime`
- `endTime`
- `conclusionStatus`

## ExperimentMetadata

实验配置快照，包含：

- `experiment`
- `groups`
- `traffic`
- `eventDefinitions`
- `metricDefinitions`
- `groupConfigSchema`
- `configVersion`

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

### `AIGraduationDecisionResponse`

- `decisionType`
- `summary`
- `confidence`
- `riskFlags`
- `guardrailStatus`
- `decision`
