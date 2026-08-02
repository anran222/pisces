package com.pisces.api.experiment;

import com.pisces.common.model.Experiment;
import com.pisces.common.request.ExperimentApprovalEscalationAcknowledgeRequest;
import com.pisces.common.request.ExperimentApprovalStatusUpdateRequest;
import com.pisces.common.request.ExperimentConfigDraftSaveRequest;
import com.pisces.common.request.ExperimentConfigPublishRequest;
import com.pisces.common.request.ExperimentConfigRollbackRequest;
import com.pisces.common.request.ExperimentConclusionStatusUpdateRequest;
import com.pisces.common.request.ExperimentCreateRequest;
import com.pisces.common.response.AuditLogResponse;
import com.pisces.common.response.BaseResponse;
import com.pisces.common.response.ExperimentApprovalEscalationOperationResponse;
import com.pisces.common.response.ExperimentApprovalEscalationResponse;
import com.pisces.common.response.ExperimentApprovalEscalationStatusResponse;
import com.pisces.common.response.ExperimentApprovalTaskResponse;
import com.pisces.common.response.ExperimentConfigDraftApprovalResponse;
import com.pisces.common.response.ExperimentConfigDraftResponse;
import com.pisces.common.response.ExperimentConfigVersionResponse;
import com.pisces.common.response.ExperimentResponse;
import com.pisces.service.annotation.ApiKeyScopeRequired;
import com.pisces.service.annotation.NoTokenRequired;
import com.pisces.service.security.ApiKeyScope;
import com.pisces.service.service.ExperimentService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 实验管理控制器（无用户系统版本）
 */
@RestController
@RequestMapping("/experiments")
@ApiKeyScopeRequired(ApiKeyScope.MANAGEMENT)
@NoTokenRequired  // 所有接口无需Token认证
public class ExperimentController {
    
    @Autowired
    private ExperimentService experimentService;
    
    /**
     * 创建实验
     */
    @PostMapping
    public BaseResponse<Experiment> createExperiment(@Valid @RequestBody ExperimentCreateRequest request) {
        Experiment experiment = experimentService.createExperiment(request);
        return BaseResponse.of("实验创建成功", experiment);
    }
    
    /**
     * 更新实验
     */
    @PutMapping("/{id}")
    public BaseResponse<Experiment> updateExperiment(@PathVariable String id, 
                                                     @Valid @RequestBody ExperimentCreateRequest request) {
        Experiment experiment = experimentService.updateExperiment(id, request);
        return BaseResponse.of("实验更新成功", experiment);
    }
    
    /**
     * 获取实验
     */
    @GetMapping("/{id}")
    public BaseResponse<ExperimentResponse> getExperiment(@PathVariable String id) {
        ExperimentResponse response = experimentService.getExperiment(id);
        return BaseResponse.of(response);
    }

    /**
     * 查询实验审计日志
     */
    @GetMapping("/{id}/audit-logs")
    public BaseResponse<List<AuditLogResponse>> listExperimentAuditLogs(@PathVariable String id) {
        List<AuditLogResponse> response = experimentService.listExperimentAuditLogs(id);
        return BaseResponse.of(response);
    }

    /**
     * 查询实验配置版本
     */
    @GetMapping("/{id}/config-versions")
    public BaseResponse<List<ExperimentConfigVersionResponse>> listConfigVersions(@PathVariable String id) {
        List<ExperimentConfigVersionResponse> response = experimentService.listConfigVersions(id);
        return BaseResponse.of(response);
    }

    /**
     * 发布当前实验配置
     */
    @PostMapping("/{id}/config-versions/publish")
    public BaseResponse<ExperimentConfigVersionResponse> publishConfigVersion(
            @PathVariable String id,
            @RequestBody(required = false) ExperimentConfigPublishRequest request) {
        ExperimentConfigVersionResponse response = experimentService.publishConfigVersion(id, request);
        return BaseResponse.of("实验配置发布成功", response);
    }

