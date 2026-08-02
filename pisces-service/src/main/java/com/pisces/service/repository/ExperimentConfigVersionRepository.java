package com.pisces.service.repository;

import com.pisces.common.model.ExperimentConfigVersion;
import com.pisces.common.model.ExperimentMetadata;

import java.util.List;
import java.util.Optional;

/**
 * 实验配置版本仓库
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/16 19:20
 */
public interface ExperimentConfigVersionRepository {

    /**
     * 保存配置版本
     *
     * @param experimentId 实验ID
     * @param metadata 配置快照
     * @param publishedBy 发布人
     * @param publishComment 发布备注
     * @param sourceConfigVersion 来源配置版本
     * @param sourceType 来源类型
     * @return 配置版本
     */
    ExperimentConfigVersion save(String experimentId, ExperimentMetadata metadata, String publishedBy,
                                 String publishComment, Long sourceConfigVersion, String sourceType);

    /**
     * 查询实验配置版本列表
     *
     * @param experimentId 实验ID
     * @return 配置版本列表
     */
    List<ExperimentConfigVersion> listByExperimentId(String experimentId);

    /**
     * 查询指定配置版本
     *
     * @param experimentId 实验ID
     * @param configVersion 配置版本
     * @return 配置版本
     */
    Optional<ExperimentConfigVersion> findByExperimentIdAndVersion(String experimentId, long configVersion);
}
