# Runtime Plane Capacity Baseline

本文档用于建立 Pisces 运行时分流平面的容量基线。基线对象是 `/api/traffic/assign/trace` 热路径，覆盖吞吐、错误率、P50/P95/P99 延迟、命中来源和配置版本分布。

## 运行脚本

默认阶梯为 `100:8,500:16,1000:32`，含义是 `请求数:并发数`：

```bash
PISCES_INSTANCE_URLS="http://localhost:9990/api,http://localhost:9991/api" \
PISCES_EXPERIMENT_ID="exp_price_001" \
PISCES_RUNTIME_API_KEY="runtime-key" \
bash scripts/runtime-plane-capacity-baseline.sh
```

输出默认写入 `target/pisces-runtime-capacity-baseline-<timestamp>.jsonl`。每一行是一档阶梯的 JSON 摘要，便于提交到内部性能平台或与历史基线做 diff。

生成后按 `docs/operations/runtime-plane-baseline-archive.md` 归档，保留 manifest 作为发布证据。

## 推荐生产前基线

预发环境至少跑三档：

```bash
PISCES_CAPACITY_STEPS="1000:32,5000:64,10000:128" \
PISCES_CAPACITY_MAX_ERROR_RATE=0 \
PISCES_CAPACITY_MAX_P95_MS=200 \
PISCES_EXPERIMENT_ID="exp_price_001" \
bash scripts/runtime-plane-capacity-baseline.sh
```

如果 Redis 缓存命中率较低，先跑一次较小流量预热，再执行正式基线。正式基线过程中同步观察 Grafana 的 `Pisces Runtime Plane` 仪表盘。

## 结果字段

| 字段 | 含义 |
| --- | --- |
| `requests` / `concurrency` | 本档请求数与并发数 |
| `total` / `ok` / `failed` | 实际完成、成功和失败请求数 |
| `errorRate` | 失败请求占比 |
| `latencyMs.p50/p95/p99` | 成功请求延迟 |
| `byInstance` | 多实例流量分布 |
| `versions` | 返回的 `configVersion` 分布 |
| `sources` / `reasons` | 分流来源和命中原因分布 |

## 验收标准

- `errorRate` 不高于配置的 `PISCES_CAPACITY_MAX_ERROR_RATE`。
- `latencyMs.p95` 不高于配置的 `PISCES_CAPACITY_MAX_P95_MS`。
- `versions` 在没有发布动作时应集中在当前 runtime 版本。
- `byInstance` 应与负载入口策略一致，不能有实例完全无流量或集中失败。
- Grafana 中 `Assignment Error Rate` 和 `Cache Error Rate` 不持续增长。

## 何时重建基线

- 修改 `TrafficService`、运行时配置接口、Redis 缓存键或 SDK 分流调用方式后。
- 修改 API Key 鉴权、应用隔离或配置发布广播后。
- Redis、JDK、连接池、容器规格或实例数发生变化后。
- 生产发布前需要证明运行时平面容量没有回退时。