    /**
     * 回滚实验配置
     */
    @PostMapping("/{id}/config-versions/rollback")
    public BaseResponse<ExperimentConfigVersionResponse> rollbackConfigVersion(
            @PathVariable String id,
            @Valid @RequestBody ExperimentConfigRollbackRequest request) {
        ExperimentConfigVersionResponse response = experimentService.rollbackConfigVersion(id, request);
        return BaseResponse.of("实验配置回滚成功", response);
    }

    /**
     * 查询实验配置草稿
     */
    @GetMapping("/{id}/config-draft")
    public BaseResponse<ExperimentConfigDraftResponse> getConfigDraft(@PathVariable String id) {
        ExperimentConfigDraftResponse response = experimentService.getConfigDraft(id);
        return BaseResponse.of(response);
    }

    /**
     * 查询实验配置草稿审批历史
     */
    @GetMapping("/{id}/config-draft/approvals")
    public BaseResponse<List<ExperimentConfigDraftApprovalResponse>> listConfigDraftApprovals(
            @PathVariable String id) {
        List<ExperimentConfigDraftApprovalResponse> response = experimentService.listConfigDraftApprovals(id);
        return BaseResponse.of(response);
    }

    /**
     * 保存实验配置草稿
     */
    @PutMapping("/{id}/config-draft")
    public BaseResponse<ExperimentConfigDraftResponse> saveConfigDraft(
            @PathVariable String id,
            @Valid @RequestBody ExperimentConfigDraftSaveRequest request) {
        ExperimentConfigDraftResponse response = experimentService.saveConfigDraft(id, request);
        return BaseResponse.of("实验配置草稿保存成功", response);
    }

    /**
     * 发布实验配置草稿
     */
    @PostMapping("/{id}/config-draft/publish")
    public BaseResponse<ExperimentConfigVersionResponse> publishConfigDraft(
            @PathVariable String id,
            @RequestBody(required = false) ExperimentConfigPublishRequest request) {
        ExperimentConfigVersionResponse response = experimentService.publishConfigDraft(id, request);
        return BaseResponse.of("实验配置草稿发布成功", response);
    }

    /**
     * 查询实验审批任务
     *
     * @param appId 可选，按应用筛选；非 admin key 仍只能看到自身应用
     * @param owner 可选，按负责人筛选
     * @param approvalStatus 可选，默认 PENDING；传 ALL 返回所有需要审批的任务
     */
    @GetMapping("/approval-tasks")
    public BaseResponse<List<ExperimentApprovalTaskResponse>> listApprovalTasks(
            @RequestParam(value = "appId", required = false) String appId,
            @RequestParam(value = "owner", required = false) String owner,
            @RequestParam(value = "approvalStatus", required = false) String approvalStatus) {
        List<ExperimentApprovalTaskResponse> response =
                experimentService.listApprovalTasks(appId, owner, approvalStatus);
        return BaseResponse.of(response);
    }

    /**
     * 扫描逾期审批任务并创建升级告警
     *
     * @param appId 可选，按应用筛选；非 admin key 仍只能扫描自身应用
     * @param owner 可选，按负责人筛选
     */
    @PostMapping("/approval-escalations/scan")
    public BaseResponse<List<ExperimentApprovalEscalationResponse>> scanApprovalEscalations(
            @RequestParam(value = "appId", required = false) String appId,
            @RequestParam(value = "owner", required = false) String owner) {
        List<ExperimentApprovalEscalationResponse> response =
                experimentService.scanApprovalEscalations(appId, owner);
        return BaseResponse.of("审批升级告警扫描完成", response);
    }

    /**
     * 查询审批升级告警投递状态
     *
     * @param appId 可选，按应用筛选；非 admin key 仍只能查询自身应用
     * @param owner 可选，按负责人筛选
     */
    @GetMapping("/approval-escalations/status")
    public BaseResponse<ExperimentApprovalEscalationStatusResponse> getApprovalEscalationStatus(
            @RequestParam(value = "appId", required = false) String appId,
            @RequestParam(value = "owner", required = false) String owner) {
        ExperimentApprovalEscalationStatusResponse response =
                experimentService.getApprovalEscalationStatus(appId, owner);
        return BaseResponse.of(response);
    }

