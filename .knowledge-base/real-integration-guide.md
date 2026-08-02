# 真实业务接入指南

## 接入目标

业务方接入 Pisces 时，应完成一条完整实验链路：

1. 在管理台创建实验，定义实验组、事件和指标。
2. 启动实验。
3. 业务侧使用 SDK 获取命中实验组。
4. 命中实验组后上报曝光。
5. 用户产生行为后按实验事件 key 上报事件。
6. 在管理台查看统计、数据质量、AI 诊断和毕业建议。

## 前置配置

后端启动时通过环境变量注入运行配置：

```bash
export MYSQL_URL="jdbc:mysql://localhost:3306/pisces?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
export MYSQL_USERNAME="root"
export MYSQL_PASSWORD=""
cp config/pisces-local.env.example config/pisces-local.env
# 编辑 config/pisces-local.env，通常只替换 TONGYI_API_KEY；MySQL 非默认配置时再改数据库项
source config/pisces-local.env
export PISCES_API_KEY_SPECS="runtime-key|shop-app|sdk|runtime,ops-key|shop-app|ops|management+analysis"
export PISCES_CONFIG_CHANGE_REDIS_BROADCAST_ENABLED="true"
export PISCES_CONFIG_CHANGE_REDIS_CHANNEL="pisces:config-change"
```

`config/pisces-local.env` 已被 `.gitignore` 忽略，不能提交真实密钥。`TONGYI_API_KEY` 未配置时，实验管理、分流和数据上报链路仍可运行；AI 相关接口会返回服务不可用错误。

API Key 请求头为 `X-Pisces-Api-Key`。业务运行时 SDK 使用 `runtime` scope，管理台和实验配置变更使用 `management` scope，分析与报告接口使用 `analysis` scope。非 `admin` key 创建实验时会写入自身 `appId` / `owner`，后续只能访问同应用实验；跨应用调用分流、上报、分析或管理接口会被拒绝。旧 `PISCES_API_KEYS` 兼容模式会授予全部 scope，仅建议迁移期使用。

多实例部署建议开启 Redis 配置变更广播。配置保存、删除或 Zookeeper watcher 收到变更后，本实例会推进本地配置变更序列；开启 `PISCES_CONFIG_CHANGE_REDIS_BROADCAST_ENABLED=true` 后，写入实例还会通过 Redis Pub/Sub 广播实验 ID，其他实例收到后提前唤醒 `/runtime/experiments/{id}/config/version?waitMillis=...` 的等待请求。`PISCES_CONFIG_CHANGE_REDIS_CHANNEL` 可用于隔离环境或集群。

## 应用空间治理

生产接入前建议先注册应用空间：

```bash
curl -X PUT "http://localhost:9990/api/applications/shop-app" \
  -H "X-Pisces-Api-Key: ops-key" \
  -H "Content-Type: application/json" \
  -d '{"displayName":"交易应用","defaultOwner":"pm-a","approvalOwners":["reviewer-a","reviewer-b"],"approvalRequiredCount":2,"approvalSlaHours":8,"approvalEscalationOwners":["ops-a","pm-lead"],"experimentQuota":20,"approvalRequired":true,"releaseWindowEnabled":true,"releaseWindowTimezone":"Asia/Shanghai","releaseWindowDays":[1,2,3,4,5],"releaseWindowStartTime":"09:00","releaseWindowEndTime":"18:00"}'
```

注册后，`GET /api/applications` 会返回展示名、默认负责人、审批人列表、审批通过人数、审批策略版本、审批 SLA、升级接收人、实验配额、已用配额、剩余配额、配置/启动审批开关和发布窗口。`admin` key 创建实验且未指定 `owner` 时会优先使用应用空间默认负责人；应用配置了 `experimentQuota` 时，创建实验前会先校验配额；应用启用 `approvalRequired` 后，配置草稿发布、实验启动或恢复前需要审批通过。`approvalRequiredCount` 大于 1 时，多个审批人需要分别提交 `APPROVED`，未达人数前审批状态保持 `PENDING`。审批任务会绑定提交时的审批人、通过人数和策略版本；后续修改应用空间审批策略不会改变已经提交的待办。应用启用 `approvalSlaHours` 后，`GET /api/experiments/approval-tasks` 会返回 `approvalSubmittedAt`、`approvalElapsedHours`、`approvalSlaStatus`、`approvalEscalationOwners` 和 `approvalEscalationReason`，用于管理台展示和外部告警集成。应用启用 `releaseWindowEnabled` 后，启动、恢复、运行中配置更新、配置草稿发布和配置回滚必须落在配置的时区/星期/时间窗口内。

