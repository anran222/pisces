# Runtime Plane Release Package Check

本文档用于在运行时分流平面发布前检查发布包完整性。检查对象包括 runtime HTTP 契约、AI 决策证据契约、SDK 兼容性、运维脚本、监控资产和发布文档，目标是在进入预发演练前先发现漏发文件、脚本语法错误或契约证据缺失。

## 快速执行

默认只做离线静态检查，不依赖本地服务、Redis、MySQL 或外部网络：

```bash
bash scripts/runtime-plane-release-package-check.sh
```

执行成功后会写出 JSON 报告：

```text
target/pisces-runtime-release-package-check/report.json
```

如果发布窗口要求同时跑聚焦测试：

```bash
PISCES_RELEASE_PACKAGE_RUN_TESTS=true \
bash scripts/runtime-plane-release-package-check.sh
```

## 检查内容

脚本会检查：

- runtime 配置 Controller、响应模型、Service 实现、Service 契约测试和 HTTP 契约测试是否随包存在。
- AI 诊断和毕业响应的 `evidence` 数据质量证据字段、最新报告快照版本绑定、Prompt 事实注入、Service 绑定逻辑、公共响应形状测试和 HTTP 序列化测试是否随包存在。
- 人工结论确认的 `expectedConfigVersion` / `reportSnapshotVersion` 请求契约、实验详情绑定字段、服务端证据校验、配置变更后的结论重置和审计详情测试是否随包存在。
- Java SDK、JS SDK 的缓存、长轮询、stale fallback、重试和 metrics snapshot 相关实现与测试是否随包存在。
- 生产基础设施完成度审计、本地 bootstrap、本地 Docker 依赖栈、本地 MySQL schema apply、本地依赖预检、本地服务启动/健康检查、本地证据采集器、本地证据工作区、本地证据结构校验、本地 pre-key 预演、本地完成验收、运行时发布演练、容量基线、基线归档、Redis 故障注入、预发演练记录校验、发布证据归档、发布后 SLO 回看、实验影响面抽样、分批发布决策、生产验收、事件管道重放审计和发布包检查脚本是否通过 `bash -n`；其中发布证据归档脚本需要支持 `PISCES_EVENT_REPLAY_AUDIT_SUMMARY_FILE`，校验事件 replay audit summary，并把 `eventPipelineReplayAudit` 写入 manifest。
- 事件管道重放审计是否随包包含异步 job 终态轮询、筛选复制型 replay、服务端影响面上限、批量分页、运行中进度回写、计划总量/进度百分比、取消安全点、缺账本修复模式、分段巡检和指定分段恢复：`PISCES_EVENT_REPLAY_JOB_TIMEOUT_SECONDS`、`replay_job_terminal_success`、`PISCES_EVENT_REPLAY_MAX_AFFECTED_PLAN`、`PISCES_EVENT_REPLAY_MAX_FILTERED_COPY_FACTS`、`PISCES_EVENT_REPLAY_BATCH_SIZE`、`plannedAffectedCount`、`progressPercent`、`FILTERED_DERIVED_COPY_REPLAY`、`REPLAY_COPY`、`PISCES_EVENT_REPLAY_REPAIR_MATERIALIZATION`、`PISCES_EVENT_REPLAY_SEGMENT_COUNT`、`PISCES_EVENT_REPLAY_REPAIR_SEGMENT_INDEX`、`/events/replay/materialization/repair`、`/events/replay/materialization/repair/segments/{segmentIndex}`、`repair_materialization_operation_success`、`replay_plan_segments_generated`、`post_repair_replay_plan_unmaterialized_count`、`PISCES_EVENT_REPLAY_EVENT_TYPES` 等 replay scope 变量、修复后 `replayPlanAfterRepair` 和 `replayScopeRequest` 样例证据、CI path trigger；同时静态检查 replay job 详情、`CANCEL_REQUESTED` 取消控制面、筛选计划复制型补派生、筛选计划局部补物化实现、分段 plan/repair 实现、时间范围索引 migration、影响面上限测试、批量分页测试、运行中计数契约、计划总量 migration 和 Redis factId 去重测试是否随 API、服务、前端和文档发布。默认检查还会运行 `scripts/event-pipeline-replay-audit-scope-smoke-test.sh` 和 `scripts/event-pipeline-replay-segment-repair-smoke-test.sh`，用本地 mock API 断言修复前 plan、repair、修复后 plan 使用同一份 scope 请求体，且指定分段修复后分段缺口归零。前端同级仓库通过独立 `npm test` 和 `npm run build` 验证。
- 测试模式会额外执行 `AnalysisServiceImplEventPipelineTest`、`EventInboxMaterializerTest`、`ProductionExperimentFlowSmokeTest`、`EventReplayMetricsTest` 和 `EventReplayExecutorConfigTest`，覆盖事件管道重放、筛选复制型 replay、筛选计划局部补物化、缺账本修复、物化账本、worker 指标、executor 配置和真实实验闭环。
- Grafana runtime dashboard 是否是合法 JSON。
- Prometheus runtime alert rules 在本机有 `ruby` 时是否可解析 YAML，在本机有 `promtool` 时是否通过规则检查。
- 事件管道 replay audit scoped request smoke test 和 segmented repair smoke test 是否通过；这些测试只启动本地 mock HTTP server，不依赖真实后端、Redis、MySQL 或外部网络。
- 生产基础设施完成度审计 smoke test 是否通过；该 smoke 构造 strict CI 发布包报告、预发记录检查摘要、发布证据 manifest、生产验收摘要、核心截图目录和 `layout-audit.json`，断言 `production-infrastructure-completion-audit.sh` 输出 `completionStatus=COMPLETE` 且布局审计 gates 全部通过。
- 本地 bootstrap smoke test 是否通过；该 smoke 断言脚本会创建被 Git 忽略的 `config/pisces-local.env`，在未替换 key 时输出 `NEEDS_QIANWEN_API_KEY`，strict 模式拒绝占位符，并且替换后输出 `READY_TO_SOURCE`、next command 指向 `production-infrastructure-local-finalize.sh`、不泄露真实 key。
- 本地 Docker 依赖栈 smoke test 是否通过；该 smoke 不启动容器，只用 dry-run 验证 `compose.local.yml`、端口覆盖、`config/pisces-local-stack.env` 生成，以及 dependency/schema 脚本会自动消费该覆盖文件。
- 本地 MySQL schema apply smoke test 是否通过；该 smoke 断言脚本 dry-run 会计划基础建表 SQL、跳过迁移脚本，并且默认拒绝非本地 `MYSQL_URL`。
- 本地依赖预检 smoke test 是否通过；该 smoke 断言脚本能输出 `pisces-production-infrastructure-local-dependency-check` summary、在禁用外部依赖检查时进入 `READY_FOR_LOCAL_SERVICE_START`，并在 strict 模式下拒绝不可达 MySQL。
- 本地服务启动 smoke test 是否通过；该 smoke 断言服务脚本会拒绝占位符千问 key，dry-run 下记录后端启动命令、健康检查 URL 和 stack env，并且不会把 key 写入 summary。
- 本地 TongYi AI smoke test 是否通过；该 smoke 断言 `production-infrastructure-local-ai-smoke.sh` 会拒绝占位符 key、dry-run 不触碰服务，并在 mock `/api/variants/generate` 成功时输出 `status=PASS`、记录 `TONGYI_MODEL=qwen3.7-max`、`TONGYI_API_MODE=dashscope`、实际命中的 `tongyiSelectedModel`、尝试模型列表和生产 DashScope 策略且不泄露 key；前端核心截图还必须包含 `09-variant-lab-tongyi-model-evidence.png`，证明候选生成页面用紧凑横屏布局展示实际模型证据。
- 本地证据采集器 smoke test 是否通过；该 smoke 只运行 plan-only 模式，断言 collector 会规划 8 份最终证据、列出 release drill / capacity baseline / event replay audit / impact sampling / validator / generated closeout wrapper 命令；未传 `PISCES_EXPERIMENT_ID` 时会计划调用 `/experiments/generator/demo` 自动准备本地演示实验，并且不会伪造最终证据文件。
- 本地证据结构校验 smoke test 是否通过；该 smoke 先确认模板工作区因 `TODO` 占位符被拒绝，再用明确标记为 smoke 的本地证据断言 `production-infrastructure-local-evidence-validate.sh` 输出 `status=PASS`。
- 本地 pre-key 完整流程预演 smoke test 是否通过；该 smoke 验证本地 env 被忽略、finalizer 在占位符 key 下安全停住、临时 key dry-run 不泄露 key，并且完整计划包含依赖栈、schema、依赖预检、后端启动、readiness、TongYi AI smoke、前端截图、证据采集、closeout 和 completion verify。
- 本地完成验收 smoke test 是否通过；该 smoke 先断言占位 key 和不完整证据只能输出 `NEEDS_API_KEY`，再构造完整 finalizer、AI smoke、readiness、collection、closeout、`layout-audit.json` 和 release evidence manifest，断言 `production-infrastructure-local-completion-verify.sh` 输出 `status=COMPLETE` 且不泄露 key。
- 本地 readiness smoke 是否通过；该 smoke 跳过真实服务 health 调用，但会执行 completion audit、secret scan、本地依赖预检和 collector plan-only 预检，确认 readiness summary 能输出工具、密钥、本地 env 文件状态、key 替换阶段、MySQL/Redis 依赖、截图、collector wrapper、直接 finalizer next command 和剩余真实证据 gate。
- 预发演练记录 smoke test 是否通过；该 smoke 构造 strict CI 发布包报告、容量基线 manifest、分段 event replay audit summary 和一份已填写预发演练记录，断言 `runtime-plane-preprod-drill-record-check.sh` 输出 `status=PASS`。
- 发布证据归档 smoke test 是否通过；默认同时执行宽松归档 smoke 和严格 CI 归档 smoke。宽松 smoke 构造本地最小发布报告、预发记录和事件 replay audit summary，断言归档 manifest 写入 `eventPipelineReplayAudit` 和 `eventPipelineReplayAuditSummary`；严格 smoke 构造 `runTests=true`、`requirePromtool=true`、`requireRuby=true`、`gitDirty=false` 的发布包报告，并校验容量基线、Redis 故障记录和分段 replay audit summary 都进入 manifest。
- 生产验收 smoke test 是否通过；该 smoke 构造 strict CI 发布包报告、发布证据 manifest、发布后 SLO 摘要、影响面抽样摘要、分批发布决策和人工验收记录，断言最终 `runtime-plane-production-acceptance-check.sh` 输出 `decision=ACCEPT`。
- 发布清单、契约矩阵和 Runbook 是否引用了发布包检查入口。

