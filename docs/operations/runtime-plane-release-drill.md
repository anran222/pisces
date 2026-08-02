# Runtime Plane Release Drill

本文档用于在多实例部署下验证 Pisces 运行时分流平面。演练覆盖四件事：

1. 各实例能读取同一实验的 runtime 配置版本。
2. 发布配置后，各实例能在 bounded long-poll 和 Redis 配置变更广播下收敛到同一 `configVersion`。
3. `/traffic/assign/trace` 热路径在压测流量下持续返回可解释的分流结果。
4. Prometheus 能看到分流和配置广播指标。

正式发布时先按 `docs/operations/runtime-plane-release-checklist.md` 做发布前、中、后检查，再使用本文脚本执行验证。涉及热路径性能或 Redis 降级改动时，还需要执行 `docs/operations/runtime-plane-capacity-baseline.md` 和 `docs/operations/runtime-plane-redis-fault-injection.md`。

## 前置条件

至少准备两个 Pisces API 实例，并共享同一 MySQL、Redis 和 Zookeeper。多实例验证时建议开启 Redis 配置变更广播：

```bash
export PISCES_API_KEY_SPECS="runtime-key|shop-app|sdk|runtime,ops-key|shop-app|ops|management+analysis"
export PISCES_CONFIG_CHANGE_REDIS_BROADCAST_ENABLED=true
export PISCES_CONFIG_CHANGE_REDIS_CHANNEL=pisces:config-change
```

本地可用不同端口启动两个实例：

```bash
SERVER_PORT=9990 mvn -pl pisces-api spring-boot:run
SERVER_PORT=9991 mvn -pl pisces-api spring-boot:run
```

演练依赖一个已存在且 runtime key 可访问的实验，例如 `exp_price_001`。如果要执行发布动作，management key 也必须能访问该实验。

## 只读压测

默认 `observe` 模式不会修改实验配置，只会读取配置、发送分流请求并抓取运行时指标：

```bash
PISCES_INSTANCE_URLS="http://localhost:9990/api,http://localhost:9991/api" \
PISCES_EXPERIMENT_ID="exp_price_001" \
PISCES_RUNTIME_API_KEY="runtime-key" \
PISCES_ASSIGNMENT_REQUESTS=1000 \
PISCES_ASSIGNMENT_CONCURRENCY=32 \
bash scripts/runtime-plane-release-drill.sh
```

输出中应重点看：

- `Initial configVersion`：各实例初始版本是否一致。
- `Assignment load summary`：`failed=0`，且 `versions` 中的版本符合当前发布版本。
- `sources` / `reasons`：确认是否符合预期，例如缓存命中、首次分配、互斥阻断。
- Prometheus 样本：至少能看到 `pisces_traffic_*` 和 `pisces_config_change_broadcast_*` 指标。

## 发布当前配置并验证收敛

`publish-current` 会调用第一个实例的管理接口发布当前配置快照，然后轮询所有实例的 runtime version 接口，直到都收敛到发布返回的 `configVersion`：

```bash
PISCES_INSTANCE_URLS="http://localhost:9990/api,http://localhost:9991/api" \
PISCES_EXPERIMENT_ID="exp_price_001" \
PISCES_RUNTIME_API_KEY="runtime-key" \
PISCES_MANAGEMENT_API_KEY="ops-key" \
PISCES_RELEASE_ACTION="publish-current" \
PISCES_VERSION_WAIT_MILLIS=25000 \
PISCES_CONVERGENCE_TIMEOUT_SECONDS=60 \
bash scripts/runtime-plane-release-drill.sh
```

成功标准：

- 发布接口返回新的 `target configVersion`。
- 所有实例在超时前输出同一 `currentVersion`。
- 发布后压测阶段 `failed=0`。
- `pisces_config_change_broadcast_published_total{result="SUCCESS"}` 和远端实例的 `pisces_config_change_broadcast_received_total{result="APPLIED"}` 有增长。

## 保存草稿并发布

当需要验证真实配置变更时，可以准备完整的草稿请求体，然后使用 `save-draft-and-publish`：

```bash
PISCES_INSTANCE_URLS="http://localhost:9990/api,http://localhost:9991/api" \
PISCES_EXPERIMENT_ID="exp_price_001" \
PISCES_RUNTIME_API_KEY="runtime-key" \
PISCES_MANAGEMENT_API_KEY="ops-key" \
PISCES_RELEASE_ACTION="save-draft-and-publish" \
PISCES_DRAFT_PAYLOAD_FILE="/tmp/pisces-exp-price-draft.json" \
bash scripts/runtime-plane-release-drill.sh
```

`PISCES_DRAFT_PAYLOAD_FILE` 内容与 `PUT /api/experiments/{id}/config-draft` 一致，需包含实验配置主体字段、`operator` 和 `comment`。如果应用启用了配置发布审批，先按审批流程完成审批，再运行发布演练。

## 故障判定

- 版本长时间不收敛：优先检查 Redis 配置变更广播开关、频道是否一致，以及目标实例是否能连接 Redis。
- 分流失败增长：检查实验状态、runtime key scope、应用隔离、Redis/Zookeeper/MySQL 连接和 `TrafficService` 日志。
- 指标缺失：检查 `/api/actuator/prometheus` 是否暴露，Prometheus scrape path 是否包含 context path。
- 版本已收敛但 SDK 仍使用旧配置：检查 SDK `configVersionLongPollMillis`、本地缓存 TTL 和 stale fallback 指标。

进一步排查流程见 `docs/observability/runtime-plane-runbook.md`。
