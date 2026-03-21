# Pisces

Pisces 是一个面向业务接入的实验系统，当前代码已经收口为 4 条主线能力：

- 实验管理与流量分配
- 事件采集与统计分析
- 结构化 AI 设计 / 诊断 / 毕业决策
- 候选变体生成与演示实验

当前仓库是多模块 Maven 工程，默认服务入口为 `http://localhost:9990/api`。

## 模块

| 模块 | 说明 |
| --- | --- |
| `pisces-common` | 公共模型、请求体、响应体、错误码 |
| `pisces-service` | 核心业务逻辑、统计分析、AI、Redis/Zookeeper/MyBatis |
| `pisces-api` | REST API |
| `pisces-sdk-java` | 面向后端接入的运行时 SDK |
| `pisces-sdk-js` | 面向前端接入的运行时 SDK |

前端管理台位于同级目录 `../pisces-web`。

## 当前能力

### 1. 实验管理

- 创建、更新、启动、暂停、恢复、停止、删除实验
- 批量状态操作
- 实验结论状态流转
- 每个实验可选定义 `groupConfigSchema`
- 每个实验组可配置结构化 `config`

### 2. 流量分配

- `HASH`
- `RANDOM`
- `RULE`
- `THOMPSON_SAMPLING`
- `UCB`

### 3. 数据与分析

- 事件上报与曝光上报
- 统计总览、组间对比、时间线
- 样本量计算、显著性检验、贝叶斯分析、早停
- SRM、数据质量检查、报告快照

### 4. AI 能力

- `POST /api/analysis/experiment/ai-design/v2`
- `GET /api/analysis/experiment/{id}/ai-diagnosis`
- `GET /api/analysis/experiment/{id}/ai-graduation-decision`
- `POST /api/variants/generate`

AI 当前只输出建议，不自动修改实验状态或流量。

### 5. 演示与补数

- `POST /api/experiments/generator/demo`
  生成固定演示实验，允许使用演示数据
- `POST /api/experiments/generator/{experimentId}/simulate`
  为已有实验补充真实事件数据

## 快速启动

### 后端

```bash
mvn -DskipTests compile
mvn -pl pisces-api spring-boot:run
```

### 前端

```bash
cd ../pisces-web
npm install
npm run dev
```

## 文档入口

- [知识库总览](.knowledge-base/README.md)
- [架构说明](.knowledge-base/architecture.md)
- [API 清单](.knowledge-base/api-surface.md)
- [领域模型](.knowledge-base/domain-model.md)
- [模块地图](.knowledge-base/module-map.md)
- [实现边界](.knowledge-base/implementation-status.md)
- [Java SDK](pisces-sdk-java/README.md)
- [JS SDK](pisces-sdk-js/README.md)

## 当前文档原则

仓库里的 Markdown 已按当前实现重写，旧计划、旧指南、旧测试报告和历史整理文档不再保留。