## 环境变量

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `PISCES_REPO_ROOT` | 自动推断 | 仓库根目录 |
| `PISCES_RELEASE_PACKAGE_RUN_TESTS` | `false` | 是否运行 API、Service、事件管道重放治理、AI 决策证据、Java SDK 和 JS SDK 聚焦测试 |
| `PISCES_RELEASE_PACKAGE_REPORT_FILE` | `target/pisces-runtime-release-package-check/report.json` | JSON 证据报告输出路径 |
| `PISCES_RELEASE_PACKAGE_REQUIRE_PROMTOOL` | `false` | `promtool` 缺失时是否失败 |
| `PISCES_RELEASE_PACKAGE_REQUIRE_RUBY` | `false` | `ruby` 缺失时是否失败 |

## CI 接入

仓库提供 `.github/workflows/runtime-plane-release-package.yml`。该 workflow 会：

- 安装 Java 21、Node 20、`promtool` 和 `ruby`。
- 使用 `PISCES_RELEASE_PACKAGE_RUN_TESTS=true` 运行发布包检查。
- 使用 `PISCES_RELEASE_PACKAGE_REQUIRE_PROMTOOL=true` 和 `PISCES_RELEASE_PACKAGE_REQUIRE_RUBY=true` 强制执行 Prometheus 规则语义检查和 YAML 解析。
- 上传 `target/pisces-runtime-release-package-check/` 作为 `runtime-plane-release-package-report` artifact。
- 报告中的 `releaseArtifacts` 会列出生产基础设施完成度审计与 smoke、本地环境模板、本地 bootstrap 与 smoke、本地 Docker Compose 依赖栈与 smoke、本地 MySQL schema apply 与 smoke、本地依赖预检与 smoke、本地服务启动与 smoke、本地 TongYi AI smoke、本地证据采集器与 smoke、本地证据工作区、本地证据结构校验与 smoke、本地 pre-key 预演与 smoke、本地完成验收与 smoke、发布包检查脚本、发布证据归档脚本、发布证据宽松/严格 smoke、预发演练记录检查与 smoke、生产验收检查与 smoke、发布后 SLO 回看脚本、实验影响面抽样脚本、分批发布决策脚本、事件管道重放审计脚本、完成度审计文档、发布包检查文档、发布证据归档文档、生产验收文档、发布后复盘文档、分批发布、回滚演练和事件管道重放审计文档、预发演练模板、预发演练样例和 CI workflow。
- `.github/workflows/runtime-plane-release-package.yml` 会在 `scripts/event-pipeline-replay-audit.sh` 变化时触发，避免事件审计脚本绕过发布包检查。

