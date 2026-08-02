package com.pisces.service.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class EventReplayMetricsTest {

    private static final String JOBS_METRIC = "pisces.event.replay.jobs";

    private static final String DURATION_METRIC = "pisces.event.replay.duration";

    @Test
    void recordSubmittedAndTerminalShouldUseFiniteStatusTags() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        EventReplayMetrics metrics = new EventReplayMetrics(meterRegistry);

        metrics.recordSubmitted();
        metrics.recordTerminal("succeeded", 1_000_000L);

        Counter submittedCounter = meterRegistry.find(JOBS_METRIC)
                .tag("status", "SUBMITTED")
                .counter();
        Counter succeededCounter = meterRegistry.find(JOBS_METRIC)
                .tag("status", "SUCCEEDED")
                .counter();
        Timer succeededTimer = meterRegistry.find(DURATION_METRIC)
                .tag("status", "SUCCEEDED")
                .timer();

        assertThat(submittedCounter).isNotNull();
        assertThat(submittedCounter.count()).isEqualTo(1D);
        assertThat(succeededCounter).isNotNull();
        assertThat(succeededCounter.count()).isEqualTo(1D);
        assertThat(succeededTimer).isNotNull();
        assertThat(succeededTimer.count()).isEqualTo(1L);
        assertThat(succeededTimer.totalTime(TimeUnit.NANOSECONDS)).isEqualTo(1_000_000D);
    }

    @Test
    void recordSubmitRejectedShouldExposeRejectedStatus() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        EventReplayMetrics metrics = new EventReplayMetrics(meterRegistry);

        metrics.recordSubmitRejected();

        Counter rejectedCounter = meterRegistry.find(JOBS_METRIC)
                .tag("status", "REJECTED")
                .counter();
        assertThat(rejectedCounter).isNotNull();
        assertThat(rejectedCounter.count()).isEqualTo(1D);
    }
}