    /**
     * 批量重投审批升级告警死信
     *
     * @param appId 可选，按应用筛选；非 admin key 只能重投自身应用
     * @param owner 可选，按负责人筛选
     * @param operator 操作人
     */
    @PostMapping("/approval-escalations/dead/retry")
    public BaseResponse<ExperimentApprovalEscalationOperationResponse> retryDeadApprovalEscalationNotifications(
            @RequestParam(value = "appId", required = false) String appId,
            @RequestParam(value = "owner", required = false) String owner,
            @RequestParam(value = "operator", required = false, defaultValue = "web-ui") String operator) {
        ExperimentApprovalEscalationOperationResponse response =
                experimentService.retryDeadApprovalEscalationNotifications(appId, owner, operator);
        return BaseResponse.of("审批升级告警死信已重新入队", response);
    }

    /**
     * 查询审批升级告警
     *
     * @param appId 可选，按应用筛选；非 admin key 仍只能查询自身应用
     * @param owner 可选，按负责人筛选
     * @param escalationStatus 可选，默认 OPEN；传 ALL 返回全部
     */
    @GetMapping("/approval-escalations")
    public BaseResponse<List<ExperimentApprovalEscalationResponse>> listApprovalEscalations(
            @RequestParam(value = "appId", required = false) String appId,
            @RequestParam(value = "owner", required = false) String owner,
            @RequestParam(value = "escalationStatus", required = false) String escalationStatus) {
        List<ExperimentApprovalEscalationResponse> response =
                experimentService.listApprovalEscalations(appId, owner, escalationStatus);
        return BaseResponse.of(response);
    }

    /**
     * 确认审批升级告警
     */
    @PostMapping("/approval-escalations/{escalationId}/ack")
    public BaseResponse<ExperimentApprovalEscalationResponse> acknowledgeApprovalEscalation(
            @PathVariable String escalationId,
            @RequestBody(required = false) ExperimentApprovalEscalationAcknowledgeRequest request) {
        ExperimentApprovalEscalationResponse response =
                experimentService.acknowledgeApprovalEscalation(escalationId, request);
        return BaseResponse.of("审批升级告警已确认", response);
    }

    /**
     * 重投单条审批升级告警死信
     */
    @PostMapping("/approval-escalations/{escalationId}/notification/retry")
    public BaseResponse<ExperimentApprovalEscalationOperationResponse> retryApprovalEscalationNotification(
            @PathVariable String escalationId,
            @RequestParam(value = "operator", required = false, defaultValue = "web-ui") String operator) {
        ExperimentApprovalEscalationOperationResponse response =
                experimentService.retryApprovalEscalationNotification(escalationId, operator);
        return BaseResponse.of("审批升级告警死信已重新入队", response);
    }
    