创建或更新实验成功后，实验中的 `eventDefinitions` 和 `metricDefinitions` 会自动沉淀到应用级字典，可通过下面接口查看：

```bash
curl "http://localhost:9990/api/applications/shop-app/dictionary" \
  -H "X-Pisces-Api-Key: ops-key"
```

已有数据库如果先前已创建 `pisces_application_space` 表，需要先执行：

```bash
mysql "$MYSQL_DATABASE" < pisces-service/src/main/resources/sql/mysql/pisces_application_space_approval_required_migration.sql
mysql "$MYSQL_DATABASE" < pisces-service/src/main/resources/sql/mysql/pisces_application_dictionary.sql
mysql "$MYSQL_DATABASE" < pisces-service/src/main/resources/sql/mysql/pisces_experiment_config_draft_approval.sql
mysql "$MYSQL_DATABASE" < pisces-service/src/main/resources/sql/mysql/pisces_experiment_approval_vote.sql
mysql "$MYSQL_DATABASE" < pisces-service/src/main/resources/sql/mysql/pisces_experiment_approval_escalation.sql
mysql "$MYSQL_DATABASE" < pisces-service/src/main/resources/sql/mysql/pisces_event_replay_job.sql
mysql "$MYSQL_DATABASE" < pisces-service/src/main/resources/sql/mysql/pisces_event_materialization.sql
```

已有环境如果已经创建过 `pisces_event_replay_job`，需要额外执行 `pisces_event_replay_job_scope_migration.sql` 补齐重放 scope 字段；新环境直接执行当前建表脚本即可。

审批通过示例：

```bash
curl -X POST "http://localhost:9990/api/experiments/exp_price_001/approval-status" \
  -H "X-Pisces-Api-Key: ops-key" \
  -H "Content-Type: application/json" \
  -d '{"approvalStatus":"APPROVED","comment":"首轮灰度允许启动"}'
```

如果该实验已有报告快照，审批通过会联动最新报告风险。存在 SRM 或护栏异常时，普通 `APPROVED` 会被拒绝；审批人仍可提交 `REJECTED` 终止该次启动/配置发布审批。`GET /api/experiments/approval-tasks` 会返回 SLA/升级字段以及 `approvalRiskLevel`、`approvalRiskFlags`、`breachedGuardrails`、`approvalRiskDisabledReason`、`riskOverrideRequired` 和 `riskOverrideAllowed` 供管理台展示。`admin` 如需受控例外，可以提交：

```bash
curl -X POST "http://localhost:9990/api/experiments/exp_price_001/approval-status" \
  -H "X-Pisces-Api-Key: ops-key" \
  -H "Content-Type: application/json" \
  -d '{"approvalStatus":"APPROVED","comment":"业务窗口必须发布","riskOverride":true,"riskOverrideReason":"业务窗口必须发布"}'
```

审批升级告警 outbox 示例：

```bash
curl -X POST "http://localhost:9990/api/experiments/approval-escalations/scan" \
  -H "X-Pisces-Api-Key: ops-key"

curl "http://localhost:9990/api/experiments/approval-escalations?escalationStatus=OPEN" \
  -H "X-Pisces-Api-Key: ops-key"

curl "http://localhost:9990/api/experiments/approval-escalations/status" \
  -H "X-Pisces-Api-Key: ops-key"

curl -X POST "http://localhost:9990/api/experiments/approval-escalations/esc_xxx/ack" \
  -H "X-Pisces-Api-Key: ops-key" \
  -H "Content-Type: application/json" \
  -d '{"operator":"ops","comment":"已在群内提醒审批人"}'

curl -X POST "http://localhost:9990/api/experiments/approval-escalations/esc_xxx/notification/retry?operator=ops" \
  -H "X-Pisces-Api-Key: ops-key"

curl -X POST "http://localhost:9990/api/experiments/approval-escalations/dead/retry?operator=ops" \
  -H "X-Pisces-Api-Key: ops-key"
```

