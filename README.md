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
- 实验归属于 API Key 绑定的 `appId` 和 `owner`，管理、运行时、分析入口按应用隔离
- 实验列表支持按 `status` / `statuses` / `appId` / `owner` 组合筛选
- 应用空间目录接口 `GET /api/applications` 可按当前 key 归并查看可见应用、负责人、权限域、实验数和配额
- 应用空间注册接口 `PUT /api/applications/{appId}` 可配置展示名、默认负责人、审批人列表、实验配额和配置/启动审批开关；创建实验时会应用默认负责人、校验配额，并按应用策略进入审批状态
- 应用事件/指标字典接口 `GET /api/applications/{appId}/dictionary` 可查看从实验创建和更新中自动沉淀的应用级事件与指标定义
- 启用审批的应用需要先调用 `POST /api/experiments/{id}/approval-status` 将实验审批为 `APPROVED`，之后才能发布配置草稿、启动或恢复；非 `admin` key 必须在应用 `approvalOwners` 列表内才能审批，列表为空时回退 `defaultOwner`，且不能审批自己提交的配置/启动变更
- 审批待办接口 `GET /api/experiments/approval-tasks` 可集中查看当前身份可见的配置/启动待审批实验，并返回当前身份是否可审批及不可审批原因
- 配置版本接口 `GET /api/experiments/{id}/config-versions`、`POST /api/experiments/{id}/config-versions/publish`、`POST /api/experiments/{id}/config-versions/rollback` 支持发布当前快照和按已发布版本回滚
- 配置草稿接口 `GET /api/experiments/{id}/config-draft`、`GET /api/experiments/{id}/config-draft/approvals`、`PUT /api/experiments/{id}/config-draft`、`POST /api/experiments/{id}/config-draft/publish` 支持先保存待发布配置，保存草稿会进入发布审批，审批通过后发布才写入运行配置，并可查询草稿审批历史
- 管理台 `/applications` 可直接维护应用空间展示名、默认负责人、审批人列表、实验配额和配置/启动审批策略
- 实验管理审计日志，接口为 `GET /api/experiments/{id}/audit-logs`
- 实验可绑定 `layerId`；互斥层内同一应用已有运行中实验时，启动或恢复会被拒绝
- 每个实验可选定义 `groupConfigSchema`
- 每个实验组可配置结构化 `config`

### 2. 流量分配

- `HASH`
- `RANDOM`
- `RULE`
- `THOMPSON_SAMPLING`
- `UCB`
- `GET /api/runtime/experiments/{id}/config` 为 SDK 提供 runtime scope 配置拉取，不依赖管理接口
- `GET /api/runtime/experiments/{id}/config/version` 为 SDK 提供轻量版本检查，支持可选 `waitMillis` 长轮询，未变化时可续用本地快照
- `POST /api/traffic/assign/trace` 可返回命中原因、来源、策略和配置版本，便于 SDK 缓存与线上排障
- Redis 缓存不可用时，分流退回当前配置直接计算，并继续保存数据库分流事实

### 3. 数据与分析

- 事件上报与曝光上报，入口写入 MySQL inbox 后由后台消费者异步物化
- 统计总览、组间对比、时间线
- 样本量计算、显著性检验、贝叶斯分析、早停
- SRM、数据质量检查、报告快照
- 事件管道状态、死信重投、重放计划物化覆盖检查、缺账本补物化修复、筛选复制型 replay 和按事实表重建 Redis/MAB 派生数据
- MAB 摘要区分奖励观测和 UCB 选择；主 RATE 指标会把 denominator 事件作为失败观测、numerator 事件作为成功升级

事件管道管理接口：

- `GET /api/analysis/experiment/{id}/event-pipeline`
- `POST /api/analysis/experiment/{id}/event-pipeline/dead/retry`
- `POST /api/analysis/experiment/{id}/event-pipeline/drain`
- `POST /api/analysis/experiment/{id}/events/replay/plan`
- `POST /api/analysis/experiment/{id}/events/replay/materialization/repair`
- `POST /api/analysis/experiment/{id}/events/replay`

### 4. AI 能力

- `POST /api/analysis/experiment/ai-design/v2`
- `GET /api/analysis/experiment/{id}/ai-diagnosis`
- `GET /api/analysis/experiment/{id}/ai-graduation-decision`
- `POST /api/variants/generate`

AI 当前只输出建议，不自动修改实验状态或流量。`ai-design/v2` 对外仍是单接口，但内部已经拆成 `Schema Planning` 和 `Draft Filling` 两阶段，并优先复用传入的 `baselineConfig`。

文本生成默认使用 `TONGYI_MODEL=qwen3.7-max`。截至 2026-07-31，阿里云模型列表中 `qwen3.8-max-preview` 更新，但当前仅 Token Plan 用户可用；本地默认保持 `qwen3.7-max`，所以正常只需要替换 `TONGYI_API_KEY`。如果你的账号已开通 Token Plan，可把 `config/pisces-local.env` 中的 `TONGYI_MODEL` 改为 `qwen3.8-max-preview`。

