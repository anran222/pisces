# Pisces Java SDK

`pisces-sdk-java` 是面向后端服务的运行时接入 SDK。

## 覆盖能力

- 分流
- 分流 trace：命中原因、来源、策略和配置版本
- 通过 runtime scope 查询实验配置
- 实验配置本地快照缓存，默认 TTL 60 秒
- 查询实验级 `eventDefinitions`
- 查询实验级 `metricDefinitions`
- 查询实验级 `groupConfigSchema`
- 查询当前命中组的 `config`
- 可配置瞬时错误重试、指数退避和 jitter
- 暴露 SDK 本地运行指标快照
- 上报曝光
- 按实验事件键上报事件

## 快速开始

```java
import com.pisces.sdk.PiscesClient;
import com.pisces.sdk.model.ExperimentConfig;

import java.util.Map;

PiscesClient client = PiscesClient.builder()
        .baseUrl("http://localhost:9990/api")
        .timeoutMillis(30000)
        .experimentCacheTtlMillis(60000)
        .configVersionLongPollMillis(25000)
        .allowStaleExperimentConfig(true)
        .maxRetries(2)
        .retryInitialBackoffMillis(100)
        .retryMaxBackoffMillis(1000)
        .retryBackoffJitterRatio(0.2D)
        .build();

String experimentId = "exp_price_001";
String visitorId = "visitor_001";

String groupId = client.assignGroup(experimentId, visitorId, Map.of("city", "shanghai"));
var assignment = client.assignGroupWithTrace(experimentId, visitorId, Map.of("city", "shanghai"));
ExperimentConfig experiment = client.getExperiment(experimentId);
var eventDefinitions = client.getEventDefinitions(experimentId);
var metricDefinitions = client.getMetricDefinitions(experimentId);
var schema = client.getGroupConfigSchema(experimentId);
Map<String, Object> groupConfig = client.getGroupConfig(experimentId, visitorId);
client.reportEventByKey(experimentId, visitorId, "PAY_SUCCESS", Map.of("orderId", "ord_001"));
var sdkMetrics = client.getMetricsSnapshot();
```

## 核心方法

- `assignGroup(String experimentId, String visitorId)`
- `assignGroup(String experimentId, String visitorId, Map<String, Object> attributes)`
- `assignGroupWithTrace(String experimentId, String visitorId)`
- `assignGroupWithTrace(String experimentId, String visitorId, Map<String, Object> attributes)`
- `getExperiment(String experimentId)`
- `getEventDefinitions(String experimentId)`
- `getMetricDefinitions(String experimentId)`
- `getGroupConfigSchema(String experimentId)`
- `getGroupConfig(String experimentId, String visitorId)`
- `getGroupConfig(String experimentId, String visitorId, Map<String, Object> attributes)`
- `reportExposure(String experimentId, String visitorId, Map<String, Object> properties)`
- `reportEvent(String experimentId, String visitorId, String eventType, String eventName, Map<String, Object> properties)`
- `reportEventByKey(String experimentId, String visitorId, String eventKey, Map<String, Object> properties)`
- `reportView(String experimentId, String visitorId, Map<String, Object> properties)`
- `reportClick(String experimentId, String visitorId, Map<String, Object> properties)`
- `reportConvert(String experimentId, String visitorId, Map<String, Object> properties)`
- `clearExperimentCache()`
- `getMetricsSnapshot()`
- `resetMetrics()`

## 配置快照缓存

- SDK 通过 `GET /runtime/experiments/{id}/config` 拉取配置，运行时只需要 `runtime` scope。
- TTL 过期后，SDK 会先调用 `GET /runtime/experiments/{id}/config/version?knownVersion=...`，配置未变化时只续期本地快照。
- `getExperiment`、`getGroupConfigSchema`、`getEventDefinitions`、`getMetricDefinitions` 和 `getGroupConfig` 会复用实验配置快照。
- `getGroupConfig` 会先调用 trace 分流，并用返回的 `configVersion` 判断本地配置是否需要刷新。
- 默认 `experimentCacheTtlMillis` 为 `60000`，设置为 `0` 可关闭实验配置缓存。
- 默认 `configVersionLongPollMillis` 为 `0`，即版本检查不等待；设置大于 `0` 后，TTL 过期的版本检查会追加 `waitMillis`，服务端最多等待 30 秒，并通过服务端配置变更序列提前唤醒。
- `allowStaleExperimentConfig(true)` 后，TTL 过期刷新失败时会返回最后一次成功获取的实验配置；如果 trace 分流返回了更新的 `configVersion` 但新配置拉取失败，仅当旧快照仍包含当前命中组配置时才会回退使用旧配置。默认关闭，避免静默掩盖远端故障。

## 重试与本地指标

- 默认 `maxRetries` 为 `0`，保持单次请求语义；生产接入建议按业务延迟预算设置为 `1` 或 `2`。
- SDK 仅重试瞬时错误：网络 IO 异常、空响应、HTTP `408` / `429` / `5xx`，以及业务响应码 `408` / `429` / `5xx`。
- `retryInitialBackoffMillis`、`retryMaxBackoffMillis` 和 `retryBackoffJitterRatio` 控制指数退避和抖动，避免配置刷新或事件上报在依赖抖动时集中重试。
- `getMetricsSnapshot()` 返回本地计数：请求尝试、成功、失败、重试次数、stale fallback 次数、配置缓存命中/未命中和版本检查次数。
- `resetMetrics()` 可在业务侧周期性上报后清零。

## 返回模型

`ExperimentConfig` 当前包含：

- `id`
- `name`
- `description`
- `status`
- `configVersion`
- `eventDefinitions`
- `metricDefinitions`
- `groupConfigSchema`
- `groups`
- `traffic`

## 不包含

- 实验创建和管理
- AI 设计、诊断、毕业
- 变体生成
- 演示实验生成
