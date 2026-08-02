package com.pisces.service.service.impl;

import com.pisces.service.event.EventInboxRecord;
import com.pisces.service.repository.EventInboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 事件收件箱消费者
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/1 10:26
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventInboxConsumer {

    private static final long[] RETRY_DELAY_MINUTES = {1L, 5L, 15L, 60L, 360L};
    private static final int DEFAULT_RETRY_COUNT = 0;
    private static final int ERROR_MESSAGE_MAX_LENGTH = 1024;

    private final EventInboxRepository eventInboxRepository;

    private final EventInboxMaterializer eventInboxMaterializer;

    @Value("${pisces.event-pipeline.batch-size:100}")
    private int batchSize;

    @Value("${pisces.event-pipeline.max-retry-count:5}")
    private int maxRetryCount;

    @Value("${pisces.event-pipeline.lock-minutes:5}")
    private long lockMinutes;

    private final String workerId = buildWorkerId();

    @Scheduled(fixedDelayString = "${pisces.event-pipeline.poll-delay-ms:1000}",
            initialDelayString = "${pisces.event-pipeline.initial-delay-ms:3000}")
    public void consumeScheduled() {
        processDueRecords();
    }

    public int processDueRecords() {
        LocalDateTime now = LocalDateTime.now();
        return processRecords(eventInboxRepository.listDueRecords(now, batchSize), now);
    }

    public int processDueRecords(String experimentId) {
        LocalDateTime now = LocalDateTime.now();
        return processRecords(eventInboxRepository.listDueRecords(experimentId, now, batchSize), now);
    }

    private int processRecords(List<EventInboxRecord> records, LocalDateTime now) {
        int processedCount = 0;
        for (EventInboxRecord record : records) {
            boolean claimed = eventInboxRepository.markProcessing(
                    record.getInboxId(), workerId, now, now.plusMinutes(lockMinutes));
            if (!claimed) {
                continue;
            }
            processRecord(record, now);
            processedCount++;
        }
        return processedCount;
    }

    private void processRecord(EventInboxRecord record, LocalDateTime now) {
        try {
            eventInboxMaterializer.materialize(record);
            eventInboxRepository.markDone(record.getInboxId(), LocalDateTime.now());
        } catch (Exception exception) {
            int nextRetryCount = resolveRetryCount(record) + 1;
            String errorMessage = normalizeErrorMessage(exception);
            if (nextRetryCount >= maxRetryCount) {
                eventInboxRepository.markDead(record.getInboxId(), nextRetryCount, errorMessage, LocalDateTime.now());
                log.warn("事件收件箱进入死信: inboxId={}, experimentId={}, retryCount={}",
                        record.getInboxId(), record.getExperimentId(), nextRetryCount, exception);
                return;
            }
            LocalDateTime nextRetryAt = resolveNextRetryAt(now, nextRetryCount);
            eventInboxRepository.markRetry(record.getInboxId(), nextRetryCount, nextRetryAt, errorMessage);
            log.warn("事件收件箱处理失败，将重试: inboxId={}, experimentId={}, retryCount={}, nextRetryAt={}",
                    record.getInboxId(), record.getExperimentId(), nextRetryCount, nextRetryAt, exception);
        }
    }

    private int resolveRetryCount(EventInboxRecord record) {
        Integer retryCount = record.getRetryCount();
        return retryCount == null ? DEFAULT_RETRY_COUNT : retryCount;
    }

    private LocalDateTime resolveNextRetryAt(LocalDateTime now, int retryCount) {
        int index = Math.min(Math.max(retryCount - 1, 0), RETRY_DELAY_MINUTES.length - 1);
        return now.plusMinutes(RETRY_DELAY_MINUTES[index]);
    }

    private String normalizeErrorMessage(Exception exception) {
        String message = exception.getMessage();
        String normalizedMessage = message == null ? exception.getClass().getSimpleName() : message;
        if (normalizedMessage.length() <= ERROR_MESSAGE_MAX_LENGTH) {
            return normalizedMessage;
        }
        return normalizedMessage.substring(0, ERROR_MESSAGE_MAX_LENGTH);
    }

    private String buildWorkerId() {
        try {
            return InetAddress.getLocalHost().getHostName() + "-" + UUID.randomUUID();
        } catch (Exception exception) {
            return "unknown-" + UUID.randomUUID();
        }
    }
}