## 发布门禁

发布前至少执行默认静态检查。影响 runtime HTTP 契约、SDK 解析字段、缓存刷新、重试策略或观测资产的变更，应设置 `PISCES_RELEASE_PACKAGE_RUN_TESTS=true` 执行聚焦测试。

脚本成功退出只说明发布包静态完整，不能替代预发环境的 `runtime-plane-release-drill.sh`、容量基线、Redis 故障注入、发布后 SLO 回看、实验级影响面抽样、分批发布决策、最终生产验收、生产基础设施完成度审计和事件管道重放审计。涉及事件采集、异步物化、统计派生数据或 MAB 奖励的发布，应补充 `event-pipeline-replay-audit.sh` 运行证据，并通过 `PISCES_EVENT_REPLAY_AUDIT_SUMMARY_FILE` 进入发布证据归档；若只读计划发现缺账本，必须归档 `PISCES_EVENT_REPLAY_REPAIR_MATERIALIZATION=true` 的修复结果，并以 `post_repair_replay_plan_unmaterialized_count=PASS` 作为关闭条件。大窗口修复应优先提交带 `PISCES_EVENT_REPLAY_SEGMENT_COUNT` 的分段计划证据；如果只恢复某个失败分段，发布记录必须包含 `PISCES_EVENT_REPLAY_REPAIR_SEGMENT_INDEX` 和对应分段的 `segments` 计数。

