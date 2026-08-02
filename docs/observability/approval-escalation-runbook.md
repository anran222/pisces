# Approval Escalation Runbook

本 Runbook 用于处理 Pisces 审批升级告警链路的 Prometheus 告警。目标是确认逾期审批是否被可靠发现、投递、重试、确认和关闭。

## Scope

覆盖对象：

- 审批升级 outbox：`pisces_experiment_approval_escalation`
- 通道投递回执：`pisces_experiment_approval_escalation_delivery`
- Actuator 指标：`/api/actuator/prometheus`
- 管理 API：`/api/experiments/approval-escalations/**`
- 管理台应用页审批升级 outbox 区域

不覆盖对象：

- 业务审批是否应该通过。
- Webhook 下游系统自己的通知策略。
- Prometheus、Grafana、Alertmanager 平台自身故障。

## Alert Response

| Alert | Severity | First Action | Recovery Target |
| --- | --- | --- | --- |
| `PiscesApprovalEscalationDeadDelivery` | critical | 查看死信通道、最近错误和 webhook 配置 | 死信通道重投成功或明确关闭对应告警 |
| `PiscesApprovalEscalationUndeliveredBacklog` | warning | 判断未送达是否在增长，确认 dispatcher 是否启用 | 未送达数量回到 0 或审批告警被确认/关闭 |
| `PiscesApprovalEscalationMetricsRefreshUnhealthy` | warning | 查看应用日志中“审批升级告警监控指标刷新失败” | `metrics_refresh_healthy` 回到 1 |
| `PiscesApprovalEscalationMetricsRefreshStale` | warning | 检查应用进程、调度线程和数据库连通性 | 刷新 age 小于 120 秒 |
| `PiscesApprovalEscalationMetricsMissing` | critical | 检查 Prometheus scrape 和 `/api/actuator/prometheus` | Prometheus 能抓到 `pisces_approval_escalation_*` |
| `PiscesApprovalEscalationDispatcherEnabledWithoutTargets` | warning | 检查 webhook 环境变量 | dispatcher 目标数量大于 0 或关闭 dispatcher |

## Triage

### 1. Confirm Metrics

```bash
curl "http://localhost:9990/api/actuator/prometheus" | grep pisces_approval_escalation
```

需要看到：

- `pisces_approval_escalation_metrics_refresh_healthy`
- `pisces_approval_escalation_notification_count`
- `pisces_approval_escalation_delivery_count`
- `pisces_approval_escalation_dispatcher_enabled`
- `pisces_approval_escalation_dispatcher_targets`

如果本地接口有指标但 Prometheus 没有数据，优先排查 scrape 配置、网络、服务发现和 Prometheus relabel。

### 2. Check Status API

```bash
curl "http://localhost:9990/api/experiments/approval-escalations/status" \
  -H "X-Pisces-Api-Key: <management-or-admin-key>"
```

重点字段：

- `healthy`
- `overallStatus`
- `undeliveredNotificationCount`
- `undeliveredDeliveryCount`
- `dispatcherEnabled`
- `dispatcherTargetCount`
- `dispatcherChannels`
- `notificationStatusCounts`
- `deliveryStatusCounts`

### 3. List Active Escalations

```bash
curl "http://localhost:9990/api/experiments/approval-escalations?escalationStatus=OPEN" \
  -H "X-Pisces-Api-Key: <management-or-admin-key>"
```

逐条确认：

- `experimentId`
- `approvalType`
- `approvalSlaStatus`
- `escalationOwners`
- `notificationStatus`
- `notificationAttemptCount`
- `notificationNextAttemptAt`
- `notificationLastError`
- `notificationDeliveries`

如果 outbox 是 `DEAD`，继续看每个 `notificationDeliveries` 的 `channelName`、`notificationStatus`、`notificationAttemptCount` 和 `notificationLastError`。

## Recovery

### Dispatcher Enabled But No Targets

检查运行配置：

