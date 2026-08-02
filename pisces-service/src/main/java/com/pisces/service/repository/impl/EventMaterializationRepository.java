package com.pisces.service.repository.impl;

import com.pisces.service.entity.EventMaterializationEntity;
import com.pisces.service.event.EventMaterializationRecord;
import com.pisces.service.mapper.EventMaterializationMapper;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 基于数据库的事件事实派生物化账本仓库
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/30 11:03
 */
@Repository
@AllArgsConstructor
public class EventMaterializationRepository
        implements com.pisces.service.repository.EventMaterializationRepository {

    private final EventMaterializationMapper eventMaterializationMapper;

    @Override
    public void saveOrRefresh(EventMaterializationRecord record) {
        eventMaterializationMapper.upsert(buildEntity(record));
    }

    @Override
    public boolean exists(String factKind, String factId) {
        return eventMaterializationMapper.countByFact(factKind, factId) > 0;
    }

    @Override
    public long countMaterializedEventsByReplayScope(String experimentId, String groupId, LocalDateTime startTime,
                                                     LocalDateTime endTime, List<String> eventTypes) {
        return eventMaterializationMapper.countMaterializedEventsByReplayScope(experimentId, groupId, startTime,
                endTime, eventTypes);
    }

    @Override
    public long countMaterializedExposuresByReplayScope(String experimentId, String groupId, LocalDateTime startTime,
                                                        LocalDateTime endTime) {
        return eventMaterializationMapper.countMaterializedExposuresByReplayScope(experimentId, groupId, startTime,
                endTime);
    }

    private EventMaterializationEntity buildEntity(EventMaterializationRecord record) {
        EventMaterializationEntity entity = new EventMaterializationEntity();
        entity.setFactKind(record.getFactKind());
        entity.setFactId(record.getFactId());
        entity.setExperimentId(record.getExperimentId());
        entity.setGroupId(record.getGroupId());
        entity.setEventType(record.getEventType());
        entity.setMaterializationSource(record.getMaterializationSource());
        entity.setReplayJobId(record.getReplayJobId());
        entity.setMaterializedAt(record.getMaterializedAt());
        return entity;
    }
}
