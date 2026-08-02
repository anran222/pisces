package com.pisces.service.repository;

import com.pisces.service.entity.EventInboxStatusCountEntity;
import com.pisces.service.event.EventInboxRecord;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 事件收件箱仓库
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/1 10:26
 */
public interface EventInboxRepository {

    boolean saveIfAbsent(EventInboxRecord record);

    List<EventInboxRecord> listDueRecords(LocalDateTime now, int limit);

    List<EventInboxRecord> listDueRecords(String experimentId, LocalDateTime now, int limit);

    boolean markProcessing(String inboxId, String lockedBy, LocalDateTime now, LocalDateTime lockedUntil);

    void markDone(String inboxId, LocalDateTime processedAt);

    void markRetry(String inboxId, int retryCount, LocalDateTime nextRetryAt, String lastError);

    void markDead(String inboxId, int retryCount, String lastError, LocalDateTime processedAt);

    List<EventInboxStatusCountEntity> countByExperimentIdGroupByStatus(String experimentId);

    LocalDateTime selectOldestUnfinishedAcceptedAt(String experimentId);

    int retryDeadRecords(String experimentId, LocalDateTime nextRetryAt);
}
