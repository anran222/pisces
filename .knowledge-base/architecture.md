# 架构与运行

## 1. 工程结构

父工程 `pom.xml` 只聚合了 3 个核心模块：

- `pisces-common`
- `pisces-service`
- `pisces-api`

`pisces-sdk-java` 和 `pisces-sdk-js` 在仓库中存在，但不在父 POM `modules` 内，当前更像附属 SDK 目录。

## 2. 模块依赖

```mermaid
flowchart TD
    common[pisces-common]
    service[pisces-service]
    api[pisces-api]

    service --> common
    api --> common
    api --> service
```

## 3. 配置与基础设施

配置文件：`pisces-service/src/main/resources/application.yml`

默认配置：

- `server.port = 9990`
- `server.servlet.context-path = /api`
- Redis：`localhost:6379`
- Zookeeper：`localhost:2181`
- 通义模型默认：`qwen-plus`
- 安全 Header：`X-Pisces-Api-Key`

## 4. 存储职责拆分

### 4.1 Zookeeper / 配置管理

由 `ConfigServiceImpl` 管理：

- 实验元数据 `ExperimentMetadata`
- 分层配置 `ExperimentLayer`
- 配置监听与缓存失效

仓库规则：

- 实验配置仓库固定通过数据库持久化，不再提供内存实现
- 如果数据源或 `pisces_experiment_config` 表不可用，服务会直接启动失败
- 当前已提供建表 SQL：`pisces-service/src/main/resources/sql/mysql/pisces_experiment_config.sql`
- 数据库链路当前已统一改为 `repository -> mapper interface -> mapper.xml`，SQL 不再内嵌在 Java 代码中

### 4.2 MySQL

当前 MySQL 主要承担五类持久化职责：

- `pisces_experiment_config`：实验配置持久化
- `pisces_experiment_report_snapshot`：实验报告快照归档
- `pisces_experiment_assignment`：分流事实
- `pisces_experiment_exposure`：曝光事实
- `pisces_experiment_event`：事件事实

代码结构已收口为：

- `repository`：领域语义仓库，对上层 Service 暴露 `save/find/list` 等业务能力
- `entity`：数据库行模型
- `mapper interface`：MyBatis 映射接口
- `mapper.xml`：唯一 SQL 定义位置

当前对应实现：

- `ExperimentConfigRepository` -> `DatabaseExperimentConfigRepository` -> `ExperimentConfigMapper` -> `ExperimentConfigMapper.xml`
- `ExperimentReportSnapshotRepository` -> `DatabaseExperimentReportSnapshotRepository` -> `ExperimentReportSnapshotMapper` -> `ExperimentReportSnapshotMapper.xml`
- `ExperimentAssignmentRepository` -> `ExperimentAssignmentMapper` -> `ExperimentAssignmentMapper.xml`
- `ExperimentExposureRepository` -> `ExperimentExposureMapper` -> `ExperimentExposureMapper.xml`
- `ExperimentEventRepository` -> `ExperimentEventMapper` -> `ExperimentEventMapper.xml`

### 4.3 Redis

由 `TrafficServiceImpl`、`DataServiceImpl`、`MultiArmedBanditServiceImpl`、`IdentityServiceImpl` 共同使用。

当前职责已收口为：

- 分组缓存
- 在线计数与热点查询投影
- Layer 互斥标记
- MAB 状态
- 身份绑定

主要 Key 约定：

- `pisces:traffic:group:{visitorId}`：访客分组缓存
- `pisces:assignment:{experimentId}:{visitorId}`：分流缓存投影
- `pisces:assignment:set:{experimentId}:{groupId}`：实验组 assignment 投影集合
- `pisces:layer:assign:{layerId}:{visitorId}`：Layer 互斥标记
- `pisces:event:store:{experimentId}:{groupId}`：事件投影列表
- `pisces:event:counter:{experimentId}:{groupId}`：事件计数投影
- `pisces:visitor:set:{experimentId}:{groupId}`：访客去重投影集合
- `pisces:exposure:{experimentId}:{visitorId}`：曝光缓存投影
- `pisces:exposure:set:{experimentId}:{groupId}`：实验组 exposure 投影集合
- `pisces:mab:beta:{experimentId}`：Thompson Sampling 参数
- `pisces:mab:ucb:{experimentId}`：UCB 统计
- `pisces:mab:trials:{experimentId}`：UCB 总尝试次数
- `pisces:identity:bind:{deviceId}`：deviceId -> userId

## 5. 请求处理横切逻辑

### 5.1 鉴权

`ApiKeyAuthInterceptor` 规则：

- 命中 `pisces.security.skip-paths` 放行
- 类或方法带 `@NoTokenRequired` 放行
- 其他接口要求请求头 `X-Pisces-Api-Key`

当前业务 Controller 基本都打了 `@NoTokenRequired`，因此主链路接口默认无需鉴权。

### 5.2 日志

日志链路由三部分组成：

- `RequestIdFilter`：注入 `X-Request-Id`
- `ApiBodyLogFilter`：打印请求/响应体，自动脱敏与截断
- `ApiLogAspect`：记录控制器级请求、响应摘要与异常

## 6. 关键运行流程

### 6.1 创建实验

```mermaid
sequenceDiagram
    participant C as Client
    participant A as ExperimentController
    participant S as ExperimentServiceImpl
    participant CFG as ConfigServiceImpl
    participant Z as Zookeeper/Cache

    C->>A: POST /experiments
    A->>S: createExperiment(request)
    S->>CFG: saveExperimentConfig(experimentId, metadata)
    CFG->>Z: 持久化或缓存
    CFG-->>S: ok
    S-->>A: Experiment
    A-->>C: BaseResponse
```

### 6.2 分流与事件

```mermaid
sequenceDiagram
    participant C as Client
    participant T as TrafficController
    participant TS as TrafficServiceImpl
    participant D as DataController
    participant DS as DataServiceImpl
    participant DB as MySQL
    participant R as Redis

    C->>T: POST /traffic/assign
    T->>TS: assignGroup(experimentId, visitorId, attributes)
    TS->>DB: 写 assignment 事实
    TS->>R: 刷新分组缓存投影
    TS-->>C: groupId

    C->>D: POST /data/exposure
    D->>DS: reportExposure(...)
    DS->>DB: 写 exposure 事实
    DS->>R: 刷新 exposure 投影

    C->>D: POST /data/event
    D->>DS: reportEvent(...)
    DS->>TS: getUserGroup(...)
    DS->>DB: 写 event 事实
    DS->>R: 刷新事件/计数/访客集合投影
```

## 7. 运行依赖的真实要求

需要明确区分“可选”与“实际不可少”：

- Zookeeper：可选，但配置监听和分层配置仍依赖它
- Redis：业务运行仍强依赖；分流缓存、在线投影、MAB、身份绑定都依赖 Redis
- 通义 API：仅 AI 相关能力依赖，不影响基础实验管理

## 8. 代码级运行风险

- 实验配置当前不再以内存方式持久保留，数据库是唯一持久化来源
- `assignment / exposure / event` 当前已切换为数据库正式事实源
- `RULE` 分流策略已是最小可用规则引擎，但暂不支持复杂布尔表达式和数值比较
- 统计、对比、贝叶斯分析、报告、预测完成时间等分析出口，当前都统一以 `traffic.allocation` 首组作为基准组来源
- 贝叶斯分析的胜率计算当前已跟随主指标定义的分子/分母口径，若主指标是曝光分母，则会直接使用 exposure 数据而不是 `VIEW`
- 部分 README/SDK 文档仍使用 `http://localhost:8080/api`，与当前 `application.yml` 的 `9990` 不一致
