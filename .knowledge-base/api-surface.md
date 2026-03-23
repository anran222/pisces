# API 清单

基础前缀：`/api`

## 实验管理

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/experiments` | 创建实验 |
| `PUT` | `/experiments/{id}` | 更新实验 |
| `GET` | `/experiments/{id}` | 获取实验详情 |
| `GET` | `/experiments` | 查询实验列表 |
| `GET` | `/experiments/status/{status}` | 按状态查询 |
| `POST` | `/experiments/{id}/start` | 启动实验 |
| `POST` | `/experiments/{id}/stop` | 停止实验 |
| `POST` | `/experiments/{id}/pause` | 暂停实验 |
| `POST` | `/experiments/{id}/resume` | 恢复实验 |
| `POST` | `/experiments/{id}/conclusion-status` | 更新人工结论状态 |
| `DELETE` | `/experiments/{id}` | 删除实验 |
| `POST` | `/experiments/batch/pause` | 批量暂停 |
| `POST` | `/experiments/batch/stop` | 批量停止 |
| `POST` | `/experiments/batch/resume` | 批量恢复 |
| `POST` | `/experiments/batch/delete` | 批量删除 |

## 流量分配

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/traffic/assign` | 分配访客到实验组 |
| `GET` | `/traffic/experiment/{experimentId}/mab/beta` | 查询 Thompson 参数 |
| `GET` | `/traffic/experiment/{experimentId}/mab/stats` | 查询组统计 |
| `GET` | `/traffic/experiment/{experimentId}/mab/probabilities` | 查询分配概率 |
| `GET` | `/traffic/experiment/{experimentId}/mab/summary` | 查询 MAB 摘要 |
| `POST` | `/traffic/experiment/{experimentId}/mab/reset` | 重置 MAB 状态 |

## 数据上报

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/data/event` | 上报实验定义事件；兼容 `VIEW` / `CLICK` / `CONVERT` 快捷事件 |
| `POST` | `/data/exposure` | 上报曝光 |

## 分析

### 当前主入口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/analysis/experiment/{id}/statistics` | 统计总览 |
| `GET` | `/analysis/experiment/{id}/compare` | 组间对比 |
| `GET` | `/analysis/sample-size` | 样本量计算 |
| `GET` | `/analysis/experiment/{id}/bayesian` | 贝叶斯分析 |
| `GET` | `/analysis/experiment/{id}/early-stop` | 早停判断 |
| `GET` | `/analysis/experiment/{id}/report` | 导出报告 |
| `POST` | `/analysis/experiment/{id}/report/snapshots` | 生成报告快照 |
| `GET` | `/analysis/experiment/{id}/report/snapshots` | 查询报告快照 |
| `GET` | `/analysis/experiment/{id}/timeline` | 时间线 |
| `GET` | `/analysis/experiment/{id}/ai-diagnosis` | 结构化 AI 诊断 |
| `GET` | `/analysis/experiment/{id}/ai-graduation-decision` | 结构化 AI 毕业决策 |
| `POST` | `/analysis/experiment/ai-design/v2` | 结构化 AI 实验设计；单接口，内部执行 Schema Planning + Draft Filling，两阶段返回 `schemaPlanning` / `draftGeneration` |

### 仍保留的兼容接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/analysis/experiment/{id}/significance` | 显著性检验 |
| `POST` | `/analysis/experiment/{id}/causal-inference` | 因果推断（仅 `DID` / `PSM`） |
| `GET` | `/analysis/experiment/{id}/ai-insights` | 旧 AI 解读接口 |
| `POST` | `/analysis/experiment/ai-design` | 旧 AI 设计接口 |
| `GET` | `/analysis/experiment/{id}/auto-graduate` | 旧自动毕业接口 |
| `GET` | `/analysis/experiment/{id}/predict-completion` | 完成时间预测 |
| `GET` | `/analysis/experiment/{id}/srm` | SRM 检测 |
| `GET` | `/analysis/experiment/{id}/sequential` | 序贯检验 |

## 变体生成

### 当前推荐入口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/variants/generate` | 统一生成候选变体，支持 `TEXT` / `IMAGE` |

### 兼容入口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/variants/text/generate` | 文本候选生成 |
| `POST` | `/variants/image/generate` | 图片候选生成 |

## 演示与补数

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/experiments/generator/demo` | 生成固定演示实验，返回 AI 毕业决策结果和示例实验结构摘要 |
| `POST` | `/experiments/generator/{experimentId}/simulate` | 为已有实验补充真实事件数据，事件类型会遵循实验自己的 `eventDefinitions` / `metricDefinitions` |
