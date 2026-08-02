package com.pisces.service.metrics;

import com.pisces.common.response.TrafficAssignmentResponse;
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
 * 分流热路径监控指标。
 */
@Component
@RequiredArgsConstructor
public class TrafficAssignmentMetrics {

    private static final String ASSIGNMENT_REQUESTS_METRIC = "pisces.traffic.assignment.requests";

    private static final String ASSIGNMENT_LATENCY_METRIC = "pisces.traffic.assignment.latency";

    private static final String CACHE_EVENTS_METRIC = "pisces.traffic.cache.events";

    private static final String RESULT_TAG = "result";

    private static final String SOURCE_TAG = "source";

    private static final String REASON_TAG = "reason";

    private static final String OPERATION_TAG = "operation";

    private static final String TAG_KEY_SEPARATOR = "|";

    private static final String RESULT_ASSIGNED = "ASSIGNED";

    private static final String RESULT_BLOCKED = "BLOCKED";

    private static final String RESULT_ERROR = "ERROR";

    private static final String TAG_UNKNOWN = "UNKNOWN";

    private static final String DYNAMIC_REASON_SEPARATOR = ":";

    private final MeterRegistry meterRegistry;

    private final ConcurrentMap<String, Counter> assignmentCounters = new ConcurrentHashMap<>();

    private final ConcurrentMap<String, Timer> assignmentLatencyTimers = new ConcurrentHashMap<>();

    private final ConcurrentMap<String, Counter> cacheEventCounters = new ConcurrentHashMap<>();

    /**
     * 记录一次分流请求结果。
     *
     * @param response 分流响应
     * @param elapsedNanos 分流耗时
     */
    public void recordAssignment(TrafficAssignmentResponse response, long elapsedNanos) {
        if (response == null) {
            recordAssignmentError(elapsedNanos);
            return;
        }
        String result = Boolean.TRUE.equals(response.getAssigned()) ? RESULT_ASSIGNED : RESULT_BLOCKED;
        String source = normalizeTag(response.getSource());
        String reason = normalizeReason(response.getReason());
        assignmentCounter(result, source, reason).increment();
        assignmentLatencyTimer(result, source).record(elapsedNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * 记录一次分流请求异常。
     *
     * @param elapsedNanos 分流耗时
     */
    public void recordAssignmentError(long elapsedNanos) {
        assignmentCounter(RESULT_ERROR, TAG_UNKNOWN, RESULT_ERROR).increment();
        assignmentLatencyTimer(RESULT_ERROR, TAG_UNKNOWN).record(elapsedNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * 记录一次分流缓存事件。
     *
     * @param operation 缓存操作
     * @param result 缓存结果
     */
    public void recordCacheEvent(String operation, String result) {
        String normalizedOperation = normalizeTag(operation);
        String normalizedResult = normalizeTag(result);
        cacheEventCounter(normalizedOperation, normalizedResult).increment();
    }

    private Counter assignmentCounter(String result, String source, String reason) {
        String counterKey = result + TAG_KEY_SEPARATOR + source + TAG_KEY_SEPARATOR + reason;
        return assignmentCounters.computeIfAbsent(counterKey, key -> Counter.builder(ASSIGNMENT_REQUESTS_METRIC)
                .description("分流请求结果计数")
                .tag(RESULT_TAG, result)
                .tag(SOURCE_TAG, source)
                .tag(REASON_TAG, reason)
                .register(meterRegistry));
    }

    private Timer assignmentLatencyTimer(String result, String source) {
        String timerKey = result + TAG_KEY_SEPARATOR + source;
        return assignmentLatencyTimers.computeIfAbsent(timerKey, key -> Timer.builder(ASSIGNMENT_LATENCY_METRIC)
                .description("分流请求耗时")
                .tag(RESULT_TAG, result)
                .tag(SOURCE_TAG, source)
                .register(meterRegistry));
    }

    private Counter cacheEventCounter(String operation, String result) {
        String counterKey = operation + TAG_KEY_SEPARATOR + result;
        return cacheEventCounters.computeIfAbsent(counterKey, key -> Counter.builder(CACHE_EVENTS_METRIC)
                .description("分流缓存事件计数")
                .tag(OPERATION_TAG, operation)
                .tag(RESULT_TAG, result)
                .register(meterRegistry));
    }

    private String normalizeReason(String reason) {
        if (!StringUtils.hasText(reason)) {
            return TAG_UNKNOWN;
        }
        int separatorIndex = reason.indexOf(DYNAMIC_REASON_SEPARATOR);
        if (separatorIndex > 0) {
            return normalizeTag(reason.substring(0, separatorIndex));
        }
        return normalizeTag(reason);
    }

    private String normalizeTag(String tagValue) {
        if (!StringUtils.hasText(tagValue)) {
            return TAG_UNKNOWN;
        }
        return tagValue.trim().toUpperCase(Locale.ROOT);
    }
}
