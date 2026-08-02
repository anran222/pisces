# Production Infrastructure Completion Audit

本文档定义“生产级实验基础设施”完成度审计。它不是发布包检查的替代品，而是最高层验收：把 Control Plane、Data Plane、Event Plane、Decision Plane 和 Operations 的静态能力证据，与真实环境发布证据合并为一个 `completionStatus`。

只有 `completionStatus=COMPLETE` 才能认为目标完成。仓库静态能力通过但缺少真实环境证据时，输出会是 `INCOMPLETE`。

## 执行

先运行静态审计：

```bash
bash scripts/production-infrastructure-completion-audit.sh
```

默认输出：

```text
target/pisces-production-infrastructure-completion-audit/summary.json
```

静态审计会检查四个实验基础设施平面和发布运维门禁是否在当前仓库中有实现、文档和测试证据。它会因为缺少真实环境证据输出 `status=HOLD`、`completionStatus=INCOMPLETE`，这是预期结果。

真实验收时应传入发布证据：

```bash
PISCES_COMPLETION_REQUIRE_REAL_ENV_EVIDENCE=true \
PISCES_COMPLETION_TARGET_ENVIRONMENT="prod" \
PISCES_COMPLETION_RELEASE_PACKAGE_REPORT_FILE="target/pisces-runtime-release-package-check/report.json" \
PISCES_COMPLETION_PREPROD_RECORD_CHECK_SUMMARY_FILE="target/pisces-runtime-preprod-drill-record-check/summary.json" \
PISCES_COMPLETION_RELEASE_EVIDENCE_MANIFEST_FILE="target/pisces-runtime-release-evidence-archive/<release>/manifest.json" \
PISCES_COMPLETION_PRODUCTION_ACCEPTANCE_SUMMARY_FILE="target/pisces-runtime-production-acceptance/summary.json" \
PISCES_COMPLETION_SCREENSHOT_DIR="../pisces-web/target/screenshots/core-functions-current" \
bash scripts/production-infrastructure-completion-audit.sh
```

如果本机就是目标运行环境，使用：

```bash
PISCES_COMPLETION_TARGET_ENVIRONMENT="local" \
...
```

此时 release evidence manifest、capacity baseline 和 production acceptance 中的 `environment` 都必须是 `local`。千问 API key 不写入仓库，只通过本地运行配置或环境变量注入。

本地 readiness 可以先单独检查：

```bash
bash scripts/production-infrastructure-local-readiness.sh
```

默认输出：

```text
target/pisces-production-infrastructure-local-readiness/summary.json
```

该检查会明确列出本机缺失的工具、dirty worktree、千问 API key 环境变量、本地服务 health、核心截图目录、collector plan-only 预检结果以及 completion audit 仍阻塞的真实证据项。
它也会执行 secret scan 和本地依赖预检，确认仓库中没有疑似真实 `TONGYI_API_KEY`、DashScope key 或 Aliyun AccessKey，并明确 MySQL 连接/schema、Redis ping 和 Zookeeper 等价性状态。

本地最终验收可以使用一键驱动：

```bash
bash scripts/production-infrastructure-local-prekey-check.sh
# 看到 READY_FOR_API_KEY 后，编辑 config/pisces-local.env 只替换 TONGYI_API_KEY
bash scripts/production-infrastructure-local-finalize.sh
```

`production-infrastructure-local-prekey-check.sh` 是补 key 前的完整流程预演入口。它会确认本地 env 已生成且被 Git 忽略，验证 finalizer 在缺失或占位符 key 下只输出 `NEEDS_QIANWEN_API_KEY` 且不会启动服务，再用临时非真实 key 执行 finalizer dry-run，确认依赖栈、schema、依赖预检、后端启动、readiness、TongYi AI smoke、前端截图、证据采集、Redis fault 模式、closeout 和 completion verify 都已纳入同一条 finalizer 计划。summary 不包含真实 key 或临时 key。看到 `READY_FOR_API_KEY` 后，再替换 `TONGYI_API_KEY` 并运行 finalizer。

