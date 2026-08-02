package com.pisces.service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 事件重放后台执行线程池配置。
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/30 14:10
 */
@Configuration
public class EventReplayExecutorConfig {

    @Bean(name = "eventReplayTaskExecutor")
    public Executor eventReplayTaskExecutor(
            @Value("${pisces.event-pipeline.replay.executor.core-size:1}") int coreSize,
            @Value("${pisces.event-pipeline.replay.executor.max-size:2}") int maxSize,
            @Value("${pisces.event-pipeline.replay.executor.queue-capacity:100}") int queueCapacity,
            @Value("${pisces.event-pipeline.replay.executor.await-termination-seconds:30}")
            int awaitTerminationSeconds) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, coreSize));
        executor.setMaxPoolSize(Math.max(Math.max(1, coreSize), maxSize));
        executor.setQueueCapacity(Math.max(0, queueCapacity));
        executor.setThreadNamePrefix("pisces-event-replay-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(Math.max(0, awaitTerminationSeconds));
        executor.initialize();
        return executor;
    }
}
