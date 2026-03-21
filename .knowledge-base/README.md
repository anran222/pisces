# Pisces 知识库

这套知识库只描述当前代码已经具备的能力，不保留历史方案。

## 系统定位

Pisces 是一个实验平台后端，提供：

- 实验配置与生命周期管理
- 访客分流与 MAB 分配
- 事件采集、统计分析与报告
- 结构化 AI 决策
- 候选变体生成
- 演示实验与真实补数

## 代码入口

- 启动类：`pisces-service/src/main/java/com/pisces/PiscesApplication.java`
- 默认端口：`9990`
- Context Path：`/api`

## 当前文档

- [架构说明](architecture.md)
- [API 清单](api-surface.md)
- [领域模型](domain-model.md)
- [模块地图](module-map.md)
- [实现边界](implementation-status.md)

## 当前前端

同级目录 `../pisces-web` 是当前管理台，主路由为：

- `/ai-center`
- `/ai-design`
- `/experiments`
- `/experiments/:id`
- `/experiments/:id/decision`
- `/variants-lab`