`production-infrastructure-local-finalize.sh` 会在 `config/pisces-local.env` 缺失时先自动调用本地 bootstrap 生成模板，写出 `NEEDS_QIANWEN_API_KEY` summary，并停止在“只替换 `TONGYI_API_KEY` 后重跑 finalizer”这一步。key 配好后，它会启动本地 MySQL/Redis/Zookeeper 依赖栈、应用本地 MySQL 基础 schema、执行严格依赖预检，再启动本地服务、运行 readiness、通过 `POST /api/variants/generate` 执行 TongYi 文本模型 smoke、自动执行前端 high 级依赖审计并生成核心横屏截图，随后采集真实本地证据，默认继续执行生成的 `run-local-closeout.sh`，并在 closeout 后执行 `production-infrastructure-local-completion-verify.sh`。使用本项目 Docker 依赖栈时，它会自动识别 `PISCES_REDIS_DOCKER_CONTAINER` 指向的本地 Redis 容器，并用 `docker-stop` 完成可恢复 Redis fault 演练；外部 Redis 或手工指定容器仍需要显式选择 fault 模式和确认。缺失或占位符 `TONGYI_API_KEY` 时，它只写出 `NEEDS_QIANWEN_API_KEY` summary，不会启动服务或生成虚假证据。

`production-infrastructure-local-completion-verify.sh` 不启动服务、不采集证据，只读取 finalizer summary、AI smoke summary、readiness summary、collection summary、closeout summary/report、`layout-audit.json` 和 release evidence manifest。finalizer 会默认调用它；也可以在需要复核时单独运行。它在补 key 前输出 `NEEDS_API_KEY`，在 key 已配置但 finalizer 或 closeout 证据不完整时输出 `NEEDS_FINALIZER`，只有 finalizer `status=PASS`、所有关键步骤 `PASS`、TongYi AI smoke `status=PASS`、collection `status=PASS`、closeout 报告 `Verdict: **COMPLETE**`、最终 completion audit `completionStatus=COMPLETE`、布局审计 `status=PASS failedCount=0 enforcedCount>=8` 且 release evidence manifest 的 `environment=local` 时，才输出 `status=COMPLETE`。

`production-infrastructure-local-bootstrap.sh` 会创建 `config/pisces-local.env`、确认该文件被 Git 忽略、检查默认 MySQL / 本地 scoped API keys 是否保留，并用 `NEEDS_QIANWEN_API_KEY` 或 `READY_TO_SOURCE` 表示是否只剩替换千问 key。summary 不包含真实 key。`production-infrastructure-local-dependency-stack.sh` 使用 `compose.local.yml` 启动本地 MySQL、Redis 和 Zookeeper；当 3306、6379 或 2181 被占用时，会自动选择备用端口并写入 `config/pisces-local-stack.env`。后续 schema apply、dependency check、readiness、service start 和 evidence collect 脚本会自动读取本地 env 文件。`production-infrastructure-local-mysql-schema-apply.sh` 只对本地 MySQL 应用基础建表 SQL，默认拒绝非本地 `MYSQL_URL`，并跳过迁移脚本，因为新库基础 DDL 已包含当前列和索引。`production-infrastructure-local-dependency-check.sh` 会继续验证 MySQL 连接/schema、Redis ping 和 Zookeeper 等价性；如果不用 Docker 本地依赖栈，也可以按 summary 修改 `MYSQL_USERNAME` / `MYSQL_PASSWORD` 接入已有本机 MySQL。`production-infrastructure-local-service.sh start` 会拒绝缺失或占位符千问 key，按同一组 env 启动后端，并等待 `/api/actuator/health` 返回 `UP`。

本地证据采集器会先读取 `target/pisces-production-infrastructure-local-service/summary.json`，要求本地服务由 `production-infrastructure-local-service.sh start` 启动且 `status=HEALTHY`、`apiKeyStatus=configured`、`healthStatus=UP`，再检查实时 `/actuator/health`。如果没有显式设置 `PISCES_EXPERIMENT_ID`，会用 `ops-key` 调用 `/experiments/generator/demo` 自动创建本地达标演示实验，再依次执行 runtime release drill、容量基线、Redis 故障注入、事件 replay 分段修复审计、实验影响面抽样、发布后指标摘要、full rollout 记录、production acceptance 记录和本地证据结构校验。分步直接运行 collector 时 Redis 故障注入默认是人工窗口；通过 `production-infrastructure-local-finalize.sh` 且使用本项目 Docker 栈时，会自动传入 `PISCES_LOCAL_COLLECT_REDIS_FAULT_MODE=docker-stop`、`PISCES_REDIS_DOCKER_CONTAINER` 和 `PISCES_FAULT_CONFIRM=true`。

