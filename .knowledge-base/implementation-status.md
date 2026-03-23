# 实现边界

## 当前可用

- 实验增删改查和状态流转
- 多策略分流
- 事件采集、曝光采集、统计聚合
- MAB、贝叶斯分析、SRM、报告快照
- 结构化 AI 设计 / 诊断 / 毕业接口
- 统一候选变体生成
- 演示实验生成
- 为已有实验补充真实事件数据
- Java / JS 运行时 SDK

## 当前重要约束

### AI

- AI 只输出建议，不自动执行
- `ai-design/v2` 已升级为单接口、内部分两阶段：先做 Schema Planning，再做 Draft Filling
- 设计链优先复用 `baselineConfig`，并要求对照组、实验组都返回完整配置值
- `diagnosis` 动作统一是 `MANUAL_ONLY`
- `graduation` 会被数据质量门禁修正

### 实验配置

- `groupConfigSchema` 是实验级可选字段定义
- `eventDefinitions` 和 `metricDefinitions` 是实验级必填定义
- 事件 key 和指标 key 都要求使用大写英文下划线格式
- 创建和更新实验时，会校验指标引用的事件是否已定义
- 定义 schema 后，实验组配置会按类型校验和归一化

### 数据

- 示例实验允许固定演示数据，但最终是否可毕业依赖 AI 毕业决策，不再使用本地规则直接宣布毕业
- 其余实验创建、统计和补数都应使用真实数据链路
- 已有实验补数时，会按实验自己的事件定义生成事件，而不是回退到固定 `VIEW` / `CLICK` / `CONVERT`

### 兼容接口

系统仍保留部分旧接口，但当前推荐入口已经切到：

- `ai-design/v2`
- `ai-diagnosis`
- `ai-graduation-decision`
- `/variants/generate`

## 当前文档策略

只保留和当前代码一致的 Markdown，不再维护历史计划、旧版说明和测试记录。
