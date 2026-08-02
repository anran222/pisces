# Runtime Plane Runbook

本 Runbook 用于处理 Pisces 运行时分流和配置变更广播告警。目标是确认 SDK 分流、Redis 缓存降级和多实例配置刷新是否仍在可控范围内。

## Scope

覆盖对象：

- 分流接口：`/api/traffic/assign`、`/api/traffic/assign/trace`
- 运行时配置接口：`/api/runtime/experiments/{id}/config`、`/api/runtime/experiments/{id}/config/version`
- Redis 分流缓存和配置变更 Pub/Sub
- Actuator 指标：`/api/actuator/prometheus`

不覆盖对象：

- 管理端审批是否应该通过。
- SDK 所在业务系统的本地监控。
- Prometheus、Grafana、Alertmanager 平台自身故障。

## Alert Response

| Alert | Severity | First Action | Recovery Target |
| --- | --- | --- | --- |
| `PiscesRuntimeTrafficAssignmentErrors` | critical | 查看运行时 API 日志和实验访问权限 | `assignment_requests_total{result="ERROR"}` 不再增长 |
| `PiscesRuntimeTrafficCacheErrors` | warning | 检查 Redis 连通性和慢请求 | 缓存错误不再增长，分流 P95/P99 恢复 |
| `PiscesConfigChangeBroadcastPublishErrors` | warning | 检查 Redis Pub/Sub 连接和 channel 配置 | `published_total{result="ERROR"}` 不再增长 |
| `PiscesConfigChangeBroadcastInvalidMessages` | warning | 检查 channel 是否被其他环境共用 | 非法消息不再增长 |
| `PiscesConfigChangeBroadcastListenerErrors` | warning | 查看本地 listener 失败日志 | listener 错误不再增长 |

## Triage

### 1. Confirm Metrics

```bash
curl "http://localhost:9990/api/actuator/prometheus" | grep -E "pisces_traffic|pisces_config_change_broadcast"
```

需要看到：

- `pisces_traffic_assignment_requests_total`
- `pisces_traffic_assignment_latency_seconds`
- `pisces_traffic_cache_events_total`
- `pisces_config_change_broadcast_enabled`
- `pisces_config_change_broadcast_published_total`
- `pisces_config_change_broadcast_received_total`

如果本地接口有指标但 Prometheus 没有数据，优先排查 scrape 配置、网络和服务发现。

业务侧如已接入 Java SDK 或 JS SDK 的 `getMetricsSnapshot()`，同步查看 SDK 本地请求尝试、失败、重试、stale fallback 和配置缓存命中/未命中计数，判断故障发生在 SDK 到 Pisces 的链路、Pisces 服务端，还是本地缓存降级路径。

### 2. Check Config Broadcast

检查运行配置：

```bash
echo "$PISCES_CONFIG_CHANGE_REDIS_BROADCAST_ENABLED"
echo "$PISCES_CONFIG_CHANGE_REDIS_CHANNEL"
```

多实例生产环境建议启用 `PISCES_CONFIG_CHANGE_REDIS_BROADCAST_ENABLED=true`，并为不同环境使用不同的 `PISCES_CONFIG_CHANGE_REDIS_CHANNEL`。

### 3. Check Runtime API

```bash
curl "http://localhost:9990/api/runtime/experiments/<experimentId>/config/version?knownVersion=<version>&waitMillis=1000" \
  -H "X-Pisces-Api-Key: <runtime-key>"

curl -X POST "http://localhost:9990/api/traffic/assign/trace" \
  -H "X-Pisces-Api-Key: <runtime-key>" \
  -H "Content-Type: application/json" \
  -d '{"experimentId":"<experimentId>","visitorId":"debug-visitor"}'
```

重点确认：

- runtime key 是否绑定实验所在 `appId`。
- 返回的 `configVersion` 是否符合最新发布配置。
- `reason` 是否为预期的 `CACHE_HIT`、`ALLOCATED` 或业务阻断原因。
- SDK `requestFailureCount`、`retryCount`、`staleExperimentConfigFallbackCount` 是否持续增长。

## Recovery

### Broadcast Publish Errors

处理顺序：

1. 确认 Redis 可连接且应用实例使用同一 Redis 集群。
2. 确认 channel 没有拼错，且不同环境没有共用 channel。
3. 观察 `pisces_config_change_broadcast_published_total{result="SUCCESS"}` 是否恢复增长。
4. 在恢复前，SDK 仍会依赖 TTL、版本检查和 bounded long-poll 超时刷新，不应直接关闭 runtime 服务。

