package com.pisces.service.service.impl;

import com.pisces.service.event.EventInboxRecord;
import com.pisces.service.repository.EventInboxRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 事件收件箱消费者测试
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/1 10:34
 */
class EventInboxConsumerTest {

    private static final String INBOX_ID = "inbox_retry";

    @Test
    void processDueRecordsShouldMarkRetryWhenMaterializeFailsBeforeMaxRetry() {
        EventInboxRepository repository = mock(EventInboxRepository.class);
        EventInboxMaterializer materializer = mock(EventInboxMaterializer.class);
        EventInboxConsumer consumer = buildConsumer(repository, materializer, 5);
        EventInboxRecord record = record(0);

        when(repository.listDueRecords(any(LocalDateTime.class), eq(100))).thenReturn(List.of(record));
        when(repository.markProcessing(eq(INBOX_ID), any(String.class), any(LocalDateTime.class),
                any(LocalDateTime.class))).thenReturn(true);
        doThrow(new IllegalStateException("redis down")).when(materializer).materialize(record);

        int processedCount = consumer.processDueRecords();

        assertThat(processedCount).isEqualTo(1);
        verify(repository).markRetry(eq(INBOX_ID), eq(1), any(LocalDateTime.class), eq("redis down"));
        verify(repository, never()).markDead(eq(INBOX_ID), anyInt(), any(String.class),
                any(LocalDateTime.class));
    }

    @Test
    void processDueRecordsShouldMarkDeadWhenRetryCountReachesLimit() {
        EventInboxRepository repository = mock(EventInboxRepository.class);
        EventInboxMaterializer materializer = mock(EventInboxMaterializer.class);
        EventInboxConsumer consumer = buildConsumer(repository, materializer, 5);
        EventInboxRecord record = record(4);

        when(repository.listDueRecords(any(LocalDateTime.class), eq(100))).thenReturn(List.of(record));
        when(repository.markProcessing(eq(INBOX_ID), any(String.class), any(LocalDateTime.class),
                any(LocalDateTime.class))).thenReturn(true);
        doThrow(new IllegalStateException("redis down")).when(materializer).materialize(record);

        int processedCount = consumer.processDueRecords();

        assertThat(processedCount).isEqualTo(1);
        verify(repository).markDead(eq(INBOX_ID), eq(5), eq("redis down"), any(LocalDateTime.class));
        verify(repository, never()).markRetry(eq(INBOX_ID), anyInt(), any(LocalDateTime.class),
                any(String.class));
    }

    @Test
    void processDueRecordsShouldLoadExperimentScopedDueRecords() {
        EventInboxRepository repository = mock(EventInboxRepository.class);
        EventInboxMaterializer materializer = mock(EventInboxMaterializer.class);
        EventInboxConsumer consumer = buildConsumer(repository, materializer, 5);
        EventInboxRecord record = record(0);

        when(repository.listDueRecords(eq("exp_1"), any(LocalDateTime.class), eq(100))).thenReturn(List.of(record));
        when(repository.markProcessing(eq(INBOX_ID), any(String.class), any(LocalDateTime.class),
                any(LocalDateTime.class))).thenReturn(true);

        int processedCount = consumer.processDueRecords("exp_1");

        assertThat(processedCount).isEqualTo(1);
        verify(repository).listDueRecords(eq("exp_1"), any(LocalDateTime.class), eq(100));
        verify(materializer).materialize(record);
        verify(repository).markDone(eq(INBOX_ID), any(LocalDateTime.class));
    }

    private EventInboxConsumer buildConsumer(EventInboxRepository repository, EventInboxMaterializer materializer,
                                             int maxRetryCount) {
        EventInboxConsumer consumer = new EventInboxConsumer(repository, materializer);
        ReflectionTestUtils.setField(consumer, "batchSize", 100);
        ReflectionTestUtils.setField(consumer, "maxRetryCount", maxRetryCount);
        ReflectionTestUtils.setField(consumer, "lockMinutes", 5L);
        return consumer;
    }

    private EventInboxRecord record(int retryCount) {
        EventInboxRecord record = new EventInboxRecord();
        record.setInboxId(INBOX_ID);
        record.setExperimentId("exp_1");
        record.setRetryCount(retryCount);
        record.setNextRetryAt(LocalDateTime.now().minusSeconds(1));
        return record;
    }
}
