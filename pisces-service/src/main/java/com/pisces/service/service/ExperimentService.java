package com.pisces.service.service;

import com.pisces.common.model.Experiment;
import com.pisces.common.request.ExperimentApprovalEscalationAcknowledgeRequest;
import com.pisces.common.response.AuditLogResponse;
import com.pisces.common.response.ExperimentApprovalEscalationOperationResponse;
import com.pisces.common.response.ExperimentApprovalEscalationResponse;
import com.pisces.common.response.ExperimentApprovalEscalationStatusResponse;
import com.pisces.common.response.ExperimentApprovalTaskResponse;
import com.pisces.common.response.ExperimentConfigDraftApprovalResponse;
import com.pisces.common.response.ExperimentConfigDraftResponse;
import com.pisces.common.response.ExperimentConfigVersionResponse;
import com.pisces.common.response.ExperimentResponse;
import com.pisces.common.request.ExperimentApprovalStatusUpdateRequest;
import com.pisces.common.request.ExperimentConfigDraftSaveRequest;
import com.pisces.common.request.ExperimentConfigPublishRequest;
import com.pisces.common.request.ExperimentConfigRollbackRequest;
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
     * 查询实验审计日志
     *
     * @param experimentId 实验ID
     * @return 审计日志列表
     */
    List<AuditLogResponse> listExperimentAuditLogs(String experimentId);

    /**
     * 查询实验配置版本列表
     *
     * @param experimentId 实验ID
     * @return 配置版本列表
     */
    List<ExperimentConfigVersionResponse> listConfigVersions(String experimentId);

    /**
     * 发布当前实验配置版本
     *
     * @param experimentId 实验ID
     * @param request 发布请求
     * @return 配置版本
     */
    ExperimentConfigVersionResponse publishConfigVersion(String experimentId, ExperimentConfigPublishRequest request);

    /**
     * 回滚实验配置版本
     *
     * @param experimentId 实验ID
     * @param request 回滚请求
     * @return 回滚后的新配置版本
     */
    ExperimentConfigVersionResponse rollbackConfigVersion(String experimentId, ExperimentConfigRollbackRequest request);

    /**
     * 查询实验配置草稿
     *
     * @param experimentId 实验ID
     * @return 配置草稿
     */
    ExperimentConfigDraftResponse getConfigDraft(String experimentId);

    /**
     * 查询实验配置草稿审批历史
     *
     * @param experimentId 实验ID
     * @return 草稿审批记录列表
     */
    List<ExperimentConfigDraftApprovalResponse> listConfigDraftApprovals(String experimentId);

    /**
     * 保存实验配置草稿
     *
     * @param experimentId 实验ID
     * @param request 草稿保存请求
     * @return 配置草稿
     */
    ExperimentConfigDraftResponse saveConfigDraft(String experimentId, ExperimentConfigDraftSaveRequest request);

    /**
     * 发布实验配置草稿
     *
     * @param experimentId 实验ID
     * @param request 发布请求
     * @return 发布后的配置版本
     */
    ExperimentConfigVersionResponse publishConfigDraft(String experimentId, ExperimentConfigPublishRequest request);
    
    /**
     * 获取实验列表
     */
    List<Experiment> listExperiments();

    /**
     * 按可见应用、状态、归属人查询实验列表
     *
     * @param status 单个实验状态
     * @param statuses 多个实验状态
     * @param appId 应用ID
     * @param owner 归属人
     * @return 符合条件的实验列表
     */
    List<Experiment> listExperiments(String status, List<String> statuses, String appId, String owner);

    /**
     * 查询实验审批任务
     *
     * @param appId 应用ID
     * @param owner 负责人
     * @param approvalStatus 审批状态，默认 PENDING，传 ALL 返回所有需要审批的任务
     * @return 审批任务列表
     */
    List<ExperimentApprovalTaskResponse> listApprovalTasks(String appId, String owner, String approvalStatus);

    /**
     * 扫描逾期审批任务并创建升级告警
     *
     * @param appId 应用ID
     * @param owner 负责人
     * @return 告警记录列表
     */
    List<ExperimentApprovalEscalationResponse> scanApprovalEscalations(String appId, String owner);

    /**
     * 查询审批升级告警
     *
     * @param appId 应用ID
     * @param owner 负责人
     * @param escalationStatus 告警状态，默认 OPEN，传 ALL 返回全部
     * @return 告警记录列表
     */
    List<ExperimentApprovalEscalationResponse> listApprovalEscalations(String appId, String owner,
                                                                       String escalationStatus);

    /**
     * 查询审批升级告警投递状态汇总
     *
     * @param appId 应用ID
     * @param owner 负责人
     * @return 投递状态汇总
     */
    ExperimentApprovalEscalationStatusResponse getApprovalEscalationStatus(String appId, String owner);

    /**
     * 确认审批升级告警
     *
     * @param escalationId 告警ID
     * @param request 确认请求
     * @return 更新后的告警记录
     */
    ExperimentApprovalEscalationResponse acknowledgeApprovalEscalation(
            String escalationId, ExperimentApprovalEscalationAcknowledgeRequest request);

    /**
     * 重投单条审批升级告警死信
     *
     * @param escalationId 告警ID
     * @param operator 操作人
     * @return 操作结果
     */
    ExperimentApprovalEscalationOperationResponse retryApprovalEscalationNotification(
            String escalationId, String operator);

    /**
     * 批量重投审批升级告警死信
     *
     * @param appId 应用ID
     * @param owner 负责人
     * @param operator 操作人
     * @return 操作结果
     */
    ExperimentApprovalEscalationOperationResponse retryDeadApprovalEscalationNotifications(
            String appId, String owner, String operator);
    
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

    /**
     * 更新实验审批状态
     *
     * @param experimentId 实验ID
     * @param request 审批状态更新请求
     */
    void updateApprovalStatus(String experimentId, ExperimentApprovalStatusUpdateRequest request);
}
