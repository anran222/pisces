# 模块与代码地图

## 1. `pisces-common`

职责：

- 公共模型
- 请求/响应 DTO
- 统一响应码

关键文件：

- `pisces-common/src/main/java/com/pisces/common/model/Experiment.java`
- `pisces-common/src/main/java/com/pisces/common/model/ExperimentMetadata.java`
- `pisces-common/src/main/java/com/pisces/common/model/Event.java`
- `pisces-common/src/main/java/com/pisces/common/model/Statistics.java`
- `pisces-common/src/main/java/com/pisces/common/request/ExperimentCreateRequest.java`
- `pisces-common/src/main/java/com/pisces/common/request/EventReportRequest.java`
- `pisces-common/src/main/java/com/pisces/common/response/BaseResponse.java`

## 2. `pisces-service`

职责：

- 业务主逻辑
- Redis/Zookeeper/通义集成
- 算法与统计计算

### 2.1 入口与配置

- `com.pisces.PiscesApplication`
- `config/RedisConfig.java`
- `config/TongYiConfig.java`
- `config/ApiKeyProperties.java`
- `zookeeper/ZookeeperClient.java`
- `zookeeper/ZookeeperConfig.java`

### 2.2 核心服务

| 服务 | 实现类 | 作用 |
| --- | --- | --- |
| `ExperimentService` | `ExperimentServiceImpl` | 实验增删改查与状态流转 |
| `TrafficService` | `TrafficServiceImpl` | 分流、缓存、Layer 互斥 |
| `DataService` | `DataServiceImpl` | 事件写入、计数、访客去重 |
| `AnalysisService` | `AnalysisServiceImpl` | 统计、报表、AI 分析聚合 |
| `ConfigService` | `ConfigServiceImpl` | 配置持久化与缓存 |
| `IdentityService` | `IdentityServiceImpl` | 匿名 ID 与登录 ID 绑定 |
| `ExperimentDataGeneratorService` | `ExperimentDataGeneratorServiceImpl` | 演示数据生成 |

### 2.3 算法与 AI 子能力

| 服务 | 实现类 | 说明 |
| --- | --- | --- |
| `MultiArmedBanditService` | `MultiArmedBanditServiceImpl` | Thompson Sampling / UCB |
| `BayesianAnalysisService` | `BayesianAnalysisServiceImpl` | 胜率与早停 |
| `CausalInferenceService` | `CausalInferenceServiceImpl` | DID / PSM / Causal Forest |
| `HTEAnalysisService` | `HTEAnalysisServiceImpl` | HTE / ITE / 敏感群体 |
| `VariantGenerationService` | `VariantGenerationServiceImpl` | 文本/图像变体与完整实验演示 |

## 3. `pisces-api`

职责：

- 暴露 REST API
- 鉴权与日志

关键目录：

- `analysis/`
- `data/`
- `experiment/`
- `traffic/`
- `variant/`
- `security/`
- `logging/`

核心横切：

- `security/ApiKeyAuthInterceptor.java`
- `security/WebMvcConfig.java`
- `logging/RequestIdFilter.java`
- `logging/ApiBodyLogFilter.java`
- `logging/ApiLogAspect.java`

## 4. SDK 目录

### 4.1 `pisces-sdk-java`

当前状态：

- 有独立 `pom.xml`
- 有使用说明 `README.md`
- 未纳入父模块聚合构建

### 4.2 `pisces-sdk-js`

当前状态：

- 主要看到 README 使用说明
- 仓库内未看到主工程集成构建配置

## 5. 代码阅读优先级建议

首次理解项目时，推荐阅读顺序：

1. `README.md`
2. `pisces-service/src/main/resources/application.yml`
3. `ExperimentController` / `TrafficController` / `DataController`
4. `ExperimentServiceImpl` / `TrafficServiceImpl` / `DataServiceImpl`
5. `AnalysisServiceImpl`
6. `VariantGenerationServiceImpl`
7. `ConfigServiceImpl` 与 `ZookeeperClient`

