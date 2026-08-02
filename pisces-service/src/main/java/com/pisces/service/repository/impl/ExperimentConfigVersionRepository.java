package com.pisces.service.repository.impl;

import com.pisces.common.model.ExperimentConfigVersion;
import com.pisces.common.model.ExperimentMetadata;
import com.pisces.service.entity.ExperimentConfigVersionEntity;
import com.pisces.service.mapper.ExperimentConfigVersionMapper;
import com.pisces.service.util.JsonUtil;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 数据库实验配置版本仓库
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/16 19:20
 */
@Repository
@AllArgsConstructor
public class ExperimentConfigVersionRepository
        implements com.pisces.service.repository.ExperimentConfigVersionRepository {

    private final ExperimentConfigVersionMapper experimentConfigVersionMapper;

    private final JsonUtil jsonUtil;

    @Override
    public ExperimentConfigVersion save(String experimentId, ExperimentMetadata metadata, String publishedBy,
                                        String publishComment, Long sourceConfigVersion, String sourceType) {
        ExperimentConfigVersionEntity entity = buildEntity(experimentId, metadata, publishedBy, publishComment,
                sourceConfigVersion, sourceType);
        experimentConfigVersionMapper.upsert(entity);
        return findByExperimentIdAndVersion(experimentId, metadata.getConfigVersion())
                .orElseGet(() -> buildVersion(entity));
    }

    @Override
    public List<ExperimentConfigVersion> listByExperimentId(String experimentId) {
        return experimentConfigVersionMapper.selectByExperimentId(experimentId).stream()
                .map(this::buildVersion)
                .toList();
    }

    @Override
    public Optional<ExperimentConfigVersion> findByExperimentIdAndVersion(String experimentId, long configVersion) {
        ExperimentConfigVersionEntity entity =
                experimentConfigVersionMapper.selectByExperimentIdAndVersion(experimentId, configVersion);
        return entity == null ? Optional.empty() : Optional.of(buildVersion(entity));
    }

    private ExperimentConfigVersionEntity buildEntity(String experimentId, ExperimentMetadata metadata,
                                                      String publishedBy, String publishComment,
                                                      Long sourceConfigVersion, String sourceType) {
        ExperimentConfigVersionEntity entity = new ExperimentConfigVersionEntity();
        entity.setExperimentId(experimentId);
        entity.setConfigVersion(metadata.getConfigVersion());
        entity.setMetadataJson(jsonUtil.toJson(metadata));
        entity.setPublishedBy(publishedBy);
        entity.setPublishComment(publishComment);
        entity.setSourceConfigVersion(sourceConfigVersion);
        entity.setSourceType(sourceType);
        return entity;
    }

    private ExperimentConfigVersion buildVersion(ExperimentConfigVersionEntity entity) {
        ExperimentConfigVersion version = new ExperimentConfigVersion();
        version.setExperimentId(entity.getExperimentId());
        version.setConfigVersion(entity.getConfigVersion());
        version.setMetadata(jsonUtil.toObject(entity.getMetadataJson(), ExperimentMetadata.class));
        version.setPublishedBy(entity.getPublishedBy());
        version.setPublishComment(entity.getPublishComment());
        version.setSourceConfigVersion(entity.getSourceConfigVersion());
        version.setSourceType(entity.getSourceType());
        version.setPublishedAt(entity.getCreatedAt());
        return version;
    }
}
