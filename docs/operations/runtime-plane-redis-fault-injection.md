# Runtime Plane Redis Fault Injection

本文档用于验证 Redis 短时不可用时，Pisces 运行时分流能退回当前配置直接计算，并在 Redis 恢复后回到正常缓存路径。

## 故障模式

脚本支持三种模式：

| 模式 | 说明 |
| --- | --- |
| `manual` | 只提示并等待，Redis 故障由操作者手动注入，默认模式 |
| `docker-pause` | 对 Redis Docker 容器执行 `docker pause` / `docker unpause` |
| `docker-stop` | 对 Redis Docker 容器执行 `docker stop` / `docker start` |

`docker-pause` 和 `docker-stop` 会改变本机容器状态，必须显式设置 `PISCES_FAULT_CONFIRM=true`。

## 手工模式

适用于 Redis 不在本机 Docker 中，或需要通过云控制台、网络 ACL 注入故障：

```bash
PISCES_INSTANCE_URLS="http://localhost:9990/api,http://localhost:9991/api" \
PISCES_EXPERIMENT_ID="exp_price_001" \
PISCES_RUNTIME_API_KEY="runtime-key" \
PISCES_REDIS_FAULT_MODE="manual" \
PISCES_FAULT_MANUAL_GRACE_SECONDS=15 \
PISCES_FAULT_DURATION_SECONDS=60 \
bash scripts/runtime-plane-redis-fault-injection.sh
```

脚本会依次执行：

1. baseline 阶段：Redis 正常时运行一次只读 runtime drill。
2. during-fault 阶段：提示操作者注入 Redis 故障后继续压测。
3. recovery 阶段：Redis 恢复后再次压测。

## Docker 模式

本地 Docker Redis 可直接自动注入：

```bash
PISCES_INSTANCE_URLS="http://localhost:9990/api,http://localhost:9991/api" \
PISCES_EXPERIMENT_ID="exp_price_001" \
PISCES_RUNTIME_API_KEY="runtime-key" \
PISCES_REDIS_FAULT_MODE="docker-pause" \
PISCES_REDIS_DOCKER_CONTAINER="pisces-redis" \
PISCES_FAULT_CONFIRM=true \
PISCES_FAULT_DURATION_SECONDS=30 \
bash scripts/runtime-plane-redis-fault-injection.sh
```

脚本注册了退出恢复逻辑。即使 during-fault 阶段失败，也会尝试恢复容器状态。

## 成功标准

- baseline 阶段 `failed=0`，分流延迟符合容量基线。
- during-fault 阶段允许 `pisces_traffic_cache_events_total{result="ERROR"}` 增长，但 `/traffic/assign/trace` 不应整体失败。
- during-fault 阶段返回的 `reason`、`source` 和 `configVersion` 仍可解释。
- recovery 阶段 `Cache Error Rate` 不再增长，P95/P99 回到基线附近。
- SDK 本地 `staleExperimentConfigFallbackCount` 不应持续增长；若增长，说明业务侧已经依赖旧配置快照，需要排查 runtime 配置接口或网络。

## 演练后记录

每次演练至少记录：

- 实验 ID、实例列表、Redis fault mode 和持续时间。
- baseline / during-fault / recovery 三阶段的 `Assignment load summary`。
- Grafana 中 Assignment Error Rate、Cache Error Rate、Assignment Latency 的截图或导出数据。
- 如果演练覆盖事件 replay、补物化修复或 MAB 奖励恢复，附带 `event-pipeline-replay-audit.sh` summary；大窗口修复需要包含 `PISCES_EVENT_REPLAY_SEGMENT_COUNT`、`repairSegmentIndex` 和修复前后分段缺账本计数。
- 如果发生失败，记录对应 API 日志、Redis 日志和恢复动作。
