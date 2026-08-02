package com.pisces.service.repository.impl;

import com.pisces.common.model.ExperimentConfigDraft;
import com.pisces.common.model.ExperimentMetadata;
import com.pisces.service.entity.ExperimentConfigDraftEntity;
import com.pisces.service.mapper.ExperimentConfigDraftMapper;
import com.pisces.service.util.JsonUtil;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 数据库配置草稿仓库
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/17 09:09
 */
@Repository
@AllArgsConstructor
public class ExperimentConfigDraftRepository
        implements com.pisces.service.repository.ExperimentConfigDraftRepository {

    private final ExperimentConfigDraftMapper experimentConfigDraftMapper;

    private final JsonUtil jsonUtil;

    @Override
    public ExperimentConfigDraft save(String experimentId, ExperimentMetadata metadata, long baseConfigVersion,
                                      String updatedBy, String draftComment) {
        ExperimentConfigDraftEntity entity = buildEntity(experimentId, metadata, baseConfigVersion, updatedBy,
                draftComment);
        experimentConfigDraftMapper.upsert(entity);
        return findByExperimentId(experimentId).orElseGet(() -> buildDraft(entity));
    }

    @Override
    public Optional<ExperimentConfigDraft> findByExperimentId(String experimentId) {
        ExperimentConfigDraftEntity entity = experimentConfigDraftMapper.selectByExperimentId(experimentId);
        return entity == null ? Optional.empty() : Optional.of(buildDraft(entity));
    }

    @Override
    public void delete(String experimentId) {
        experimentConfigDraftMapper.deleteByExperimentId(experimentId);
    }

    private ExperimentConfigDraftEntity buildEntity(String experimentId, ExperimentMetadata metadata,
                                                    long baseConfigVersion, String updatedBy, String draftComment) {
        ExperimentConfigDraftEntity entity = new ExperimentConfigDraftEntity();
        entity.setExperimentId(experimentId);
        entity.setBaseConfigVersion(baseConfigVersion);
        entity.setMetadataJson(jsonUtil.toJson(metadata));
        entity.setUpdatedBy(updatedBy);
        entity.setDraftComment(draftComment);
        return entity;
    }

    private ExperimentConfigDraft buildDraft(ExperimentConfigDraftEntity entity) {
        ExperimentConfigDraft draft = new ExperimentConfigDraft();
        draft.setExperimentId(entity.getExperimentId());
        draft.setDraftVersion(entity.getDraftVersion());
        draft.setBaseConfigVersion(entity.getBaseConfigVersion());
        draft.setMetadata(jsonUtil.toObject(entity.getMetadataJson(), ExperimentMetadata.class));
        draft.setUpdatedBy(entity.getUpdatedBy());
        draft.setDraftComment(entity.getDraftComment());
        draft.setCreatedAt(entity.getCreatedAt());
        draft.setUpdatedAt(entity.getUpdatedAt());
        return draft;
    }
}
