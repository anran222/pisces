package com.pisces.service.service;

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
}

