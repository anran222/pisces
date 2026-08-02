package com.pisces.service.metrics;

import com.pisces.common.response.TrafficAssignmentResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TrafficAssignmentMetricsTest {

    private static final String ASSIGNMENT_REQUESTS_METRIC = "pisces.traffic.assignment.requests";

    private static final String ASSIGNMENT_LATENCY_METRIC = "pisces.traffic.assignment.latency";

    private static final String CACHE_EVENTS_METRIC = "pisces.traffic.cache.events";

    @Test
    void recordAssignmentShouldNormalizeDynamicReasonTag() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        TrafficAssignmentMetrics metrics = new TrafficAssignmentMetrics(meterRegistry);
        TrafficAssignmentResponse response = new TrafficAssignmentResponse();
        response.setAssigned(false);
        response.setSource("BLOCKED");
        response.setReason("LAYER_MUTEX:exp_other_001");

        metrics.recordAssignment(response, 1_000L);

        Counter counter = meterRegistry.find(ASSIGNMENT_REQUESTS_METRIC)
                .tag("result", "BLOCKED")
                .tag("source", "BLOCKED")
                .tag("reason", "LAYER_MUTEX")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1D);
        assertThat(meterRegistry.find(ASSIGNMENT_LATENCY_METRIC)
                .tag("result", "BLOCKED")
                .tag("source", "BLOCKED")
                .timer()).isNotNull();
    }

    @Test
    void recordCacheEventShouldUseFiniteOperationAndResultTags() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        TrafficAssignmentMetrics metrics = new TrafficAssignmentMetrics(meterRegistry);

        metrics.recordCacheEvent("USER_GROUP", "HIT");

        Counter counter = meterRegistry.find(CACHE_EVENTS_METRIC)
                .tag("operation", "USER_GROUP")
                .tag("result", "HIT")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1D);
    }
}
