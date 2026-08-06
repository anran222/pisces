package com.pisces.service.repository.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.pisces.common.model.ExperimentEventFact;
import com.pisces.service.entity.ExperimentEventEntity;
import com.pisces.service.entity.ExperimentFactAggregateEntity;
import com.pisces.service.mapper.ExperimentEventMapper;
import com.pisces.service.util.JsonUtil;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 基于数据库的实验事件事实仓库实现
 */
@Repository
@AllArgsConstructor
public class ExperimentEventRepository implements com.pisces.service.repository.ExperimentEventRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ExperimentEventMapper experimentEventMapper;

    private final JsonUtil jsonUtil;

    @Override
    public boolean saveIfAbsent(ExperimentEventFact eventFact) {
        return experimentEventMapper.insertIgnore(buildExperimentEventEntity(eventFact)) > 0;
    }

    @Override
    public ExperimentEventFact findByExperimentIdAndClientEventId(String experimentId, String clientEventId) {
        ExperimentEventEntity entity =
                experimentEventMapper.selectByExperimentIdAndClientEventId(experimentId, clientEventId);
        return entity == null ? null : buildExperimentEventFact(entity);
    }

    @Override
    public long countByExperimentIdAndGroupIdAndEventType(String experimentId, String groupId, String eventType) {
        return experimentEventMapper.countByExperimentIdAndGroupIdAndEventType(experimentId, groupId, eventType);
    }

    @Override
    public ExperimentFactAggregateEntity aggregateByExperimentIds(List<String> experimentIds) {
        if (experimentIds == null || experimentIds.isEmpty()) {
            return emptyAggregate();
        }
        return normalizeAggregate(experimentEventMapper.aggregateByExperimentIds(experimentIds));
    }

    @Override
    public long countByReplayScope(String experimentId, String groupId, LocalDateTime startTime,
                                   LocalDateTime endTime, List<String> eventTypes) {
        return experimentEventMapper.countByReplayScope(experimentId, groupId, startTime, endTime, eventTypes);
    }

    @Override
    public List<ExperimentEventFact> listByReplayScope(String experimentId, String groupId,
                                                       LocalDateTime startTime, LocalDateTime endTime,
                                                       List<String> eventTypes) {
        return experimentEventMapper.selectByReplayScope(experimentId, groupId, startTime, endTime, eventTypes)
                .stream()
                .map(this::buildExperimentEventFact)
                .toList();
    }

    @Override
    public List<ExperimentEventFact> listByReplayScopeBatch(String experimentId, String groupId,
                                                            LocalDateTime startTime, LocalDateTime endTime,
                                                            List<String> eventTypes, long offset, int limit) {
        return experimentEventMapper.selectByReplayScopeBatch(experimentId, groupId, startTime, endTime, eventTypes,
                        offset, limit)
                .stream()
                .map(this::buildExperimentEventFact)
                .toList();
    }

    @Override
    public List<ExperimentEventFact> listUnmaterializedByReplayScope(String experimentId, String groupId,
                                                                     LocalDateTime startTime, LocalDateTime endTime,
                                                                     List<String> eventTypes) {
        return experimentEventMapper.selectUnmaterializedByReplayScope(experimentId, groupId, startTime, endTime,
                        eventTypes)
                .stream()
                .map(this::buildExperimentEventFact)
                .toList();
    }

    @Override
    public List<ExperimentEventFact> listUnmaterializedByReplayScopeBatch(String experimentId, String groupId,
                                                                          LocalDateTime startTime,
                                                                          LocalDateTime endTime,
                                                                          List<String> eventTypes,
                                                                          long offset, int limit) {
        return experimentEventMapper.selectUnmaterializedByReplayScopeBatch(experimentId, groupId, startTime,
                        endTime, eventTypes, offset, limit)
                .stream()
                .map(this::buildExperimentEventFact)
                .toList();
    }

    @Override
    public long countDistinctVisitorByExperimentIdAndGroupId(String experimentId, String groupId) {
        return experimentEventMapper.countDistinctVisitorByExperimentIdAndGroupId(experimentId, groupId);
    }

    @Override
    public List<ExperimentEventFact> listByExperimentIdAndGroupId(String experimentId, String groupId) {
        return experimentEventMapper.selectByExperimentIdAndGroupId(experimentId, groupId).stream()
                .map(this::buildExperimentEventFact)
                .toList();
    }

    @Override
    public List<ExperimentEventFact> listByExperimentIdAndGroupIdBatch(String experimentId, String groupId,
                                                                       long offset, int limit) {
        return experimentEventMapper.selectByExperimentIdAndGroupIdBatch(experimentId, groupId, offset, limit)
                .stream()
                .map(this::buildExperimentEventFact)
                .toList();
    }

    @Override
    public List<ExperimentEventFact> listByExperimentIdAndGroupIdInTimeRange(String experimentId, String groupId,
                                                                              LocalDateTime startTime,
                                                                              LocalDateTime endTime) {
        return experimentEventMapper.selectByExperimentIdAndGroupIdInTimeRange(experimentId, groupId, startTime, endTime)
                .stream()
                .map(this::buildExperimentEventFact)
                .toList();
    }

    private ExperimentEventEntity buildExperimentEventEntity(ExperimentEventFact eventFact) {
        ExperimentEventEntity entity = new ExperimentEventEntity();
        entity.setEventId(eventFact.getEventId());
        entity.setExperimentId(eventFact.getExperimentId());
        entity.setVisitorId(eventFact.getVisitorId());
        entity.setGroupId(eventFact.getGroupId());
        entity.setEventType(eventFact.getEventType());
        entity.setEventName(eventFact.getEventName());
        entity.setClientEventId(eventFact.getClientEventId());
        entity.setPropertiesJson(jsonUtil.toJson(eventFact.getProperties()));
        entity.setEventTime(eventFact.getEventTime());
        return entity;
    }

    private ExperimentEventFact buildExperimentEventFact(ExperimentEventEntity entity) {
        ExperimentEventFact eventFact = new ExperimentEventFact();
        eventFact.setEventId(entity.getEventId());
        eventFact.setExperimentId(entity.getExperimentId());
        eventFact.setVisitorId(entity.getVisitorId());
        eventFact.setGroupId(entity.getGroupId());
        eventFact.setEventType(entity.getEventType());
        eventFact.setEventName(entity.getEventName());
        eventFact.setClientEventId(entity.getClientEventId());
        eventFact.setProperties(readMap(entity.getPropertiesJson()));
        eventFact.setEventTime(entity.getEventTime());
        return eventFact;
    }

    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        return jsonUtil.toObject(json, MAP_TYPE);
    }

    private ExperimentFactAggregateEntity normalizeAggregate(ExperimentFactAggregateEntity aggregate) {
        return aggregate == null ? emptyAggregate() : aggregate;
    }

    private ExperimentFactAggregateEntity emptyAggregate() {
        ExperimentFactAggregateEntity aggregate = new ExperimentFactAggregateEntity();
        aggregate.setTotalCount(0L);
        return aggregate;
    }
}