### Invalid Broadcast Messages

处理顺序：

1. 确认 Redis channel 是否被其他项目或旧版本 Pisces 共用。
2. 确认所有实例均已升级到兼容的广播载荷格式。
3. 如需临时隔离，修改 `PISCES_CONFIG_CHANGE_REDIS_CHANNEL` 并滚动重启。

### Traffic Cache Errors

处理顺序：

1. 检查 Redis 连接数、慢查询、网络抖动和超时配置。
2. 观察 `pisces_traffic_assignment_latency_seconds` P95/P99 是否升高。
3. 若 Redis 短时不可用，分流会退回当前配置计算并保留数据库分流事实；恢复 Redis 后观察缓存错误是否停止增长。
4. 恢复后执行 `scripts/runtime-plane-redis-fault-injection.sh` 或 `docs/operations/runtime-plane-redis-fault-injection.md` 中的等价流程，确认降级和恢复路径可重复。

### Traffic Assignment Errors

处理顺序：

1. 查看 API 日志中的业务异常或鉴权异常。
2. 确认实验存在、状态为 `RUNNING`，且 API Key 可访问同一 `appId`。
3. 确认配置中 `traffic`、`groups` 和规则分流字段完整。

## Drill Checklist

发布运行时平面变更后，至少完成一次演练：

- 开启 `PISCES_CONFIG_CHANGE_REDIS_BROADCAST_ENABLED=true`，部署两个应用实例连接同一 Redis。
- 执行 `scripts/runtime-plane-release-package-check.sh`，确认发布包包含 runtime 契约测试、SDK、脚本、监控资产和文档入口。
- 确认 CI artifact `runtime-plane-release-package-report` 已归档，并基于 `docs/operations/runtime-plane-preprod-drill-record-template.md` 记录预发演练证据。
- 使用 `scripts/runtime-plane-release-evidence-archive.sh` 归档发布证据，必要时比对上一版或期望发布批次 manifest。
- 按 `docs/operations/runtime-plane-release-checklist.md` 完成发布前检查。
- 使用 `scripts/runtime-plane-release-drill.sh` 在只读模式验证配置读取、`assign/trace` 热路径和 Prometheus 指标。
- 如本次包含配置发布，使用 `PISCES_RELEASE_ACTION=publish-current` 验证所有实例收敛到目标 `configVersion`。
- 使用 `scripts/runtime-plane-capacity-baseline.sh` 建立当前热路径容量基线。
- 使用 `scripts/runtime-plane-archive-baseline.sh` 归档容量基线结果。
- 使用 `scripts/runtime-plane-redis-fault-injection.sh` 验证 Redis 故障期间分流不会整体失败。
- 检查 `pisces_config_change_broadcast_published_total{result="SUCCESS"}` 和 `pisces_config_change_broadcast_received_total{result="APPLIED"}` 增长。
- 临时停止 Redis 或指向不可达 Redis，确认 `pisces_traffic_cache_events_total{result="ERROR"}` 增长且分流仍可基于当前配置计算。
- 恢复 Redis 后确认缓存错误不再增长，分流延迟回落。
- 发布后观察窗口结束时，使用 `scripts/runtime-plane-post-release-slo-review.sh` 生成 SLO 回看摘要。
- 对本次涉及的实验使用 `scripts/runtime-plane-experiment-impact-sampling.sh` 生成实验级影响面抽样摘要。
- 使用 `scripts/runtime-plane-staged-rollout-decision.sh` 生成当前阶段 `PROCEED`、`HOLD` 或 `ROLLBACK` 决策。
- 如果 SLO 回看或影响面抽样失败，按 `docs/operations/runtime-plane-post-release-incident-review-template.md` 建立异常复盘记录。

## Close Criteria

告警关闭前必须满足：

- `pisces_traffic_assignment_requests_total{result="ERROR"}` 不再增长。
- `pisces_traffic_cache_events_total{result="ERROR"}` 不再增长。
- `pisces_config_change_broadcast_published_total{result="ERROR"}` 不再增长。
- `pisces_config_change_broadcast_received_total{result="INVALID"}` 不再增长。
- 多实例生产环境中，`pisces_config_change_broadcast_enabled == 1`。
- 发布后 SLO 回看摘要 `status=PASS`。
- 涉及实验的影响面抽样摘要 `status=PASS`。
- 分批发布决策摘要 `decision=PROCEED`。