采集完成后，`collection-summary.json` 会写出 `validateWrapper`、`closeoutWrapper` 和 `nextCommands`。如果本地 worktree 已经干净并且 `TONGYI_API_KEY` 已设置，可以直接在采集命令上追加 `PISCES_LOCAL_COLLECT_RUN_CLOSEOUT=true`，让 collector 在证据结构校验后继续执行 `run-local-closeout.sh`、strict package check、证据归档、SLO 回看、rollout decision、production acceptance 和最终 completion audit。

如果需要手工补充或重跑单项证据，可以先生成可编辑工作区：

```bash
PISCES_RELEASE_ID="local-20260730-runtime-plane" \
bash scripts/production-infrastructure-local-evidence-workspace.sh
```

该命令会在 `target/pisces-production-infrastructure-local-evidence/<release-id>/` 下创建可编辑证据模板和 `run-local-closeout.sh`。先把所有 `TODO` 替换为真实本地结果，或使用采集器已经生成的证据文件，再执行：

```bash
source config/pisces-local.env
source config/pisces-local-stack.env 2>/dev/null || true

bash target/pisces-production-infrastructure-local-evidence/local-20260730-runtime-plane/validate-local-evidence.sh
bash target/pisces-production-infrastructure-local-evidence/local-20260730-runtime-plane/run-local-closeout.sh
```

该脚本不会生成虚假的完成证据。`validate-local-evidence.sh` 会先校验 8 份本地证据文件是否存在、是否仍有占位符、JSON 是否可解析，以及 `releaseId`、`environment=local`、`stage=full`、`decision=PROCEED`、`finalDecision=ACCEPT`、事件重放分段修复和关键指标阈值是否闭合。`run-local-closeout.sh` 会先重复执行该校验，再要求本地具备 `ruby`、`promtool`、干净 git worktree 和千问 API key，然后执行 strict release package check、预发记录校验、证据归档、SLO 回看、full-stage rollout decision、production acceptance 和最终 closeout。

如果任一输入证据文件仍包含 `TODO*` 或 `LOCAL-TODO`，local closeout 会在跑耗时测试前直接失败，并打印具体文件和行号。

最终 closeout 使用同一组证据生成 Markdown 报告：

```bash
PISCES_COMPLETION_TARGET_ENVIRONMENT="local" \
PISCES_COMPLETION_RELEASE_PACKAGE_REPORT_FILE="target/pisces-runtime-release-package-check/report.json" \
PISCES_COMPLETION_PREPROD_RECORD_CHECK_SUMMARY_FILE="target/pisces-runtime-preprod-drill-record-check/summary.json" \
PISCES_COMPLETION_RELEASE_EVIDENCE_MANIFEST_FILE="target/pisces-runtime-release-evidence-archive/<release>/manifest.json" \
PISCES_COMPLETION_PRODUCTION_ACCEPTANCE_SUMMARY_FILE="target/pisces-runtime-production-acceptance/summary.json" \
PISCES_COMPLETION_SCREENSHOT_DIR="../pisces-web/target/screenshots/core-functions-current" \
bash scripts/production-infrastructure-closeout.sh
```

默认输出：

```text
target/pisces-production-infrastructure-closeout/completion-summary.json
target/pisces-production-infrastructure-closeout/closeout-report.md
```

## 完成条件

| 层级 | 必须证明 |
| --- | --- |
| Control Plane | API key scope、应用隔离、应用空间、配置版本/草稿/回滚、审计日志、审批和升级告警有实现、SQL 和测试证据 |
| Data Plane | runtime config API、版本检查/long-poll、`assign/trace`、Redis 配置广播、分流指标、Java/JS SDK 缓存/重试/metrics 有实现和测试证据 |
| Event Plane | runtime 数据采集、MySQL inbox、异步消费者、物化账本、replay job、分段 plan/repair、审计脚本和 replay 证据归档有实现和测试证据 |
| Decision Plane | AI evidence、数据质量门禁、报告快照事实、人工结论绑定配置版本和报告版本、配置变更后结论重置有实现和测试证据 |
| Operations | strict CI 发布包、预发记录校验、发布证据归档、发布后 SLO、影响面抽样、分批发布决策、最终生产验收、核心功能截图都通过 |