扫描接口会为逾期 `PENDING` 审批生成 `APPROVAL_ESCALATION_OUTBOX` 消息载荷。后台默认每 60 秒扫描一次，可通过 `PISCES_APPROVAL_ESCALATION_SCAN_ENABLED=false` 关闭，或通过 `PISCES_APPROVAL_ESCALATION_SCAN_DELAY_MS` / `PISCES_APPROVAL_ESCALATION_SCAN_INITIAL_DELAY_MS` 调整频率。

外部投递默认关闭。需要把 outbox 投递到告警系统时，至少配置：

```bash
PISCES_APPROVAL_ESCALATION_DISPATCH_ENABLED=true
PISCES_APPROVAL_ESCALATION_WEBHOOK_URL=https://example.com/pisces/approval-escalations
```

单目标场景继续使用 `PISCES_APPROVAL_ESCALATION_WEBHOOK_URL`。多目标投递可以追加逗号分隔的 `PISCES_APPROVAL_ESCALATION_WEBHOOK_URLS`，并用 `PISCES_APPROVAL_ESCALATION_WEBHOOK_CHANNEL_NAMES` 按顺序声明通道名，例如：

```bash
PISCES_APPROVAL_ESCALATION_WEBHOOK_URL=https://lark.example.com/pisces/approval-escalations
PISCES_APPROVAL_ESCALATION_WEBHOOK_URLS=https://slack.example.com/pisces/approval-escalations
PISCES_APPROVAL_ESCALATION_WEBHOOK_CHANNEL_NAMES=lark,slack
```

每个 webhook payload 会包含 `dispatchChannel`，用于外部系统区分接收通道。系统会为每个当前启用目标写入 `notificationDeliveries` 回执；某个目标成功后不会因为其他目标失败而重复投递，失败目标会独立进入 `RETRY` 或 `DEAD`。outbox 的 `notificationStatus` 由当前启用目标回执聚合得出：任一通道 `DEAD` 则整体 `DEAD`，任一通道 `RETRY` 则整体 `RETRY`，全部通道 `SENT` 后整体 `SENT`。外部系统仍建议按 `escalationId + dispatchChannel` 做幂等，避免网络超时导致的重复提醒。

可选配置包括 `PISCES_APPROVAL_ESCALATION_DISPATCH_DELAY_MS`、`PISCES_APPROVAL_ESCALATION_DISPATCH_BATCH_SIZE`、`PISCES_APPROVAL_ESCALATION_MAX_RETRY_COUNT`、`PISCES_APPROVAL_ESCALATION_DISPATCH_LOCK_MINUTES` 和 `PISCES_APPROVAL_ESCALATION_WEBHOOK_TIMEOUT_MS`。投递成功会把通道回执标记为 `SENT`；失败未达上限会进入 `RETRY` 并设置下次投递时间；达到上限会进入 `DEAD`，管理台 outbox 会展示投递健康、dispatcher 目标数量、通道名、通道未送达数、每通道尝试次数、下次重试/送达时间和最近错误，并支持单条或批量重投死信。审批最终通过或拒绝后，同任务的打开/已确认告警会自动关闭。

审批升级告警和分流热路径同时暴露 Prometheus 指标，审批升级告警默认每 30 秒刷新一次，可通过 `PISCES_APPROVAL_ESCALATION_METRICS_REFRESH_DELAY_MS` 和 `PISCES_APPROVAL_ESCALATION_METRICS_REFRESH_INITIAL_DELAY_MS` 调整。服务运行时可以直接检查：

```bash
curl "http://localhost:9990/api/actuator/prometheus" | grep -E "pisces_approval_escalation|pisces_traffic"
```

核心指标：

