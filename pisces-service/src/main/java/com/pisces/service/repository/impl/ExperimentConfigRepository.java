package com.pisces.service.repository.impl;

import com.pisces.common.model.ExperimentMetadata;
import com.pisces.service.entity.ExperimentConfigEntity;
import com.pisces.service.mapper.ExperimentConfigMapper;
import com.pisces.service.util.JsonUtil;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 基于数据库的实验配置仓库实现
 */
@Repository
@AllArgsConstructor
public class ExperimentConfigRepository implements com.pisces.service.repository.ExperimentConfigRepository {

    private final ExperimentConfigMapper experimentConfigMapper;

    private final JsonUtil jsonUtil;

    @Override
    public void save(String experimentId, ExperimentMetadata metadata) {
        ExperimentConfigEntity entity = buildExperimentConfigEntity(experimentId, metadata);
        experimentConfigMapper.upsert(entity);
    }

    @Override
    public Optional<ExperimentMetadata> findById(String experimentId) {
        ExperimentConfigEntity entity = experimentConfigMapper.selectByExperimentId(experimentId);
        if (entity == null) {
            return Optional.empty();
        }
        return Optional.of(parseExperimentMetadata(entity.getMetadataJson()));
    }

    @Override
    public void delete(String experimentId) {
        experimentConfigMapper.deleteByExperimentId(experimentId);
    }

    @Override
    public List<String> findAllExperimentIds() {
        return experimentConfigMapper.selectAllExperimentIds();
    }

    private ExperimentConfigEntity buildExperimentConfigEntity(String experimentId, ExperimentMetadata metadata) {
        ExperimentConfigEntity entity = new ExperimentConfigEntity();
        entity.setExperimentId(experimentId);
        entity.setConfigVersion(metadata.getConfigVersion());
        entity.setMetadataJson(jsonUtil.toJson(metadata));
        return entity;
    }

    private ExperimentMetadata parseExperimentMetadata(String metadataJson) {
        return jsonUtil.toObject(metadataJson, ExperimentMetadata.class);
    }
}
