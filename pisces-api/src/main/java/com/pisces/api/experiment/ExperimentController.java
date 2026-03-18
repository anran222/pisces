package com.pisces.api.experiment;

import com.pisces.common.model.Experiment;
import com.pisces.common.request.ExperimentConclusionStatusUpdateRequest;
import com.pisces.common.request.ExperimentCreateRequest;
import com.pisces.common.response.BaseResponse;
import com.pisces.common.response.ExperimentResponse;
import com.pisces.service.annotation.NoTokenRequired;
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
     * 获取实验列表
     * @param status 可选，按状态筛选：DRAFT（草稿）、RUNNING（运行中）、PAUSED（已暂停）、STOPPED（已停止）
     * @param statuses 可选，按多个状态筛选（逗号分隔，如：PAUSED,STOPPED）
     */
    @GetMapping
    public BaseResponse<List<Experiment>> listExperiments(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String statuses) {
        List<Experiment> experiments;
        
        if (statuses != null && !statuses.trim().isEmpty()) {
            // 多状态查询
            List<String> statusList = java.util.Arrays.asList(statuses.split(","));
            experiments = experimentService.listExperimentsByStatuses(statusList);
        } else if (status != null && !status.trim().isEmpty()) {
            // 单状态查询
            experiments = experimentService.listExperimentsByStatus(status);
        } else {
            // 查询全部
            experiments = experimentService.listExperiments();
        }
        
        return BaseResponse.of(experiments);
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