### 5. 演示与补数

- `POST /api/experiments/generator/demo`
  生成固定演示实验，实验结构使用当前 schema / 指标契约；配置 AI 时使用 AI 毕业决策，AI 不可用时达标演示样例会使用本地确定性演示结论
- `POST /api/experiments/generator/{experimentId}/simulate`
  为已有实验补充真实事件数据

## 快速启动

### 后端

```bash
bash scripts/production-infrastructure-local-prekey-check.sh
# 看到 READY_FOR_API_KEY 后，编辑 config/pisces-local.env 只替换 TONGYI_API_KEY
bash scripts/production-infrastructure-local-finalize.sh
```

`production-infrastructure-local-prekey-check.sh` 是补 key 前的完整流程预演入口。它会确认 `config/pisces-local.env` 已生成且被 Git 忽略，验证 finalizer 在占位符 key 下只输出 `NEEDS_QIANWEN_API_KEY` 且不会启动服务，再用临时非真实 key 执行 finalizer dry-run，证明依赖栈、schema、依赖预检、后端启动、readiness、TongYi AI smoke、前端截图、证据采集、closeout 和最终 completion verify 都已串好。看到 `READY_FOR_API_KEY` 后，只需要替换 `TONGYI_API_KEY` 并运行 `production-infrastructure-local-finalize.sh`。finalizer 默认会在 closeout 后调用 `production-infrastructure-local-completion-verify.sh`；只有该验收输出 `status=COMPLETE` 才能视为本地目标完成。`config/pisces-local.env` 和 `config/pisces-local-stack.env` 不能提交。`TONGYI_API_KEY` 未配置时，普通实验管理、分流和数据上报链路仍可启动；调用 AI 设计、诊断、毕业决策或变体生成接口时会返回明确的服务不可用错误。

API Key 请求头为 `X-Pisces-Api-Key`。`PISCES_API_KEY_SPECS` 格式为 `key|appId|owner|scope1+scope2`，scope 包括 `runtime`、`analysis`、`management`、`admin`。非 `admin` key 创建实验时会强制写入自身 `appId` / `owner`，并只能访问同应用实验；旧的 `PISCES_API_KEYS` 仍兼容，但会赋予全部 scope，仅建议本地迁移期使用。

分步排障时仍可以单独运行 `production-infrastructure-local-dependency-stack.sh`、`production-infrastructure-local-mysql-schema-apply.sh`、`production-infrastructure-local-dependency-check.sh` 和 `production-infrastructure-local-service.sh start`。正常最终验收不需要手工串联这些子步骤。

要用本机作为“生产级实验基础设施”的目标环境完成验收，替换 key 后执行：

```bash
bash scripts/production-infrastructure-local-finalize.sh
```

`production-infrastructure-local-finalize.sh` 是替换千问 key 后的一键最终验收入口：它会先启动本地 MySQL/Redis/Zookeeper 依赖栈、应用本地 MySQL 基础 schema、执行严格依赖预检，再启动本地服务、运行 readiness、调用 `production-infrastructure-local-ai-smoke.sh` 通过 `/variants/generate` 验证 TongYi 文本模型、自动执行前端 high 级依赖审计并生成核心横屏截图和 `layout-audit.json`，随后采集真实本地证据，默认继续执行生成的 local closeout，并在 closeout 后运行 completion verify。使用本项目 Docker 依赖栈时，它会自动识别 `pisces-local-redis-1` 这类本地 Redis 容器并用 `docker-stop` 完成可恢复故障演练；核心前端截图默认写入 `../pisces-web/target/screenshots/core-functions-current` 并进入最终完成审计。外部 Redis 或手工指定容器仍需要显式选择 fault 模式和确认。缺失本地 env、缺失或占位符 `TONGYI_API_KEY` 时，它只写出 `NEEDS_QIANWEN_API_KEY` summary，不会启动服务或生成虚假证据。

`production-infrastructure-local-completion-verify.sh` 是只读完成验收入口，finalizer 会默认调用它，也可以单独复跑。当前仍是占位 key 或 finalizer 未跑完时，它会输出 `NEEDS_API_KEY` / `NEEDS_FINALIZER` 和下一步命令；只有 finalizer `status=PASS`、所有关键步骤 `PASS`、TongYi AI smoke `status=PASS`、collection `status=PASS`、closeout 报告 `Verdict: **COMPLETE**`、completion audit `completionStatus=COMPLETE`、`layout-audit.json` 为 `status=PASS failedCount=0 enforcedCount>=8`，且 release evidence manifest 为 `environment=local` 时，它才输出 `status=COMPLETE`。

如果需要分步排查，可以单独运行证据采集：

```bash
PISCES_RELEASE_ID="local-$(date +%Y%m%d)-runtime-plane" \
bash scripts/production-infrastructure-local-evidence-collect.sh
```

