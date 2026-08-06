package com.pisces.service.repository.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.pisces.common.model.ExperimentAssignment;
import com.pisces.service.entity.ExperimentAssignmentEntity;
import com.pisces.service.entity.ExperimentFactAggregateEntity;
import com.pisces.service.mapper.ExperimentAssignmentMapper;
import com.pisces.service.util.JsonUtil;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 基于数据库的实验分流事实仓库实现
 */
@Repository
@AllArgsConstructor
public class ExperimentAssignmentRepository implements com.pisces.service.repository.ExperimentAssignmentRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ExperimentAssignmentMapper experimentAssignmentMapper;

    private final JsonUtil jsonUtil;

    @Override
    public void save(ExperimentAssignment assignment) {
        experimentAssignmentMapper.upsert(buildExperimentAssignmentEntity(assignment));
    }

    @Override
    public Optional<ExperimentAssignment> findByExperimentIdAndVisitorId(String experimentId, String visitorId) {
        ExperimentAssignmentEntity entity = experimentAssignmentMapper.selectByExperimentIdAndVisitorId(experimentId, visitorId);
        if (entity == null) {
            return Optional.empty();
        }
        return Optional.of(buildExperimentAssignment(entity));
    }

    @Override
    public long countByExperimentIdAndGroupId(String experimentId, String groupId) {
        return experimentAssignmentMapper.countByExperimentIdAndGroupId(experimentId, groupId);
    }

    @Override
    public ExperimentFactAggregateEntity aggregateByExperimentIds(List<String> experimentIds) {
        if (experimentIds == null || experimentIds.isEmpty()) {
            return emptyAggregate();
        }
        return normalizeAggregate(experimentAssignmentMapper.aggregateByExperimentIds(experimentIds));
    }

    @Override
    public List<ExperimentAssignment> listByVisitorId(String visitorId) {
        return experimentAssignmentMapper.selectByVisitorId(visitorId).stream()
                .map(this::buildExperimentAssignment)
                .toList();
    }

    private ExperimentAssignmentEntity buildExperimentAssignmentEntity(ExperimentAssignment assignment) {
        ExperimentAssignmentEntity entity = new ExperimentAssignmentEntity();
        entity.setAssignmentId(assignment.getAssignmentId());
        entity.setExperimentId(assignment.getExperimentId());
        entity.setVisitorId(assignment.getVisitorId());
        entity.setGroupId(assignment.getGroupId());
        entity.setStrategy(assignment.getStrategy());
        entity.setHashKey(assignment.getHashKey());
        entity.setConfigVersion(assignment.getConfigVersion());
        entity.setAttributesJson(jsonUtil.toJson(assignment.getAttributes()));
        entity.setIdempotencyKey(assignment.getIdempotencyKey());
        entity.setAssignedAt(assignment.getAssignedAt());
        return entity;
    }

    private ExperimentAssignment buildExperimentAssignment(ExperimentAssignmentEntity entity) {
        ExperimentAssignment assignment = new ExperimentAssignment();
        assignment.setAssignmentId(entity.getAssignmentId());
        assignment.setExperimentId(entity.getExperimentId());
        assignment.setVisitorId(entity.getVisitorId());
        assignment.setGroupId(entity.getGroupId());
        assignment.setStrategy(entity.getStrategy());
        assignment.setHashKey(entity.getHashKey());
        assignment.setConfigVersion(entity.getConfigVersion());
        assignment.setAttributes(readMap(entity.getAttributesJson()));
        assignment.setIdempotencyKey(entity.getIdempotencyKey());
        assignment.setAssignedAt(entity.getAssignedAt());
        return assignment;
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