| 指标 | 标签 | 含义 |
| --- | --- | --- |
| `pisces_approval_escalation_business_count` | `status=OPEN/ACKNOWLEDGED/RESOLVED/TOTAL` | 审批升级告警业务状态数量 |
| `pisces_approval_escalation_notification_count` | `status=PENDING/DISPATCHING/SENT/RETRY/DEAD/UNDELIVERED` | outbox 总投递状态数量，`UNDELIVERED` 为未送达活跃告警 |
| `pisces_approval_escalation_delivery_count` | `status=PENDING/DISPATCHING/SENT/RETRY/DEAD/UNDELIVERED` | 当前通道回执状态数量，`UNDELIVERED` 为未送达通道 |
| `pisces_approval_escalation_dispatcher_enabled` | 无 | dispatcher 是否启用，`1` 表示已启用 |
| `pisces_approval_escalation_dispatcher_targets` | 无 | 当前配置的 webhook 目标数量 |
| `pisces_approval_escalation_metrics_refresh_healthy` | 无 | 最近一次指标刷新是否成功，`1` 表示成功 |
| `pisces_approval_escalation_metrics_last_refresh_epoch_seconds` | 无 | 最近一次成功刷新时间 |
| `pisces_approval_escalation_metrics_refresh_failures_total` | 无 | 指标刷新失败次数 |
| `pisces_traffic_assignment_requests_total` | `result=ASSIGNED/BLOCKED/ERROR`、`source=CACHE/NEW_ASSIGNMENT/BLOCKED/UNKNOWN`、`reason=CACHE_HIT/ALLOCATED/LAYER_MUTEX/...` | 分流请求结果计数；动态原因会归一化，例如 `LAYER_MUTEX:exp_xxx` 只保留 `LAYER_MUTEX` |
| `pisces_traffic_assignment_latency_seconds` | `result`、`source` | 分流请求耗时分布 |
| `pisces_traffic_cache_events_total` | `operation=USER_GROUP/USER_GROUP_VERSION/LAYER_ASSIGNMENT_READ/...`、`result=HIT/MISS/SUCCESS/ERROR` | 分流 Redis 缓存命中、未命中、写入成功和异常计数 |
| `pisces_config_change_broadcast_enabled` | 无 | Redis 跨实例配置变更广播是否启用 |
| `pisces_config_change_broadcast_published_total` | `result=SUCCESS/ERROR/SKIPPED` | 配置变更广播发送结果 |
| `pisces_config_change_broadcast_received_total` | `result=APPLIED/IGNORED_SELF/INVALID` | 配置变更广播接收处理结果 |
| `pisces_config_change_broadcast_listener_errors_total` | 无 | 远端配置变更消息触发本地 listener 失败次数 |
| `pisces_config_change_broadcast_last_published_epoch_seconds` | 无 | 最近一次成功发送配置变更广播时间 |
| `pisces_config_change_broadcast_last_received_epoch_seconds` | 无 | 最近一次成功处理远端配置变更广播时间 |

建议至少配置四类审批告警：`delivery_count{status="DEAD"} > 0`、`notification_count{status="UNDELIVERED"}` 持续大于 0、`metrics_refresh_healthy == 0`、期望外部投递时 `dispatcher_enabled == 0` 或 `dispatcher_targets == 0`。分流平面建议额外监控 `pisces_traffic_assignment_requests_total{result="ERROR"}` 增长、`pisces_traffic_cache_events_total{result="ERROR"}` 增长、`pisces_traffic_assignment_latency_seconds` P95/P99，以及 `pisces_config_change_broadcast_published_total{result="ERROR"}`、`pisces_config_change_broadcast_received_total{result="INVALID"}` 和 `pisces_config_change_broadcast_listener_errors_total`。

仓库内已提供可直接接入的生产监控模板：

