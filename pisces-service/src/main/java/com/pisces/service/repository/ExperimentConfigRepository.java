package com.pisces.service.repository;

import com.pisces.common.model.ExperimentMetadata;

import java.util.List;
import java.util.Optional;

/**
 * 实验配置仓库
 */
public interface ExperimentConfigRepository {

    /**
     * 保存实验配置
     *
     * @param experimentId 实验ID
     * @param metadata 实验元数据
     */
    void save(String experimentId, ExperimentMetadata metadata);

    /**
     * 查询实验配置
     *
     * @param experimentId 实验ID
     * @return 实验元数据
     */
    Optional<ExperimentMetadata> findById(String experimentId);

    /**
     * 删除实验配置
     *
     * @param experimentId 实验ID
     */
    void delete(String experimentId);

    /**
     * 查询所有实验ID
     *
     * @return 实验ID列表
     */
    List<String> findAllExperimentIds();
}