```bash
echo "$PISCES_APPROVAL_ESCALATION_DISPATCH_ENABLED"
echo "$PISCES_APPROVAL_ESCALATION_WEBHOOK_URL"
echo "$PISCES_APPROVAL_ESCALATION_WEBHOOK_URLS"
echo "$PISCES_APPROVAL_ESCALATION_WEBHOOK_CHANNEL_NAMES"
```

恢复方式：

- 如果生产需要外部投递，补齐 `PISCES_APPROVAL_ESCALATION_WEBHOOK_URL` 或 `PISCES_APPROVAL_ESCALATION_WEBHOOK_URLS` 后重启服务。
- 如果当前环境不需要外部投递，把 `PISCES_APPROVAL_ESCALATION_DISPATCH_ENABLED=false` 并移除该环境的 mandatory dispatcher 告警规则。

### Dead Delivery

先确认 webhook 下游已恢复，再执行单条重投：

```bash
curl -X POST "http://localhost:9990/api/experiments/approval-escalations/<escalationId>/notification/retry?operator=ops" \
  -H "X-Pisces-Api-Key: <management-or-admin-key>"
```

批量重投当前可见死信：

```bash
curl -X POST "http://localhost:9990/api/experiments/approval-escalations/dead/retry?operator=ops" \
  -H "X-Pisces-Api-Key: <management-or-admin-key>"
```

重投后观察：

- outbox `notificationStatus` 从 `DEAD` 变为 `RETRY`。
- 通道回执中当前启用的 `DEAD` 通道变为 `RETRY`。
- 下一轮调度后成功通道变为 `SENT`。

### Undelivered Backlog

处理顺序：

1. 确认 `dispatcherEnabled` 和 `dispatcherTargetCount`。
2. 检查 `notificationStatusCounts` 中是 `PENDING`、`DISPATCHING`、`RETRY` 还是 `DEAD`。
3. 如果长期停留 `DISPATCHING`，检查应用实例是否频繁重启，等待 `PISCES_APPROVAL_ESCALATION_DISPATCH_LOCK_MINUTES` 后是否重新领取。
4. 如果长期停留 `RETRY`，检查 `notificationNextAttemptAt` 是否还没到。
5. 如果进入 `DEAD`，按 Dead Delivery 流程处理。

### Metrics Refresh Failure

排查顺序：

1. 查看应用日志是否有“审批升级告警监控指标刷新失败”。
2. 检查 MySQL 连通性和 `pisces_experiment_approval_escalation` / `pisces_experiment_approval_escalation_delivery` 表是否存在。
3. 检查 `PISCES_APPROVAL_ESCALATION_METRICS_REFRESH_DELAY_MS` 是否被配置为过大。
4. 如果只有 Prometheus 无数据但本地 curl 正常，转向 scrape 配置排查。

## Drill Checklist

每次发布审批升级投递链路或监控配置后，至少完成一次演练：

- 手动构造一个逾期 `PENDING` 审批任务。
- 执行 `POST /api/experiments/approval-escalations/scan`，确认生成 `OPEN` outbox。
- 临时配置一个不可达 webhook，确认通道进入 `RETRY`，达到上限后进入 `DEAD`。
- 验证 Prometheus 触发 `PiscesApprovalEscalationDeadDelivery`。
- 修复 webhook 或切回可达 mock endpoint。
- 执行单条死信重投，确认通道最终进入 `SENT`。
- 确认 Grafana 中死信数量回到 0，未送达数量回到 0。
- 对业务审批执行通过或拒绝，确认同任务告警自动关闭为 `RESOLVED`。

## Close Criteria

告警关闭前必须满足：

- `pisces_approval_escalation_metrics_refresh_healthy == 1`
- `pisces_approval_escalation_notification_count{status="UNDELIVERED"} == 0`
- `pisces_approval_escalation_delivery_count{status="DEAD"} == 0`
- 需要外部投递的生产环境中，`dispatcher_enabled == 1` 且 `dispatcher_targets > 0`
- 管理台 outbox 中没有无人处理的 `OPEN` 死信告警

如果审批告警不再需要外部投递，应由审批负责人在管理台确认或等待审批最终通过/拒绝自动关闭，不应只静默 Alertmanager 告警。