- Prometheus 告警规则：`docs/observability/prometheus/pisces-approval-escalation-alerts.yml`
- 运行时平面告警规则：`docs/observability/prometheus/pisces-runtime-plane-alerts.yml`
- Grafana 仪表盘：`docs/observability/grafana/pisces-approval-escalation-dashboard.json`
- 运行时平面 Grafana 仪表盘：`docs/observability/grafana/pisces-runtime-plane-dashboard.json`
- 告警响应 Runbook：`docs/observability/approval-escalation-runbook.md`
- 运行时平面 Runbook：`docs/observability/runtime-plane-runbook.md`
- SDK 指标接入示例：`docs/observability/sdk-metrics-integration.md`
- Runtime 配置契约矩阵：`docs/operations/runtime-config-contract-matrix.md`
- 多实例发布演练：`docs/operations/runtime-plane-release-drill.md`
- 发布检查清单：`docs/operations/runtime-plane-release-checklist.md`
- 发布包检查：`docs/operations/runtime-plane-release-package-check.md`
- 预发演练记录模板：`docs/operations/runtime-plane-preprod-drill-record-template.md`
- 发布证据归档：`docs/operations/runtime-plane-release-evidence-archive.md`
- 发布后 SLO 回看：`docs/operations/runtime-plane-post-release-slo-review.md`
- 实验影响面抽样：`docs/operations/runtime-plane-experiment-impact-sampling.md`
- 分批发布决策：`docs/operations/runtime-plane-staged-rollout-decision.md`
- 回滚决策演练模板：`docs/operations/runtime-plane-rollback-decision-drill-template.md`
- 发布后异常复盘模板：`docs/operations/runtime-plane-post-release-incident-review-template.md`
- 事件管道重放审计：`docs/operations/event-pipeline-replay-audit.md`
- 容量基线：`docs/operations/runtime-plane-capacity-baseline.md`
- 容量基线归档：`docs/operations/runtime-plane-baseline-archive.md`
- Redis 故障注入：`docs/operations/runtime-plane-redis-fault-injection.md`
- 演练脚本：`scripts/runtime-plane-release-drill.sh`
- 发布包检查脚本：`scripts/runtime-plane-release-package-check.sh`
- 发布证据归档脚本：`scripts/runtime-plane-release-evidence-archive.sh`
- 发布后 SLO 回看脚本：`scripts/runtime-plane-post-release-slo-review.sh`
- 实验影响面抽样脚本：`scripts/runtime-plane-experiment-impact-sampling.sh`
- 分批发布决策脚本：`scripts/runtime-plane-staged-rollout-decision.sh`
- 事件管道重放审计脚本：`scripts/event-pipeline-replay-audit.sh`
- 发布包检查 CI：`.github/workflows/runtime-plane-release-package.yml`
- 容量基线脚本：`scripts/runtime-plane-capacity-baseline.sh`
- 容量基线归档脚本：`scripts/runtime-plane-archive-baseline.sh`
- Redis 故障注入脚本：`scripts/runtime-plane-redis-fault-injection.sh`
- 接入说明：`docs/observability/README.md`

## 实验配置要求

创建实验时，至少需要明确：

- 实验名称、开始时间、结束时间。
- 实验组及各组流量比例。
- 流量策略和总流量。
- `eventDefinitions`：实验级事件定义，事件 key 使用大写英文、数字和下划线。
- `metricDefinitions`：实验级指标定义，指标引用的事件必须已在 `eventDefinitions` 中声明。

可选配置：

- `groupConfigSchema`：定义各实验组配置字段。
- 实验组 `config`：为每个实验组填写具体配置值。
- 白名单、黑名单、规则分流条件。

## Java 后端接入

适用于服务端业务在接口、任务或网关层做实验分流。

```java
import com.pisces.sdk.PiscesClient;

import java.util.Map;

PiscesClient client = PiscesClient.builder()
        .baseUrl("http://localhost:9990/api")
        .timeoutMillis(30000)
        .defaultHeader("X-Pisces-Api-Key", "<runtime-key>")
        .maxRetries(2)
        .retryInitialBackoffMillis(100)
        .retryMaxBackoffMillis(1000)
        .retryBackoffJitterRatio(0.2D)
        .experimentCacheTtlMillis(60000)
        .configVersionLongPollMillis(25000)
        .allowStaleExperimentConfig(true)
        .build();

String experimentId = "exp_price_001";
String visitorId = "visitor_001";

String groupId = client.assignGroup(experimentId, visitorId, Map.of("city", "shanghai"));
if (groupId == null) {
    return;
}

Map<String, Object> groupConfig = client.getGroupConfig(experimentId, visitorId);
client.reportExposure(experimentId, visitorId, Map.of("scene", "detail"));
client.reportEventByKey(experimentId, visitorId, "PAY_SUCCESS", Map.of(
        "clientEventId", "order_001_PAY_SUCCESS",
        "orderId", "order_001"
));
```