真实环境证据必须形成同一次发布的闭环，不能拼接不同批次的局部报告：

- release package report 必须来自 strict CI：`runTests=true`、`requirePromtool=true`、`requireRuby=true`、`gitDirty=false`、`warnings=0`。
- 仓库 secret scan 必须通过；真实千问/DashScope key 只能放在本地环境变量中，不能写入仓库。
- release package report、preprod record check、release evidence manifest 和 production acceptance 的 `gitSha` / `releaseId` 必须互相一致。
- release evidence manifest 和 production acceptance 的 `environment` 必须等于 `PISCES_COMPLETION_TARGET_ENVIRONMENT`（本地验收为 `local`，线上验收为 `prod`），production acceptance 必须是 `stage=full`。
- preprod record check 和 production acceptance 的 `gates` 必须全部为 `PASS`。
- release evidence manifest 必须包含 release package、preprod drill、capacity baseline、Redis fault 和 event replay audit 的归档证据。

核心功能截图不是数量占位。截图采集入口固定在 `../pisces-web/scripts/capture-core-functions.cjs`，前端需要声明 Playwright 依赖；本地 finalizer 会通过 `scripts/production-infrastructure-local-frontend-evidence.sh` 自动启动或复用前端 dev server、执行 `npm run audit:prod-high` 并运行 `npm run capture:core`。分步操作时也可以在前端 dev server 启动后手工执行 `npm run capture:core`。截图目录会同时生成 `layout-audit.json`，用于证明核心桌面工作区和弹层满足横屏视口、无横向溢出、避免 body 级长下拉的布局契约。completion audit 和本地 completion verify 都会读取这份审计，要求 `summaryType=pisces-web-core-layout-audit`、`status=PASS`、`failedCount=0`、`enforcedCount>=8`。`PISCES_COMPLETION_SCREENSHOT_DIR` 至少要覆盖以下 14 类截图，文件名需要能表达对应能力。每张命中的截图还必须是有效 PNG、横屏视口、尺寸不低于 `1366x768`，并且通过像素采样证明不是空白占位图：

- AI 工作台 / 优先级队列
- 实验列表 / 实验工作台
- 实验详情 / 数据链路导航
- 实验配置版本治理
- 实验结论流转
- 实验审批面板
- 实验运行结构
- 实验统计 / MAB
- 数据链路状态
- 事件重放计划
- 事件重放分段修复
- AI 决策工作台
- 应用空间治理
- AI 实验设计草稿

## 输出语义

| 字段 | 说明 |
| --- | --- |
| `staticStatus` | 仓库静态能力证据状态 |
| `realEnvironmentStatus` | 真实环境发布和验收证据状态 |
| `completionStatus` | 仅当静态能力和真实环境证据均为 `PASS` 时为 `COMPLETE` |
| `planes` | 各平面通过、阻塞和失败 gate 数 |
| `gates` | 逐项证据，包含 `plane`、`name`、`type`、`status` 和 evidence 路径 |

退出码：

| 退出码 | 说明 |
| --- | --- |
| `0` | `completionStatus=COMPLETE` |
| `1` | 仍有 `HOLD`，通常是缺少真实环境证据 |
| `2` | 静态能力或真实证据存在失败 |

## 本地验证

```bash
bash scripts/production-infrastructure-completion-audit-smoke-test.sh
bash scripts/production-infrastructure-local-evidence-validate-smoke-test.sh
bash scripts/production-infrastructure-local-completion-verify-smoke-test.sh
```

该 smoke 会构造 strict CI 发布包报告、预发记录检查摘要、发布证据 manifest、生产验收摘要和核心截图目录，并断言审计输出 `completionStatus=COMPLETE`。
本地证据校验 smoke 会先确认模板证据被占位符门禁拒绝，再用明确标记为 smoke 的本地证据验证结构校验可通过。本地完成验收 smoke 会先断言占位 key 输出 `NEEDS_API_KEY`，再用完整本地 closeout 证据断言 `status=COMPLETE`。
