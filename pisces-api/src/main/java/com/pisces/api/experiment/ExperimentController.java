package com.pisces.api.experiment;

import com.pisces.common.model.Experiment;
import com.pisces.common.request.ExperimentCreateRequest;
import com.pisces.common.response.BaseResponse;
import com.pisces.common.response.ExperimentResponse;
import com.pisces.service.context.TokenContext;
import com.pisces.service.service.ExperimentService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 实验管理控制器
 */
@RestController
@RequestMapping("/experiments")
public class ExperimentController {
    
    @Autowired
    private ExperimentService experimentService;
    
    /**
     * 创建实验
     */
    @PostMapping
    public BaseResponse<Experiment> createExperiment(@Valid @RequestBody ExperimentCreateRequest request) {
        String username = TokenContext.getCurrentUsername();
        Experiment experiment = experimentService.createExperiment(request, username);
        return BaseResponse.of("实验创建成功", experiment);
    }
    
    /**
     * 更新实验
     */
    @PutMapping("/{id}")
    public BaseResponse<Experiment> updateExperiment(@PathVariable String id, 
                                                     @Valid @RequestBody ExperimentCreateRequest request) {
        String username = TokenContext.getCurrentUsername();
        Experiment experiment = experimentService.updateExperiment(id, request, username);
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
     */
    @GetMapping
    public BaseResponse<List<Experiment>> listExperiments() {
        List<Experiment> experiments = experimentService.listExperiments();
        return BaseResponse.of(experiments);
    }
    
    /**
     * 启动实验
     */
    @PostMapping("/{id}/start")
    public BaseResponse<Void> startExperiment(@PathVariable String id) {
        String username = TokenContext.getCurrentUsername();
        experimentService.startExperiment(id, username);
        return BaseResponse.of("实验启动成功", null);
    }
    
    /**
     * 停止实验
     */
    @PostMapping("/{id}/stop")
    public BaseResponse<Void> stopExperiment(@PathVariable String id) {
        String username = TokenContext.getCurrentUsername();
        experimentService.stopExperiment(id, username);
        return BaseResponse.of("实验停止成功", null);
    }
    
    /**
     * 暂停实验
     */
    @PostMapping("/{id}/pause")
    public BaseResponse<Void> pauseExperiment(@PathVariable String id) {
        String username = TokenContext.getCurrentUsername();
        experimentService.pauseExperiment(id, username);
        return BaseResponse.of("实验暂停成功", null);
    }
    
    /**
     * 删除实验
     */
    @DeleteMapping("/{id}")
    public BaseResponse<Void> deleteExperiment(@PathVariable String id) {
        String username = TokenContext.getCurrentUsername();
        experimentService.deleteExperiment(id, username);
        return BaseResponse.of("实验删除成功", null);
    }
}

