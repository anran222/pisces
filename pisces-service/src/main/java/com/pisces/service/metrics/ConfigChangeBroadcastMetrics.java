package com.pisces.service.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 实验配置变更广播监控指标。
 */
@Component
@RequiredArgsConstructor
public class ConfigChangeBroadcastMetrics {

    public static final String RESULT_SUCCESS = "SUCCESS";

    public static final String RESULT_ERROR = "ERROR";

    public static final String RESULT_SKIPPED = "SKIPPED";

    public static final String RESULT_APPLIED = "APPLIED";

    public static final String RESULT_IGNORED_SELF = "IGNORED_SELF";

    public static final String RESULT_INVALID = "INVALID";

    private static final String ENABLED_METRIC = "pisces.config.change.broadcast.enabled";

    private static final String PUBLISHED_METRIC = "pisces.config.change.broadcast.published";

    private static final String RECEIVED_METRIC = "pisces.config.change.broadcast.received";

    private static final String LISTENER_ERRORS_METRIC = "pisces.config.change.broadcast.listener.errors";

    private static final String LAST_PUBLISHED_EPOCH_SECONDS_METRIC =
            "pisces.config.change.broadcast.last.published.epoch.seconds";

    private static final String LAST_RECEIVED_EPOCH_SECONDS_METRIC =
            "pisces.config.change.broadcast.last.received.epoch.seconds";

    private static final String RESULT_TAG = "result";

    private final MeterRegistry meterRegistry;

    private final AtomicLong enabled = new AtomicLong();

    private final AtomicLong lastPublishedEpochSeconds = new AtomicLong();

    private final AtomicLong lastReceivedEpochSeconds = new AtomicLong();

    private final ConcurrentMap<String, Counter> publishedCounters = new ConcurrentHashMap<>();

    private final ConcurrentMap<String, Counter> receivedCounters = new ConcurrentHashMap<>();

    private Counter listenerErrors;

    @PostConstruct
    public void init() {
        Gauge.builder(ENABLED_METRIC, enabled, AtomicLong::get)
                .description("实验配置 Redis 跨实例广播是否启用")
                .register(meterRegistry);
        Gauge.builder(LAST_PUBLISHED_EPOCH_SECONDS_METRIC, lastPublishedEpochSeconds, AtomicLong::get)
                .description("最近一次成功发送配置变更广播的时间")
                .register(meterRegistry);
        Gauge.builder(LAST_RECEIVED_EPOCH_SECONDS_METRIC, lastReceivedEpochSeconds, AtomicLong::get)
                .description("最近一次成功处理远端配置变更广播的时间")
                .register(meterRegistry);
        listenerErrors = Counter.builder(LISTENER_ERRORS_METRIC)
                .description("配置变更广播 listener 处理失败次数")
                .register(meterRegistry);
    }

    public void recordEnabled(boolean broadcastEnabled) {
        enabled.set(broadcastEnabled ? 1L : 0L);
    }

    public void recordPublished(String result) {
        publishedCounter(result).increment();
        if (RESULT_SUCCESS.equals(result)) {
            lastPublishedEpochSeconds.set(Instant.now().getEpochSecond());
        }
    }

    public void recordReceived(String result) {
        receivedCounter(result).increment();
        if (RESULT_APPLIED.equals(result)) {
            lastReceivedEpochSeconds.set(Instant.now().getEpochSecond());
        }
    }

    public void recordListenerError() {
        if (listenerErrors != null) {
            listenerErrors.increment();
        }
    }

    private Counter publishedCounter(String result) {
        return publishedCounters.computeIfAbsent(result, key -> Counter.builder(PUBLISHED_METRIC)
                .description("实验配置变更广播发送计数")
                .tag(RESULT_TAG, result)
                .register(meterRegistry));
    }

    private Counter receivedCounter(String result) {
        return receivedCounters.computeIfAbsent(result, key -> Counter.builder(RECEIVED_METRIC)
                .description("实验配置变更广播接收计数")
                .tag(RESULT_TAG, result)
                .register(meterRegistry));
    }
}
