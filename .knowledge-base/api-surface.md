# API 清单

基础前缀来自应用配置：

- Host 由部署决定
- Context Path：`/api`

因此示例完整路径形如：`/api/experiments`

## 1. 实验管理

控制器：`ExperimentController`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/experiments` | 创建实验 |
| `PUT` | `/experiments/{id}` | 更新实验 |
| `GET` | `/experiments/{id}` | 获取实验详情 |
| `GET` | `/experiments` | 查询实验列表，可按状态过滤 |
| `GET` | `/experiments/status/{status}` | 按单状态查询 |
| `POST` | `/experiments/{id}/start` | 启动实验 |
| `POST` | `/experiments/{id}/stop` | 停止实验 |
| `POST` | `/experiments/{id}/pause` | 暂停实验 |
| `POST` | `/experiments/{id}/resume` | 恢复实验 |
| `DELETE` | `/experiments/{id}` | 删除实验 |
| `POST` | `/experiments/batch/pause` | 批量暂停 |
| `POST` | `/experiments/batch/stop` | 批量停止 |
| `POST` | `/experiments/batch/resume` | 批量恢复 |
| `POST` | `/experiments/batch/delete` | 批量删除 |

## 2. 流量分配与 MAB

控制器：`TrafficController`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/traffic/assign` | 分配访客到实验组，支持可选 `attributes` 参与 RULE 分流 |
| `GET` | `/traffic/experiment/{experimentId}/mab/beta` | 查询 Thompson Sampling 参数 |
| `GET` | `/traffic/experiment/{experimentId}/mab/stats` | 查询 UCB 组统计 |
| `GET` | `/traffic/experiment/{experimentId}/mab/probabilities` | 查询当前分配概率 |
| `GET` | `/traffic/experiment/{experimentId}/mab/summary` | 查询 MAB 汇总 |
| `POST` | `/traffic/experiment/{experimentId}/mab/reset` | 重置 MAB 数据 |

## 3. 数据上报

控制器：`DataController`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/data/event` | 上报 VIEW / CLICK / CONVERT 等事件 |
| `POST` | `/data/exposure` | 上报真实 exposure 事件 |

## 4. 分析能力

控制器：`AnalysisController`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/analysis/experiment/{id}/statistics` | 统计总览，包含 `dataQualityCheck`、`totalAssignments`、`totalExposures` |
| `GET` | `/analysis/experiment/{id}/compare` | 组间对比，返回 `dataQualityCheck` |
| `GET` | `/analysis/experiment/{id}/significance` | 传统显著性检验，返回 `dataQualityCheck` 与 `analysisReady` |
| `GET` | `/analysis/sample-size` | 样本量计算 |
| `GET` | `/analysis/experiment/{id}/bayesian` | 贝叶斯分析 |
| `GET` | `/analysis/experiment/{id}/early-stop` | 是否可提前停实验，数据质量门禁未通过时会强制阻断 |
| `POST` | `/analysis/experiment/{id}/causal-inference` | DID / PSM / Causal Forest |
| `POST` | `/analysis/experiment/{id}/hte` | HTE 分析 |
| `POST` | `/analysis/experiment/{id}/sensitive-groups` | 敏感群体识别 |
| `GET` | `/analysis/experiment/{id}/report` | 导出实验报告，包含 `dataSummary`、`recommendations`、`decisionContext` |
| `GET` | `/analysis/experiment/{id}/timeline` | 时间线分析 |
| `GET` | `/analysis/experiment/{id}/ai-insights` | AI 解读 |
| `POST` | `/analysis/experiment/ai-design` | AI 实验设计建议 |
| `GET` | `/analysis/experiment/{id}/auto-graduate` | AI 自动毕业决策，返回 `dataQualityCheck` |
| `GET` | `/analysis/experiment/{id}/predict-completion` | 预测实验完成时间，质量门禁或护栏异常时会返回阻断状态 |
| `GET` | `/analysis/experiment/{id}/srm` | SRM 检测 |
| `GET` | `/analysis/experiment/{id}/sequential` | 序贯检验，数据质量门禁未通过时会回退为继续观测 |

## 5. 变体生成

控制器：`VariantController`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/variants/text/generate` | 文本变体生成 |
| `POST` | `/variants/image/generate` | 文生图 |
| `POST` | `/variants/image/generate-from-image` | 图生图 |
| `POST` | `/variants/image/edit` | 局部编辑 |
| `POST` | `/variants/image/style-transfer` | 图片风格转换 |
| `GET` | `/variants/image/download` | 下载生成图片 |
| `GET` | `/variants/image/styles` | 可用风格列表 |
| `POST` | `/variants/filter` | 二级筛选 |
| `POST` | `/variants/evaluate` | 变体质量评估 |
| `POST` | `/variants/text/demo` | 文本变体完整演示 |
| `POST` | `/variants/experiment/flow` | 从变体生成到实验分析的完整演示 |

## 6. 演示数据生成

控制器：`ExperimentDataGeneratorController`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/experiments/generator/generate` | 自定义生成完整实验数据 |
| `POST` | `/experiments/generator/generate/default` | 默认参数生成 |
| `POST` | `/experiments/generator/generate/quick` | 推荐参数快速生成 |
| `POST` | `/experiments/generator/{experimentId}/simulate` | 为已有实验补充演示数据 |

## 7. 鉴权现状

当前主要业务 Controller 都通过 `@NoTokenRequired` 放行，因此这些接口默认不要求 `X-Pisces-Api-Key`。

但系统已具备鉴权基础设施，后续新增敏感接口时可以直接启用 Header 鉴权。
