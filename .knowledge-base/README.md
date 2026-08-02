# Pisces 知识库

这套知识库只描述当前代码已经具备的能力，不保留历史方案。

## 系统定位

Pisces 是一个实验平台后端，提供：

- 实验配置与生命周期管理
- 应用空间治理、应用事件/指标字典、实验审批门禁与审计
- 管理操作审计日志
- 访客分流与 MAB 分配
- 事件采集、统计分析与报告
- 结构化 AI 决策
- 候选变体生成
- 演示实验与真实补数

## 代码入口

- 启动类：`pisces-api/src/main/java/com/pisces/PiscesApplication.java`
- 默认端口：`9990`
- Context Path：`/api`

## 当前文档

- [架构说明](architecture.md)
- [API 清单](api-surface.md)
- [领域模型](domain-model.md)
- [模块地图](module-map.md)
- [实现边界](implementation-status.md)
- [生产级实验基础设施演进路线](production-infrastructure-roadmap.md)
- [真实业务接入指南](real-integration-guide.md)
- [异步事件管道设计](async-event-pipeline-design.md)

## 当前运维资产

- [可观测性资产](../docs/observability/README.md)
- [运行时平面多实例发布演练](../docs/operations/runtime-plane-release-drill.md)
- [运行时平面发布检查清单](../docs/operations/runtime-plane-release-checklist.md)
- [运行时平面发布包检查](../docs/operations/runtime-plane-release-package-check.md)
- [运行时平面预发演练记录模板](../docs/operations/runtime-plane-preprod-drill-record-template.md)
- [运行时平面发布证据归档](../docs/operations/runtime-plane-release-evidence-archive.md)
- [运行时平面发布后 SLO 回看](../docs/operations/runtime-plane-post-release-slo-review.md)
- [运行时平面实验影响面抽样](../docs/operations/runtime-plane-experiment-impact-sampling.md)
- [运行时平面分批发布决策](../docs/operations/runtime-plane-staged-rollout-decision.md)
- [运行时平面回滚决策演练模板](../docs/operations/runtime-plane-rollback-decision-drill-template.md)
- [运行时平面发布后异常复盘模板](../docs/operations/runtime-plane-post-release-incident-review-template.md)
- [事件管道重放审计](../docs/operations/event-pipeline-replay-audit.md)
- [运行时配置契约矩阵](../docs/operations/runtime-config-contract-matrix.md)
- [运行时平面容量基线](../docs/operations/runtime-plane-capacity-baseline.md)
- [运行时平面基线归档规范](../docs/operations/runtime-plane-baseline-archive.md)
- [Redis 故障注入演练](../docs/operations/runtime-plane-redis-fault-injection.md)

## 当前前端

同级目录 `../pisces-web` 是当前管理台，主路由为：

- `/ai-center`
- `/ai-design`
  - 支持填写应用 ID，并从应用字典导入事件和指标定义
- `/experiments`
- `/experiments/:id`
  - 详情页展示数据链路状态、配置版本发布/回滚和管理审计日志
- `/applications`
  - 应用空间、默认负责人、实验配额、配置/启动审批待办、审批策略和应用事件/指标字典
- `/experiments/:id/decision`
- `/variants-lab`
