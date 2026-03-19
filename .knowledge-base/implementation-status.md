# 实现状态与限制

## 1. 已实现且可作为主链路使用

### 1.1 实验管理

- 创建、更新、获取、列表查询
- 启动、暂停、恢复、停止、删除
- 批量状态操作

### 1.2 分流

- 基于实验状态、时间窗、黑白名单校验
- Hash / Random 分流
- RULE 分流，支持按 `attributes` 进行确定性规则命中，再按配置回退
- Thompson Sampling / UCB
- `configVersion` 驱动缓存失效
- Layer 互斥

### 1.3 事件与统计

- 事件上报
- `assignment / exposure / event` 三类事实已落数据库
- 访客去重统计
- 基础统计、组间对比、显著性检验
- 样本量计算
- 时间线聚合
- 报告导出
- SRM 检测
- 序贯检验
- 统计结果内置 `dataQualityCheck`，会返回 SRM、建议样本量、assignment / exposure 完整性提示
- 显著性检验、早停、序贯检验、自动毕业都会读取 `dataQualityCheck`，门禁不通过时阻断决策
- 主指标与护栏指标已接入统计摘要和自动毕业逻辑，护栏指标下降时会阻断毕业
- 显著性检验与序贯检验已支持比例型主指标；非比例型主指标会回退到 `conversion_rate`
- 基准组选择已统一收口为 `traffic.allocation` 的首个实验组；若缺失 allocation，则按实验组 ID 排序兜底，避免 `HashMap` 顺序导致结论漂移
- 贝叶斯分析已按主指标口径取分子/分母，不再固定写死 `VIEW / CONVERT`
- `DataServiceImpl` 的统计读取已优先走数据库事实表，不再依赖 Redis 原始事实作为正式口径

### 1.4 AI 相关

- 通义文本生成
- 通义图像生成 / 图生图 / 局部编辑 / 风格化
- AI 实验解读
- AI 实验设计
- 自动毕业决策
- 预测实验完成时间

前提：

- `tongyi.enabled = true`
- `tongyi.apiKey` 已配置

## 2. 有实现，但需谨慎理解

### 2.1 Zookeeper 降级

当 Zookeeper 不可用时：

- 实验仍可创建
- 但分层配置无法正常同步与监听
- 不再允许通过内存持久化兜底核心实验配置

这更像“开发态容错”，不是可靠持久化方案。

当前已补充一层能力：

- 实验配置仓库固定持久化到数据库
- 若 `pisces_experiment_config` 表或数据源不可用，服务会直接启动失败，不允许降级到内存
- 实验报告快照已落独立 MySQL 表 `pisces_experiment_report_snapshot`
- `assignment / exposure / event` 事实已新增独立数据库表
- 持久化链路统一改为 `repository + entity + mapper interface + mapper.xml`
- Redis 当前主要承担缓存、在线投影和 MAB/身份绑定职责

### 2.2 DID

`CausalInferenceServiceImpl` 中 DID 已有公式和标准误计算，但 `calculateConversionRate(...)` 仍有 `TODO`，当前按整体转化率近似，不是严格按时间窗转化率计算。

### 2.3 PSM

PSM 不是接数据库或画像表做标准特征建模，而是基于事件构造简化访客特征：

- `viewCount`
- 首次出现顺序 `rank`

适合演示或轻量验证，不宜等同于成熟生产级因果推断。

### 2.4 SDK 文档与服务端口

SDK README 示例仍大量使用：

- `http://localhost:8080/api`

但当前服务配置实际是：

- `http://localhost:9990/api`

使用 SDK 文档时要手动修正。

## 3. 明确未完成或禁止伪造结果

以下能力当前不是“弱实现”，而是直接拒绝返回模拟结果或结构化阻断：

- `HTEAnalysisServiceImpl.analyzeHTE`
- `HTEAnalysisServiceImpl.getIndividualTreatmentEffect`
- `HTEAnalysisServiceImpl.identifySensitiveGroups`
- `CausalInferenceServiceImpl.analyzeByCausalForest`

其中 HTE / Causal Forest 仍会抛 `SERVICE_UNAVAILABLE`；DID / PSM 在统计门禁未通过时会返回 `BLOCKED`。

## 4. 当前设计上的重要约束

### 4.1 Redis 仍是运行时依赖，但不再是正式事实源

虽然 README 强调“可无依赖运行”，但从代码看：

- 分流依赖 Redis
- 在线事件投影依赖 Redis
- MAB 依赖 Redis
- 身份绑定依赖 Redis

但 `assignment / exposure / event` 的正式事实读取已切到数据库。

### 4.2 `RULE` 策略是最小实现

当前 RULE 分流已经落地，但仍属于最小规则引擎：

- 规则条件支持 `EQ / IN / CONTAINS / EXISTS`
- `/traffic/assign` 需要通过 `attributes` 传入访客属性
- 规则命中失败时按 `ruleFallbackStrategy` 回退，默认 `HASH`
- 尚不支持复杂布尔表达式、数值比较、正则和跨字段计算

### 4.3 访客字段兼容债务

虽然业务语义已切换到 `visitorId`，但内部字段仍保留 `userId` 命名：

- `Event.userId`
- `Statistics.GroupStatistics.userCount`

这是兼容旧接口而保留的历史命名。

### 4.4 报告快照能力已接入

当前已补齐：

- 实验结论状态字段 `conclusionStatus`
- 手动更新结论状态接口
- 实时报表归档为不可变快照
- 快照历史查询

但仍有边界：

- 目前没有单独的快照审批/审核流程
- 快照生成依赖实时分析结果，因此数据库事实层的数据质量会直接传导到快照内容

## 5. 维护建议

- 若要长期可用，优先补齐事实表初始化、索引治理和历史数据迁移方案
- 若要将因果能力用于真实业务，优先重做 DID 时间窗逻辑并补齐 Causal Forest / HTE
- 若要对外发布 SDK，先统一端口与鉴权文档
- 若要继续增强规则分流，下一步应补数值比较、布尔组合、规则命中审计日志
