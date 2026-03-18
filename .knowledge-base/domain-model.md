# 领域模型

## 1. 核心实体

| 实体 | 位置 | 作用 |
| --- | --- | --- |
| `Experiment` | `pisces-common/.../model/Experiment.java` | 实验基本信息与生命周期状态 |
| `ExperimentMetadata` | `pisces-common/.../model/ExperimentMetadata.java` | 实验完整配置载体，实际配置中心存储对象 |
| `ExperimentGroup` | `pisces-common/.../model/ExperimentGroup.java` | 实验组定义与组内配置 |
| `TrafficConfig` | `pisces-common/.../model/TrafficConfig.java` | 总流量、分配策略、组配比 |
| `Event` | `pisces-common/.../model/Event.java` | 采集到的行为事件 |
| `Statistics` | `pisces-common/.../model/Statistics.java` | 聚合后的实验统计结果 |
| `ExperimentLayer` | `pisces-common/.../model/ExperimentLayer.java` | Layer 分层与互斥/正交关系 |

## 2. `Experiment`

核心字段：

- `id`
- `name`
- `description`
- `status`: `DRAFT / RUNNING / PAUSED / STOPPED`
- `startTime / endTime`
- `creator / createTime / updateTime`

用途：

- 生命周期控制
- 分流前状态检查
- 分析报表基础元信息

## 3. `ExperimentMetadata`

这是项目最关键的配置聚合对象，包含：

- `configVersion`：配置版本，驱动分流缓存失效
- `layerId`：流量层
- `experiment`
- `groups`
- `traffic`
- `whitelist`
- `blacklist`

设计意图：

- `Experiment` 只管实验本身
- `ExperimentMetadata` 才是“可分流、可分析”的完整实验

## 4. `ExperimentGroup`

字段：

- `id`
- `name`
- `trafficRatio`
- `config`

`config` 是可变的扩展载体，项目里的演示数据会在这里放 UI/价格/信任元素等参数。

## 5. `TrafficConfig`

字段：

- `totalTraffic`
- `allocation`
- `strategy`
- `hashKey`
- `rules`
- `ruleFallbackStrategy`

当前支持的策略名：

- `RANDOM`
- `HASH`
- `RULE`
- `THOMPSON_SAMPLING`
- `UCB`

注意：

- `RULE` 已支持最小规则引擎
- 规则条件当前支持 `EQ / IN / CONTAINS / EXISTS`
- 若规则未命中，则按 `ruleFallbackStrategy` 回退，默认 `HASH`

## 6. `Event`

字段：

- `eventId`
- `experimentId`
- `userId`
- `groupId`
- `eventType`
- `eventName`
- `properties`
- `timestamp`

重要兼容约定：

- 字段名仍叫 `userId`
- 实际业务语义已经切换为 `visitorId`
- 相关说明在 `Event`、`EventReportRequest`、`Statistics.GroupStatistics` 里都能看到兼容性注释

默认事件类型：

- `VIEW`
- `CLICK`
- `CONVERT`

事件时间提取规则：

- 优先从 `properties.eventTime`
- 其次 `properties.timestamp`
- 再次 `properties.transactionDate`
- 都没有则用服务端当前时间

## 7. `Statistics`

由两层组成：

- `ExperimentSummary`
- `GroupStatistics`

`GroupStatistics` 关注：

- `userCount`：实际语义是访客数
- `viewCount / clickCount / conversionCount`
- `clickRate / conversionRate / liftRate`
- `trafficRatio`
- `isBaseline`
- `metricValues`：指标中心计算结果

`ExperimentSummary` 新增关注：

- `primaryMetricKey / bestPrimaryMetricValue`
- `breachedGuardrails`

## 8. `ExperimentLayer`

作用：

- 同层互斥：一个访客在同一个 `MUTEX` 层只能进入一个实验
- 跨层正交：不同层之间可以并行参与

策略：

- `MUTEX`
- `ORTHOGONAL`

## 9. 关键数据流

### 9.1 配置流

`ExperimentCreateRequest` -> `ExperimentServiceImpl` -> `ExperimentMetadata` -> `ConfigServiceImpl`

### 9.2 分流流

`visitorId` -> `TrafficServiceImpl` -> `groupId` -> Redis 缓存

### 9.3 事件流

`EventReportRequest` -> `DataServiceImpl` -> Redis Event/Counter/Visitor Set

### 9.4 统计流

`ConfigService.getExperimentConfig` + `DataService` 聚合 -> `Statistics`
