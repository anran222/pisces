package com.pisces.service.repository.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.pisces.service.entity.EventInboxEntity;
import com.pisces.service.entity.EventInboxStatusCountEntity;
import com.pisces.service.event.EventInboxRecord;
import com.pisces.service.mapper.EventInboxMapper;
import com.pisces.service.util.JsonUtil;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 事件收件箱数据访问实现
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/1 10:26
 */
@Repository
@AllArgsConstructor
public class EventInboxRepository implements com.pisces.service.repository.EventInboxRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final EventInboxMapper eventInboxMapper;

    private final JsonUtil jsonUtil;

    @Override
    public boolean saveIfAbsent(EventInboxRecord record) {
        return eventInboxMapper.insertIgnore(buildEventInboxEntity(record)) > 0;
    }

    @Override
    public List<EventInboxRecord> listDueRecords(LocalDateTime now, int limit) {
        return eventInboxMapper.selectDueRecords(now, limit).stream()
                .map(this::buildEventInboxRecord)
                .toList();
    }

    @Override
    public List<EventInboxRecord> listDueRecords(String experimentId, LocalDateTime now, int limit) {
        return eventInboxMapper.selectDueRecordsByExperimentId(experimentId, now, limit).stream()
                .map(this::buildEventInboxRecord)
                .toList();
    }

    @Override
    public boolean markProcessing(String inboxId, String lockedBy, LocalDateTime now, LocalDateTime lockedUntil) {
        return eventInboxMapper.markProcessing(inboxId, lockedBy, now, lockedUntil) > 0;
    }

    @Override
    public void markDone(String inboxId, LocalDateTime processedAt) {
        eventInboxMapper.markDone(inboxId, processedAt);
    }

    @Override
    public void markRetry(String inboxId, int retryCount, LocalDateTime nextRetryAt, String lastError) {
        eventInboxMapper.markRetry(inboxId, retryCount, nextRetryAt, lastError);
    }

    @Override
    public void markDead(String inboxId, int retryCount, String lastError, LocalDateTime processedAt) {
        eventInboxMapper.markDead(inboxId, retryCount, lastError, processedAt);
    }

    @Override
    public List<EventInboxStatusCountEntity> countByExperimentIdGroupByStatus(String experimentId) {
        return eventInboxMapper.countByExperimentIdGroupByStatus(experimentId);
    }

    @Override
    public LocalDateTime selectOldestUnfinishedAcceptedAt(String experimentId) {
        return eventInboxMapper.selectOldestUnfinishedAcceptedAt(experimentId);
    }

    @Override
    public int retryDeadRecords(String experimentId, LocalDateTime nextRetryAt) {
        return eventInboxMapper.retryDeadRecords(experimentId, nextRetryAt);
    }

    private EventInboxEntity buildEventInboxEntity(EventInboxRecord record) {
        EventInboxEntity entity = new EventInboxEntity();
        entity.setId(record.getId());
        entity.setInboxId(record.getInboxId());
        entity.setExperimentId(record.getExperimentId());
        entity.setVisitorId(record.getVisitorId());
        entity.setGroupId(record.getGroupId());
        entity.setEventKind(record.getEventKind());
        entity.setEventType(record.getEventType());
        entity.setEventName(record.getEventName());
        entity.setScene(record.getScene());
        entity.setClientEventId(record.getClientEventId());
        entity.setIdempotencyKey(record.getIdempotencyKey());
        entity.setPropertiesJson(jsonUtil.toJson(record.getProperties()));
        entity.setStatus(record.getStatus());
        entity.setRetryCount(record.getRetryCount());
        entity.setNextRetryAt(record.getNextRetryAt());
        entity.setLockedBy(record.getLockedBy());
        entity.setLockedUntil(record.getLockedUntil());
        entity.setLastError(record.getLastError());
        entity.setEventTime(record.getEventTime());
        entity.setAcceptedAt(record.getAcceptedAt());
        entity.setProcessedAt(record.getProcessedAt());
        return entity;
    }

    private EventInboxRecord buildEventInboxRecord(EventInboxEntity entity) {
        EventInboxRecord record = new EventInboxRecord();
        record.setId(entity.getId());
        record.setInboxId(entity.getInboxId());
        record.setExperimentId(entity.getExperimentId());
        record.setVisitorId(entity.getVisitorId());
        record.setGroupId(entity.getGroupId());
        record.setEventKind(entity.getEventKind());
        record.setEventType(entity.getEventType());
        record.setEventName(entity.getEventName());
        record.setScene(entity.getScene());
        record.setClientEventId(entity.getClientEventId());
        record.setIdempotencyKey(entity.getIdempotencyKey());
        record.setProperties(readMap(entity.getPropertiesJson()));
        record.setStatus(entity.getStatus());
        record.setRetryCount(entity.getRetryCount());
        record.setNextRetryAt(entity.getNextRetryAt());
        record.setLockedBy(entity.getLockedBy());
        record.setLockedUntil(entity.getLockedUntil());
        record.setLastError(entity.getLastError());
        record.setEventTime(entity.getEventTime());
        record.setAcceptedAt(entity.getAcceptedAt());
        record.setProcessedAt(entity.getProcessedAt());
        return record;
    }

    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        return jsonUtil.toObject(json, MAP_TYPE);
    }
}
