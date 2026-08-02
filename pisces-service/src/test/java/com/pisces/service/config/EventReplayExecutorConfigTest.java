package com.pisces.service.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class EventReplayExecutorConfigTest {

    @Test
    void eventReplayTaskExecutorShouldNormalizePoolSizesAndRunTasks() throws Exception {
        EventReplayExecutorConfig config = new EventReplayExecutorConfig();

        Executor executor = config.eventReplayTaskExecutor(0, 0, -1, 1);

        assertThat(executor).isInstanceOf(ThreadPoolTaskExecutor.class);
        ThreadPoolTaskExecutor taskExecutor = (ThreadPoolTaskExecutor) executor;
        assertThat(taskExecutor.getCorePoolSize()).isEqualTo(1);
        assertThat(taskExecutor.getMaxPoolSize()).isEqualTo(1);
        assertThat(taskExecutor.getThreadNamePrefix()).isEqualTo("pisces-event-replay-");

        CountDownLatch latch = new CountDownLatch(1);
        taskExecutor.execute(latch::countDown);

        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
        taskExecutor.shutdown();
    }
}
