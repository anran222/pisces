# SDK Metrics Integration

Java SDK 和 JS SDK 都提供 `getMetricsSnapshot()` / `resetMetrics()`。这些指标是业务进程本地视角，用于回答三个问题：

- SDK 到 Pisces 的请求是否失败或重试。
- 配置缓存是否命中，是否频繁 miss。
- 是否发生 stale fallback，业务是否正在依赖最后一次成功配置快照。

## 指标字段

| 字段 | 含义 |
| --- | --- |
| `requestAttemptCount` | SDK 发出的请求尝试次数，包含重试 |
| `requestSuccessCount` | 请求成功次数 |
| `requestFailureCount` | 单次尝试失败次数 |
| `retryCount` | 已执行的重试次数 |
| `staleExperimentConfigFallbackCount` | 配置刷新失败后使用旧快照次数 |
| `experimentCacheHitCount` | 实验配置本地缓存命中次数 |
| `experimentCacheMissCount` | 实验配置本地缓存未命中次数 |
| `experimentVersionCheckCount` | runtime version 检查次数 |

这些字段不要打上 `experimentId`、`visitorId` 这类高基数标签。建议只按 `app`、`service`、`sdk`、`instance` 维度聚合。

## Java Micrometer 示例

如果业务侧使用 Spring Boot Actuator，可以把 SDK snapshot 暴露为本进程 gauge。使用 gauge 时不要定时调用 `resetMetrics()`，否则 Prometheus 看到的序列会回退。

```java
import com.pisces.sdk.PiscesClient;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterBinder;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PiscesSdkMetricsConfiguration {

    @Bean
    public MeterBinder piscesSdkMetricsBinder(PiscesClient piscesClient) {
        return registry -> bindPiscesSdkMetrics(registry, piscesClient);
    }

    private void bindPiscesSdkMetrics(MeterRegistry registry, PiscesClient piscesClient) {
        Gauge.builder("pisces_sdk_request_attempts", piscesClient,
                        client -> client.getMetricsSnapshot().getRequestAttemptCount())
                .tag("sdk", "java")
                .register(registry);
        Gauge.builder("pisces_sdk_request_failures", piscesClient,
                        client -> client.getMetricsSnapshot().getRequestFailureCount())
                .tag("sdk", "java")
                .register(registry);
        Gauge.builder("pisces_sdk_retries", piscesClient,
                        client -> client.getMetricsSnapshot().getRetryCount())
                .tag("sdk", "java")
                .register(registry);
        Gauge.builder("pisces_sdk_stale_fallbacks", piscesClient,
                        client -> client.getMetricsSnapshot().getStaleExperimentConfigFallbackCount())
                .tag("sdk", "java")
                .register(registry);
        Gauge.builder("pisces_sdk_experiment_cache_hits", piscesClient,
                        client -> client.getMetricsSnapshot().getExperimentCacheHitCount())
                .tag("sdk", "java")
                .register(registry);
        Gauge.builder("pisces_sdk_experiment_cache_misses", piscesClient,
                        client -> client.getMetricsSnapshot().getExperimentCacheMissCount())
                .tag("sdk", "java")
                .register(registry);
        Gauge.builder("pisces_sdk_experiment_version_checks", piscesClient,
                        client -> client.getMetricsSnapshot().getExperimentVersionCheckCount())
                .tag("sdk", "java")
                .register(registry);
    }
}
```

如果业务侧是日志或自研埋点系统，可以周期性读取 snapshot，上报后调用 `resetMetrics()`。这种模式下上报的是窗口内增量，不应再按 Prometheus counter 处理。

## JS Node 示例

Node 服务可以把 snapshot 转成 Prometheus text format。示例只使用 Node 内置 `http` 模块：

```javascript
const http = require('http')
const PiscesSDK = require('./pisces-sdk')

const sdk = new PiscesSDK({
  apiBaseUrl: process.env.PISCES_API_BASE_URL,
  experimentId: process.env.PISCES_EXPERIMENT_ID,
  visitorId: 'server-side-probe',
  headers: {
    'X-Pisces-Api-Key': process.env.PISCES_RUNTIME_API_KEY
  },
  maxRetries: 2,
  retryInitialBackoffMillis: 100,
  retryMaxBackoffMillis: 1000,
  retryBackoffJitterRatio: 0.2,
  experimentCacheTtl: 60000,
  configVersionLongPollMillis: 25000,
  allowStaleExperimentConfig: true
})

function line(name, value) {
  return `${name}{sdk="js"} ${value}`
}

function renderMetrics() {
  const metrics = sdk.getMetricsSnapshot()
  return [
    line('pisces_sdk_request_attempts', metrics.requestAttemptCount),
    line('pisces_sdk_request_failures', metrics.requestFailureCount),
    line('pisces_sdk_retries', metrics.retryCount),
    line('pisces_sdk_stale_fallbacks', metrics.staleExperimentConfigFallbackCount),
    line('pisces_sdk_experiment_cache_hits', metrics.experimentCacheHitCount),
    line('pisces_sdk_experiment_cache_misses', metrics.experimentCacheMissCount),
    line('pisces_sdk_experiment_version_checks', metrics.experimentVersionCheckCount),
    ''
  ].join('\n')
}

http.createServer((request, response) => {
  if (request.url !== '/metrics') {
    response.writeHead(404)
    response.end()
    return
  }
  response.writeHead(200, { 'Content-Type': 'text/plain; version=0.0.4' })
  response.end(renderMetrics())
}).listen(9108)
```

浏览器侧不建议直接暴露 `/metrics`。前端页面应把 `getMetricsSnapshot()` 交给既有 RUM/埋点通道，或周期性 `sendBeacon` 到业务后端后再统一导出。

## 告警建议

- `requestFailureCount` 或 `retryCount` 在短时间内持续增长：排查 SDK 到 Pisces 的网络、鉴权和 runtime 接口。
- `staleExperimentConfigFallbackCount` 增长：确认 Pisces runtime 配置接口、Redis 广播和依赖存储是否异常。
- `experimentCacheMissCount` 明显高于 hit：检查 SDK 是否频繁创建新实例、TTL 是否过短、配置版本是否被高频发布。
- 服务端 `pisces_traffic_assignment_requests_total{result="ERROR"}` 增长但 SDK failure 不增长：优先检查业务是否没有覆盖所有 SDK 实例或埋点进程。
