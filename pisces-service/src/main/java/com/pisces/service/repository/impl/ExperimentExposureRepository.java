package com.pisces.service.repository.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.pisces.common.model.ExperimentExposure;
import com.pisces.service.entity.ExperimentExposureEntity;
import com.pisces.service.mapper.ExperimentExposureMapper;
import com.pisces.service.util.JsonUtil;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 基于数据库的实验曝光事实仓库实现
 */
@Repository
@AllArgsConstructor
public class ExperimentExposureRepository implements com.pisces.service.repository.ExperimentExposureRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ExperimentExposureMapper experimentExposureMapper;

    private final JsonUtil jsonUtil;

    @Override
    public void save(ExperimentExposure exposure) {
        experimentExposureMapper.insert(buildExperimentExposureEntity(exposure));
    }

    @Override
    public long countByExperimentIdAndGroupId(String experimentId, String groupId) {
        return experimentExposureMapper.countByExperimentIdAndGroupId(experimentId, groupId);
    }

    @Override
    public List<ExperimentExposure> listByExperimentIdAndGroupId(String experimentId, String groupId) {
        return experimentExposureMapper.selectByExperimentIdAndGroupId(experimentId, groupId).stream()
                .map(this::buildExperimentExposure)
                .toList();
    }

    @Override
    public List<ExperimentExposure> listByExperimentIdAndGroupIdInTimeRange(String experimentId, String groupId,
                                                                             LocalDateTime startTime,
                                                                             LocalDateTime endTime) {
        return experimentExposureMapper.selectByExperimentIdAndGroupIdInTimeRange(experimentId, groupId, startTime, endTime)
                .stream()
                .map(this::buildExperimentExposure)
                .toList();
    }

    private ExperimentExposureEntity buildExperimentExposureEntity(ExperimentExposure exposure) {
        ExperimentExposureEntity entity = new ExperimentExposureEntity();
        entity.setExposureId(exposure.getExposureId());
        entity.setExperimentId(exposure.getExperimentId());
        entity.setVisitorId(exposure.getVisitorId());
        entity.setGroupId(exposure.getGroupId());
        entity.setScene(exposure.getScene());
        entity.setPropertiesJson(jsonUtil.toJson(exposure.getProperties()));
        entity.setIdempotencyKey(exposure.getIdempotencyKey());
        entity.setExposedAt(exposure.getExposedAt());
        return entity;
    }

    private ExperimentExposure buildExperimentExposure(ExperimentExposureEntity entity) {
        ExperimentExposure exposure = new ExperimentExposure();
        exposure.setExposureId(entity.getExposureId());
        exposure.setExperimentId(entity.getExperimentId());
        exposure.setVisitorId(entity.getVisitorId());
        exposure.setGroupId(entity.getGroupId());
        exposure.setScene(entity.getScene());
        exposure.setProperties(readMap(entity.getPropertiesJson()));
        exposure.setIdempotencyKey(entity.getIdempotencyKey());
        exposure.setExposedAt(entity.getExposedAt());
        return exposure;
    }

    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        return jsonUtil.toObject(json, MAP_TYPE);
    }
}
