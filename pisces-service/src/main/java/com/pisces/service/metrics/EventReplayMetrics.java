package com.pisces.service.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * 事件重放后台任务监控指标。
 */
@Component
@RequiredArgsConstructor
public class EventReplayMetrics {

    private static final String JOBS_METRIC = "pisces.event.replay.jobs";

    private static final String DURATION_METRIC = "pisces.event.replay.duration";

    private static final String STATUS_TAG = "status";

    private static final String TAG_UNKNOWN = "UNKNOWN";

    private final MeterRegistry meterRegistry;

    private final ConcurrentMap<String, Counter> jobCounters = new ConcurrentHashMap<>();

    private final ConcurrentMap<String, Timer> durationTimers = new ConcurrentHashMap<>();

    public void recordSubmitted() {
        jobCounter("SUBMITTED").increment();
    }

    public void recordSubmitRejected() {
        jobCounter("REJECTED").increment();
    }

    public void recordTerminal(String status, long elapsedNanos) {
        String normalizedStatus = normalizeStatus(status);
        jobCounter(normalizedStatus).increment();
        durationTimer(normalizedStatus).record(Math.max(0L, elapsedNanos), TimeUnit.NANOSECONDS);
    }

    private Counter jobCounter(String status) {
        String normalizedStatus = normalizeStatus(status);
        return jobCounters.computeIfAbsent(normalizedStatus, key -> Counter.builder(JOBS_METRIC)
                .description("事件重放任务状态计数")
                .tag(STATUS_TAG, normalizedStatus)
                .register(meterRegistry));
    }

    private Timer durationTimer(String status) {
        String normalizedStatus = normalizeStatus(status);
        return durationTimers.computeIfAbsent(normalizedStatus, key -> Timer.builder(DURATION_METRIC)
                .description("事件重放任务执行耗时")
                .tag(STATUS_TAG, normalizedStatus)
                .register(meterRegistry));
    }

    private String normalizeStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return TAG_UNKNOWN;
        }
        return status.trim().toUpperCase(Locale.ROOT);
    }
}