接入规则：

- `visitorId` 必须稳定。可以使用登录用户 ID、设备 ID 或服务端生成的匿名访客 ID。
- 同一实验中，同一 `visitorId` 应复用同一个分流结果。
- 上报关键行为时优先使用实验定义的事件 key，例如 `PAY_SUCCESS`。
- 事件 `properties.clientEventId` 用于客户端幂等去重，生产接入时应由业务侧生成稳定值。
- 同一实验内重复上报相同 `clientEventId` 会被服务端视为成功重试，不会重复写入事件或重复计数。

## JavaScript 前端接入

适用于 Web 页面直接做前端实验。

```javascript
const pisces = new PiscesSDK({
  apiBaseUrl: 'http://localhost:9990/api',
  experimentId: 'exp_price_001',
  visitorId: PiscesSDK.getOrCreateVisitorId(),
  headers: {
    'X-Pisces-Api-Key': '<runtime-key>'
  },
  maxRetries: 2,
  retryInitialBackoffMillis: 100,
  retryMaxBackoffMillis: 1000,
  retryBackoffJitterRatio: 0.2,
  experimentCacheTtl: 60000,
  configVersionLongPollMillis: 25000,
  allowStaleExperimentConfig: true
})

const groupId = await pisces.assignGroup({ city: 'shanghai' })
if (!groupId) {
  return
}

const groupConfig = await pisces.getGroupConfig()
await pisces.reportExposure({ scene: 'detail' })
await pisces.reportEventByKey('PAY_SUCCESS', {
  clientEventId: 'order_001_PAY_SUCCESS',
  orderId: 'order_001'
})
```

接入规则：

- 匿名场景使用 `PiscesSDK.getOrCreateVisitorId()` 保持浏览器内访客 ID 稳定。
- 页面展示实验内容后立即上报曝光。
- 用户完成目标行为后上报实验定义事件。
- 同一实验内相同 `clientEventId` 可安全重试，不会重复计入指标。
- 若页面需要实验组配置，使用 `getGroupConfig()` 读取当前命中组的 `config`。

SDK 配置快照默认缓存 60 秒。TTL 过期后会先检查 `/runtime/experiments/{id}/config/version`，配置未变化时只续期快照；配置了 `configVersionLongPollMillis` 后，服务端会在版本未变化时最多等待 30 秒，并在配置变更序列推进时提前返回。多实例部署开启 Redis 配置变更广播后，非写入实例也能提前推进本地序列，减少 SDK 长轮询等待到超时。runtime 配置响应中的事件定义、指标定义、实验组配置 schema、实验组 map 和组内 config 缺失时返回空集合或空 map。开启 stale fallback 时，如果 trace 分流返回了更新的 `configVersion` 但新配置拉取失败，SDK 只有在旧快照仍包含当前命中组配置时才会回退使用旧配置。

Java SDK 和 JS SDK 默认 `maxRetries=0`，生产接入可按业务延迟预算显式开启 `1` 到 `2` 次重试。SDK 只重试网络异常、超时、空响应、HTTP `408` / `429` / `5xx` 和业务响应码 `408` / `429` / `5xx`，并通过 `retryInitialBackoffMillis`、`retryMaxBackoffMillis`、`retryBackoffJitterRatio` 做指数退避和抖动。两端都提供 `getMetricsSnapshot()` 和 `resetMetrics()`，可由业务侧周期性上报本地请求尝试、成功、失败、重试、stale fallback、配置缓存命中/未命中和版本检查次数。Java Micrometer 与 JS Prometheus text 接入样例见 `docs/observability/sdk-metrics-integration.md`。

多实例发布前可运行 runtime drill，验证版本收敛、热路径分流和运行时指标：

```bash
bash scripts/runtime-plane-release-package-check.sh
```

