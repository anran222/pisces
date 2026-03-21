# Pisces Java SDK

`pisces-sdk-java` 是面向后端服务的运行时接入 SDK。

## 覆盖能力

- 分流
- 查询实验详情
- 查询实验级 `eventDefinitions`
- 查询实验级 `metricDefinitions`
- 查询实验级 `groupConfigSchema`
- 查询当前命中组的 `config`
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
        .build();

String experimentId = "exp_price_001";
String visitorId = "visitor_001";

String groupId = client.assignGroup(experimentId, visitorId, Map.of("city", "shanghai"));
ExperimentConfig experiment = client.getExperiment(experimentId);
var eventDefinitions = client.getEventDefinitions(experimentId);
var metricDefinitions = client.getMetricDefinitions(experimentId);
var schema = client.getGroupConfigSchema(experimentId);
Map<String, Object> groupConfig = client.getGroupConfig(experimentId, visitorId);
client.reportEventByKey(experimentId, visitorId, "PAY_SUCCESS", Map.of("orderId", "ord_001"));
```

## 核心方法

- `assignGroup(String experimentId, String visitorId)`
- `assignGroup(String experimentId, String visitorId, Map<String, Object> attributes)`
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

## 返回模型

`ExperimentConfig` 当前包含：

- `id`
- `name`
- `description`
- `status`
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
