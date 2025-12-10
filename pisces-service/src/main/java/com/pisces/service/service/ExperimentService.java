package com.pisces.service.service;

import com.pisces.common.model.Experiment;
import com.pisces.common.response.ExperimentResponse;
import com.pisces.common.request.ExperimentCreateRequest;

import java.util.List;

/**
 * 实验管理服务接口
 */
public interface ExperimentService {
    
    /**
     * 创建实验
     */
    Experiment createExperiment(ExperimentCreateRequest request, String creatorUsername);
    
    /**
     * 更新实验
     */
    Experiment updateExperiment(String experimentId, ExperimentCreateRequest request, String username);
    
    /**
     * 启动实验
     */
    void startExperiment(String experimentId, String username);
    
    /**
     * 停止实验
     */
    void stopExperiment(String experimentId, String username);
    
    /**
     * 暂停实验
     */
    void pauseExperiment(String experimentId, String username);
    
    /**
     * 获取实验
     */
    ExperimentResponse getExperiment(String experimentId);
    
    /**
     * 获取实验列表
     */
    List<Experiment> listExperiments();
    
    /**
     * 删除实验
     */
    void deleteExperiment(String experimentId, String username);
}

