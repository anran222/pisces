package com.pisces.service.repository;

import com.pisces.common.model.ExperimentConfigDraft;
import com.pisces.common.model.ExperimentMetadata;

import java.util.Optional;

/**
 * 实验配置草稿仓库
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/17 09:09
 */
public interface ExperimentConfigDraftRepository {

    /**
     * 保存配置草稿
     *
     * @param experimentId 实验ID
     * @param metadata 草稿配置
     * @param baseConfigVersion 基线配置版本
     * @param updatedBy 更新人
     * @param draftComment 草稿备注
     * @return 配置草稿
     */
    ExperimentConfigDraft save(String experimentId, ExperimentMetadata metadata, long baseConfigVersion,
                               String updatedBy, String draftComment);

    /**
     * 查询配置草稿
     *
     * @param experimentId 实验ID
     * @return 配置草稿
     */
    Optional<ExperimentConfigDraft> findByExperimentId(String experimentId);

    /**
     * 删除配置草稿
     *
     * @param experimentId 实验ID
     */
    void delete(String experimentId);
}
