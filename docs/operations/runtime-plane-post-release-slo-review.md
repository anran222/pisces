# Runtime Plane Post-Release SLO Review

本文档用于发布后回看运行时分流平面是否满足 SLO。它不替代发布前演练，而是在发布完成后的观察窗口内，把 Prometheus、SDK 本地指标和发布证据 manifest 汇总为一份自动化验收摘要。

## 输入文件

脚本需要两个输入：

1. 发布证据 manifest：由 `scripts/runtime-plane-release-evidence-archive.sh` 生成。
2. 发布后指标快照 JSON：可参考 `docs/operations/runtime-plane-post-release-slo-sample.json`。

指标快照字段：

| 字段 | 来源 | 说明 |
| --- | --- | --- |
| `assignment.errorRate` | `pisces_traffic_assignment_requests_total` | 发布后观察窗口内分流错误率 |
| `assignment.p95Ms` / `assignment.p99Ms` | `pisces_traffic_assignment_latency_seconds` | 发布后分流 P95/P99 延迟 |
| `cache.errorDelta` | `pisces_traffic_cache_events_total{result="ERROR"}` | 观察窗口内 Redis 缓存错误增量 |
| `broadcast.publishErrorDelta` | `pisces_config_change_broadcast_published_total{result="ERROR"}` | 配置广播发送失败增量 |
| `broadcast.invalidDelta` | `pisces_config_change_broadcast_received_total{result="INVALID"}` | 配置广播非法消息增量 |
| `broadcast.listenerErrorDelta` | `pisces_config_change_broadcast_listener_errors_total` | listener 失败增量 |
| `sdk.requestFailureDelta` | SDK `getMetricsSnapshot()` | 业务侧 SDK 请求失败增量 |
| `sdk.staleFallbackDelta` | SDK `getMetricsSnapshot()` | stale fallback 增量 |

## 执行

```bash
PISCES_RELEASE_EVIDENCE_MANIFEST_FILE="target/pisces-runtime-release-evidence-archive/<release>/manifest.json" \
PISCES_POST_RELEASE_METRICS_FILE="docs/operations/runtime-plane-post-release-slo-sample.json" \
bash scripts/runtime-plane-post-release-slo-review.sh
```

默认输出：

```text
target/pisces-runtime-post-release-slo-review/summary.json
```

## 阈值

默认门禁：

- 分流错误率 `assignment.errorRate <= 0`
- Redis 缓存错误增量 `cache.errorDelta <= 0`
- 配置广播发送失败、非法消息、listener 失败增量均为 `0`
- SDK 请求失败和 stale fallback 增量均为 `0`
- 如果发布证据 manifest 包含容量基线，则发布后 P95/P99 不超过基线的 `1.2` 倍
- 如果没有容量基线，需要显式设置 `PISCES_POST_RELEASE_MAX_P95_MS` 和 `PISCES_POST_RELEASE_MAX_P99_MS`

可覆盖阈值：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `PISCES_POST_RELEASE_MAX_ASSIGNMENT_ERROR_RATE` | `0` | 最大分流错误率 |
| `PISCES_POST_RELEASE_MAX_CACHE_ERROR_DELTA` | `0` | 最大缓存错误增量 |
| `PISCES_POST_RELEASE_MAX_BROADCAST_ERROR_DELTA` | `0` | 最大广播发送失败增量 |
| `PISCES_POST_RELEASE_MAX_BROADCAST_INVALID_DELTA` | `0` | 最大广播非法消息增量 |
| `PISCES_POST_RELEASE_MAX_LISTENER_ERROR_DELTA` | `0` | 最大 listener 错误增量 |
| `PISCES_POST_RELEASE_MAX_SDK_FAILURE_DELTA` | `0` | 最大 SDK 请求失败增量 |
| `PISCES_POST_RELEASE_MAX_SDK_STALE_FALLBACK_DELTA` | `0` | 最大 SDK stale fallback 增量 |
| `PISCES_POST_RELEASE_MAX_P95_MS` | 空 | P95 绝对阈值 |
| `PISCES_POST_RELEASE_MAX_P99_MS` | 空 | P99 绝对阈值 |
| `PISCES_POST_RELEASE_MAX_P95_BASELINE_RATIO` | `1.2` | P95 相对容量基线阈值 |
| `PISCES_POST_RELEASE_MAX_P99_BASELINE_RATIO` | `1.2` | P99 相对容量基线阈值 |

## 发布记录

把 `summary.json` 追加到预发/发布记录的 Evidence Archive 区块，并在变更单中记录：

- 观察窗口开始和结束时间。
- SLO review `status`。
- 失败 gate 的实际值和阈值。
- 如果 `status != PASS`，必须进入回滚或止血判断，不应直接关闭发布观察。
