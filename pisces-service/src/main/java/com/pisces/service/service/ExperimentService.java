package com.pisces.service.service;

import com.pisces.common.model.Experiment;
import com.pisces.common.response.ExperimentResponse;
import com.pisces.common.request.ExperimentCreateRequest;
import com.pisces.common.request.ExperimentConclusionStatusUpdateRequest;

import java.util.List;

/**
 * 实验管理服务接口（无用户系统版本）
 */
public interface ExperimentService {
    
    /**
     * 创建实验
     */
    Experiment createExperiment(ExperimentCreateRequest request);
    
    /**
     * 更新实验
     */
    Experiment updateExperiment(String experimentId, ExperimentCreateRequest request);
    
    /**
     * 启动实验
     */
    void startExperiment(String experimentId);
    
    /**
     * 停止实验
     */
    void stopExperiment(String experimentId);
    
    /**
     * 暂停实验
     */
    void pauseExperiment(String experimentId);
    
    /**
     * 恢复实验（从暂停状态恢复到运行状态）
     */
    void resumeExperiment(String experimentId);
    
    /**
     * 获取实验
     */
    ExperimentResponse getExperiment(String experimentId);
    
    /**
     * 获取实验列表
     */
    List<Experiment> listExperiments();
    
    /**
     * 根据状态查询实验列表
     * @param status 实验状态：DRAFT（草稿）、RUNNING（运行中）、PAUSED（已暂停）、STOPPED（已停止）
     * @return 符合状态条件的实验列表
     */
    List<Experiment> listExperimentsByStatus(String status);
    
    /**
     * 根据多个状态查询实验列表
     * @param statuses 实验状态列表
     * @return 符合状态条件的实验列表
     */
    List<Experiment> listExperimentsByStatuses(List<String> statuses);
    
    /**
     * 删除实验
     */
    void deleteExperiment(String experimentId);
    
    /**
     * 批量暂停实验
     * @param experimentIds 实验ID列表
     * @return 操作结果，包含成功和失败的实验ID
     */
    java.util.Map<String, Object> batchPauseExperiments(List<String> experimentIds);
    
    /**
     * 批量停止实验
     * @param experimentIds 实验ID列表
     * @return 操作结果，包含成功和失败的实验ID
     */
    java.util.Map<String, Object> batchStopExperiments(List<String> experimentIds);
    
    /**
     * 批量恢复实验
     * @param experimentIds 实验ID列表
     * @return 操作结果，包含成功和失败的实验ID
     */
    java.util.Map<String, Object> batchResumeExperiments(List<String> experimentIds);
    
    /**
     * 批量删除实验
     * @param experimentIds 实验ID列表
     * @return 操作结果，包含成功和失败的实验ID
     */
    java.util.Map<String, Object> batchDeleteExperiments(List<String> experimentIds);

    /**
     * 更新实验结论状态
     *
     * @param experimentId 实验ID
     * @param request 结论状态更新请求
     */
    void updateConclusionStatus(String experimentId, ExperimentConclusionStatusUpdateRequest request);
}