最终关闭“生产级实验基础设施”目标前，应按 `docs/operations/production-infrastructure-completion-audit.md` 执行 `scripts/production-infrastructure-completion-audit.sh`，并传入真实 CI、预发、发布证据、生产验收和核心截图证据。本机作为目标环境时，先运行 `scripts/production-infrastructure-local-prekey-check.sh`，看到 `READY_FOR_API_KEY` 后只替换 `config/pisces-local.env` 中的 `TONGYI_API_KEY`，再运行 `scripts/production-infrastructure-local-finalize.sh`。如果 `config/pisces-local.env` 不存在，finalizer 会先生成模板并以 `NEEDS_QIANWEN_API_KEY` 停下，默认只需要替换 `TONGYI_API_KEY` 后重跑同一个命令。key 配好后，finalizer 会启动本地 MySQL/Redis/Zookeeper、应用本地 schema、执行严格依赖预检、启动后端、运行 readiness、执行 TongYi AI smoke、自动执行前端 high 级依赖审计、生成核心横屏截图和 `layout-audit.json`、采集本地真实证据、进入 closeout，并默认调用 `scripts/production-infrastructure-local-completion-verify.sh`。collector 在未传 `PISCES_EXPERIMENT_ID` 时会自动生成本地演示实验；前端核心截图默认写入 `../pisces-web/target/screenshots/core-functions-current` 并作为核心前端截图证据目录，布局审计用于约束核心工作区不能退回长下拉平铺。需要人工补证时再用 `scripts/production-infrastructure-local-evidence-workspace.sh` 生成证据工作区。只有本地完成验收输出 `status=COMPLETE`，其引用的 TongYi AI smoke `status=PASS`、最终 closeout `completionStatus=COMPLETE`，`layout-audit.json` 为 `status=PASS failedCount=0 enforcedCount>=8`，且 `09-variant-lab-tongyi-model-evidence.png` 存在并在 layout audit 中 `status=PASS`，才能视为目标完成。

预发演练记录使用 `docs/operations/runtime-plane-preprod-drill-record-template.md`，其中应附上 CI Run URL、`report.json` artifact、runtime drill、容量基线、Redis 故障注入和必要的事件 replay audit 证据。可参考 `docs/operations/runtime-plane-preprod-drill-record-sample.md`。归档发布证据前应先执行 `scripts/runtime-plane-preprod-drill-record-check.sh`；归档后设置 `PISCES_PREPROD_REQUIRE_EVIDENCE_ARCHIVE=true` 复查 manifest 路径和 sha256。
