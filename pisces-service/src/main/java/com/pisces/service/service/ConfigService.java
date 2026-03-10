package com.pisces.service.service;

import com.pisces.common.model.ExperimentLayer;
import com.pisces.common.model.ExperimentMetadata;

import java.util.List;
import java.util.function.Consumer;

/**
 * 配置管理服务接口（基于Zookeeper）
 */
public interface ConfigService {

    /**
     * 保存实验配置
     */
    void saveExperimentConfig(String experimentId, ExperimentMetadata metadata) throws Exception;

    /**
     * 获取实验配置
     */
    ExperimentMetadata getExperimentConfig(String experimentId);

    /**
     * 删除实验配置
     */
    void deleteExperimentConfig(String experimentId) throws Exception;

    /**
     * 获取所有实验ID列表
     */
    List<String> getAllExperimentIds() throws Exception;

    /**
     * 注册配置变更监听器
     */
    void addConfigChangeListener(String experimentId, Consumer<ExperimentMetadata> listener);

    // ── 流量分层管理 ──────────────────────────────────────────────────────────

    /**
     * 保存分层配置
     */
    void saveLayerConfig(String layerId, ExperimentLayer layer) throws Exception;

    /**
     * 获取分层配置
     */
    ExperimentLayer getLayerConfig(String layerId);

    /**
     * 删除分层配置
     */
    void deleteLayerConfig(String layerId) throws Exception;
}