    /**
     * 获取实验列表
     * @param status 可选，按状态筛选：DRAFT（草稿）、RUNNING（运行中）、PAUSED（已暂停）、STOPPED（已停止）
     * @param statuses 可选，按多个状态筛选（逗号分隔，如：PAUSED,STOPPED）
     * @param appId 可选，按应用筛选；非 admin key 仍只能看到自身应用
     * @param owner 可选，按负责人筛选
     */
    @GetMapping
    public BaseResponse<List<Experiment>> listExperiments(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "statuses", required = false) String statuses,
            @RequestParam(value = "appId", required = false) String appId,
            @RequestParam(value = "owner", required = false) String owner) {
        List<Experiment> experiments = experimentService.listExperiments(
                status, splitStatuses(statuses), appId, owner);
        return BaseResponse.of(experiments);
    }

    private List<String> splitStatuses(String statuses) {
        if (statuses == null || statuses.trim().isEmpty()) {
            return List.of();
        }
        return java.util.Arrays.stream(statuses.split(","))
                .map(String::trim)
                .filter(status -> !status.isEmpty())
                .toList();
    }
    
    /**
     * 根据状态查询实验列表
     * @param status 实验状态：DRAFT（草稿）、RUNNING（运行中）、PAUSED（已暂停）、STOPPED（已停止）
     */
    @GetMapping("/status/{status}")
    public BaseResponse<List<Experiment>> listExperimentsByStatus(@PathVariable String status) {
        List<Experiment> experiments = experimentService.listExperimentsByStatus(status);
        return BaseResponse.of(experiments);
    }
    
    /**
     * 启动实验
     */
    @PostMapping("/{id}/start")
    public BaseResponse<Void> startExperiment(@PathVariable String id) {
        experimentService.startExperiment(id);
        return BaseResponse.of("实验启动成功", null);
    }
    
    /**
     * 停止实验
     */
    @PostMapping("/{id}/stop")
    public BaseResponse<Void> stopExperiment(@PathVariable String id) {
        experimentService.stopExperiment(id);
        return BaseResponse.of("实验停止成功", null);
    }
    
    /**
     * 暂停实验
     */
    @PostMapping("/{id}/pause")
    public BaseResponse<Void> pauseExperiment(@PathVariable String id) {
        experimentService.pauseExperiment(id);
        return BaseResponse.of("实验暂停成功", null);
    }
    
    /**
     * 恢复实验（从暂停状态恢复到运行状态）
     */
    @PostMapping("/{id}/resume")
    public BaseResponse<Void> resumeExperiment(@PathVariable String id) {
        experimentService.resumeExperiment(id);
        return BaseResponse.of("实验恢复成功", null);
    }

    /**
     * 更新实验结论状态
     */
    @PostMapping("/{id}/conclusion-status")
    public BaseResponse<Void> updateConclusionStatus(@PathVariable String id,
                                                     @Valid @RequestBody ExperimentConclusionStatusUpdateRequest request) {
        experimentService.updateConclusionStatus(id, request);
        return BaseResponse.of("实验结论状态更新成功", null);
    }

    /**
     * 更新实验审批状态
     */
    @PostMapping("/{id}/approval-status")
    public BaseResponse<Void> updateApprovalStatus(@PathVariable String id,
                                                   @Valid @RequestBody ExperimentApprovalStatusUpdateRequest request) {
        experimentService.updateApprovalStatus(id, request);
        return BaseResponse.of("实验审批状态更新成功", null);
    }
    
    /**
     * 删除实验
     */
    @DeleteMapping("/{id}")
    public BaseResponse<Void> deleteExperiment(@PathVariable String id) {
        experimentService.deleteExperiment(id);
        return BaseResponse.of("实验删除成功", null);
    }
    
    /**
     * 批量暂停实验
     * @param experimentIds 实验ID列表
     */
    @PostMapping("/batch/pause")
    public BaseResponse<java.util.Map<String, Object>> batchPauseExperiments(
            @RequestBody java.util.List<String> experimentIds) {
        java.util.Map<String, Object> result = experimentService.batchPauseExperiments(experimentIds);
        return BaseResponse.of((String) result.get("message"), result);
    }
    
    /**
     * 批量停止实验
     * @param experimentIds 实验ID列表
     */
    @PostMapping("/batch/stop")
    public BaseResponse<java.util.Map<String, Object>> batchStopExperiments(
            @RequestBody java.util.List<String> experimentIds) {
        java.util.Map<String, Object> result = experimentService.batchStopExperiments(experimentIds);
        return BaseResponse.of((String) result.get("message"), result);
    }
    
    /**
     * 批量恢复实验
     * @param experimentIds 实验ID列表
     */
    @PostMapping("/batch/resume")
    public BaseResponse<java.util.Map<String, Object>> batchResumeExperiments(
            @RequestBody java.util.List<String> experimentIds) {
        java.util.Map<String, Object> result = experimentService.batchResumeExperiments(experimentIds);
        return BaseResponse.of((String) result.get("message"), result);
    }
    
    /**
     * 批量删除实验
     * @param experimentIds 实验ID列表
     */
    @PostMapping("/batch/delete")
    public BaseResponse<java.util.Map<String, Object>> batchDeleteExperiments(
            @RequestBody java.util.List<String> experimentIds) {
        java.util.Map<String, Object> result = experimentService.batchDeleteExperiments(experimentIds);
        return BaseResponse.of((String) result.get("message"), result);
    }
}