发布包检查会输出 `target/pisces-runtime-release-package-check/report.json`。CI 中应归档 `runtime-plane-release-package-report` artifact，并使用 `docs/operations/runtime-plane-preprod-drill-record-template.md` 记录预发演练证据。

预发演练完成后，使用 `scripts/runtime-plane-release-evidence-archive.sh` 生成发布批次 `manifest.json`，必要时通过 `PISCES_RELEASE_BATCH_COMPARE_MANIFEST_FILE` 与上一版或期望 manifest 比对。

生产发布观察窗口结束后，使用 `scripts/runtime-plane-post-release-slo-review.sh` 读取发布证据 manifest 和发布后指标快照，生成 `target/pisces-runtime-post-release-slo-review/summary.json`。摘要必须 `status=PASS` 才能关闭发布观察。

随后对本次涉及实验执行 `scripts/runtime-plane-experiment-impact-sampling.sh`，生成 `target/pisces-runtime-experiment-impact-sampling/summary.json`。如果 SLO 回看或影响面抽样不是 `status=PASS`，使用 `docs/operations/runtime-plane-post-release-incident-review-template.md` 建立异常复盘记录。

分批发布每个阶段执行 `scripts/runtime-plane-staged-rollout-decision.sh`，读取发布证据、SLO 回看、影响面抽样和人工准入记录，生成 `target/pisces-runtime-staged-rollout-decision/summary.json`。只有 `decision=PROCEED` 才能进入下一阶段；`HOLD` 不推进流量，`ROLLBACK` 进入回滚和复盘。

事件管道出现事实已落库但 Redis 派生计数或 MAB 奖励不一致时，使用 `scripts/event-pipeline-replay-audit.sh` 先做只读审计。若只缺派生物化账本，可设置 `PISCES_EVENT_REPLAY_REPAIR_MATERIALIZATION=true` 走受控修复并在修复后复查计划覆盖；确认需要直接重建派生数据后再设置 `PISCES_EVENT_REPLAY_EXECUTE=true`，脚本会提交异步 replay job 并轮询终态。脚本会生成 `target/pisces-event-pipeline-replay-audit/summary.json` 作为审计证据。

```bash
PISCES_INSTANCE_URLS="http://localhost:9990/api,http://localhost:9991/api" \
PISCES_EXPERIMENT_ID="exp_price_001" \
PISCES_RUNTIME_API_KEY="runtime-key" \
PISCES_MANAGEMENT_API_KEY="ops-key" \
PISCES_RELEASE_ACTION="publish-current" \
bash scripts/runtime-plane-release-drill.sh
```

## 数据链路检查

真实实验至少需要观察四类数据：

| 数据 | 来源 | 用途 |
| --- | --- | --- |
| Assignment | `/traffic/assign` | 判断访客是否进入实验，以及进入哪个实验组 |
| Exposure | `/data/exposure` | 判断实验内容是否真的展示给访客 |
| Event | `/data/event` | 计算实验指标 |
| Statistics | `/analysis/experiment/{id}/statistics` | 查看聚合结果和数据质量 |

如果统计结果没有就绪，优先按以下顺序排查：

1. 实验是否为 `RUNNING` 状态。
2. `visitorId` 是否稳定。
3. 访客是否进入总流量范围。
4. 是否有曝光上报。
5. 事件 key 是否与实验 `eventDefinitions` 一致。
6. 指标引用的事件是否已经产生。

## 决策规则

- 数据质量门禁不通过时，不应推进毕业结论。
- AI 诊断和毕业响应的 `evidence` 是人工确认结论时的审计入口，重点查看 `analysisReady`、`blockingIssues`、SRM、样本量、主指标事实和 `latestReportSnapshotVersion`。
- AI 诊断和毕业决策只作为建议，不自动修改实验状态或流量。
- 人工结论应基于统计结果、数据质量、报告快照和 AI 建议共同确认；推进到 `READY_FOR_REVIEW`、`GRADUATED` 或 `REJECTED` 时，请求需带当前 `expectedConfigVersion` 与最新 `reportSnapshotVersion`，服务端会拒绝过期配置版本或过期报告快照。