采集器会先读取 `target/pisces-production-infrastructure-local-service/summary.json`，要求本地服务由 `production-infrastructure-local-service.sh start` 启动且 `status=HEALTHY`、`apiKeyStatus=configured`、`healthStatus=UP`。未传 `PISCES_EXPERIMENT_ID` 时会自动创建本地演示实验，然后生成本地 release / capacity / Redis fault / event replay / impact / acceptance 证据，并在 closeout 前做结构校验。分步运行 collector 时 Redis fault 默认是 `manual`；要复用本地 Docker 栈的自动演练，可传 `PISCES_LOCAL_COLLECT_REDIS_FAULT_MODE=docker-stop PISCES_REDIS_DOCKER_CONTAINER=pisces-local-redis-1 PISCES_FAULT_CONFIRM=true`。完整门禁见 `docs/operations/production-infrastructure-completion-audit.md`。

采集成功后，summary 会写出 `run-local-closeout.sh` 路径。worktree 已清理且 `TONGYI_API_KEY` 已设置时，也可以在采集命令上加：

```bash
PISCES_LOCAL_COLLECT_RUN_CLOSEOUT=true
```

采集前可以快速预检本机状态：

```bash
bash scripts/production-infrastructure-local-readiness.sh
```

### 前端

```bash
cd ../pisces-web
npm install
export VITE_PISCES_API_KEY="ops-key"
npm run dev
```

核心功能截图用于最终完成审计。前端 dev server 启动后，可在另一个终端执行：

```bash
cd ../pisces-web
PISCES_WEB_BASE_URL="http://127.0.0.1:3040" npm run capture:core
```

截图采集会同时写出 `target/screenshots/core-functions-current/layout-audit.json`。该审计会对核心桌面工作区和弹层检查横屏视口、横向溢出和 body 级纵向滚动，防止页面重新退化成需要大量下拉的平铺布局；最终 completion audit 和 completion verify 都会读取它，只有 `status=PASS`、`failedCount=0` 且至少 8 个核心横屏/弹层场景被强制审计时才允许完成。

## 文档入口

- [知识库总览](.knowledge-base/README.md)
- [架构说明](.knowledge-base/architecture.md)
- [API 清单](.knowledge-base/api-surface.md)
- [领域模型](.knowledge-base/domain-model.md)
- [模块地图](.knowledge-base/module-map.md)
- [实现边界](.knowledge-base/implementation-status.md)
- [生产级路线图](.knowledge-base/production-infrastructure-roadmap.md)
- [真实业务接入指南](.knowledge-base/real-integration-guide.md)
- [运行时发布演练](docs/operations/runtime-plane-release-drill.md)
- [运行时发布检查清单](docs/operations/runtime-plane-release-checklist.md)
- [运行时发布包检查](docs/operations/runtime-plane-release-package-check.md)
- [运行时预发演练记录模板](docs/operations/runtime-plane-preprod-drill-record-template.md)
- [运行时发布证据归档](docs/operations/runtime-plane-release-evidence-archive.md)
- [运行时发布后 SLO 回看](docs/operations/runtime-plane-post-release-slo-review.md)
- [运行时实验影响面抽样](docs/operations/runtime-plane-experiment-impact-sampling.md)
- [运行时分批发布决策](docs/operations/runtime-plane-staged-rollout-decision.md)
- [运行时回滚决策演练模板](docs/operations/runtime-plane-rollback-decision-drill-template.md)
- [运行时发布后异常复盘模板](docs/operations/runtime-plane-post-release-incident-review-template.md)
- [事件管道重放审计](docs/operations/event-pipeline-replay-audit.md)
- [运行时容量基线](docs/operations/runtime-plane-capacity-baseline.md)
- [运行时基线归档](docs/operations/runtime-plane-baseline-archive.md)
- [Redis 故障注入演练](docs/operations/runtime-plane-redis-fault-injection.md)
- [可观测性资产](docs/observability/README.md)
- [Java SDK](pisces-sdk-java/README.md)
- [JS SDK](pisces-sdk-js/README.md)

## 当前文档原则

仓库里的 Markdown 已按当前实现重写，旧计划、旧指南、旧测试报告和历史整理文档不再保留。

已有数据库如果已经创建过 `pisces_application_space` 表，需要执行 `pisces-service/src/main/resources/sql/mysql/pisces_application_space_approval_required_migration.sql` 补充配置/启动审批列和审批人列表列。应用级事件/指标字典需要执行 `pisces-service/src/main/resources/sql/mysql/pisces_application_dictionary.sql` 创建字典表。配置草稿审批记录需要执行 `pisces-service/src/main/resources/sql/mysql/pisces_experiment_config_draft_approval.sql` 创建草稿审批表。事件管道重放和派生审计需要执行 `pisces-service/src/main/resources/sql/mysql/pisces_event_replay_job.sql` 与 `pisces-service/src/main/resources/sql/mysql/pisces_event_materialization.sql`。
