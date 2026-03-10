package com.pisces.service.service.impl;

import com.pisces.common.enums.ResponseCode;
import com.pisces.common.model.Experiment;
import com.pisces.common.model.ExperimentMetadata;
import com.pisces.common.request.ExperimentCreateRequest;
import com.pisces.common.response.ExperimentResponse;
import com.pisces.service.service.ConfigService;
import com.pisces.service.service.ExperimentService;
import com.pisces.service.exception.BusinessException;
import org.springframework.beans.BeanUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 实验管理服务实现
 */
@Slf4j
@Service
public class ExperimentServiceImpl implements ExperimentService {
    
    @Autowired
    private ConfigService configService;
    
    /**
     * 创建实验（无用户系统版本）
     */
    @Override
    public Experiment createExperiment(ExperimentCreateRequest request) {
        // 参数校验
        validateExperimentRequest(request);
        
        // 生成实验ID
        String experimentId = "exp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        
        // 创建实验对象
        Experiment experiment = new Experiment();
        experiment.setId(experimentId);
        experiment.setName(request.getName());
        experiment.setDescription(request.getDescription());
        experiment.setStatus(Experiment.ExperimentStatus.DRAFT);
        experiment.setStartTime(request.getStartTime());
        experiment.setEndTime(request.getEndTime());
        experiment.setCreator("system");  // 无用户系统，使用system作为创建者
        experiment.setCreateTime(LocalDateTime.now());
        experiment.setUpdateTime(LocalDateTime.now());
        
        // 构建实验组
        Map<String, com.pisces.common.model.ExperimentGroup> groups = new HashMap<>();
        if (request.getGroups() != null) {
            for (ExperimentCreateRequest.GroupConfig groupConfig : request.getGroups()) {
                com.pisces.common.model.ExperimentGroup group = new com.pisces.common.model.ExperimentGroup();
                group.setId(groupConfig.getId());
                group.setName(groupConfig.getName());
                group.setTrafficRatio(groupConfig.getTrafficRatio());
                group.setConfig(groupConfig.getConfig());
                groups.put(group.getId(), group);
            }
        }
        
        // 构建流量配置
        com.pisces.common.model.TrafficConfig trafficConfig = new com.pisces.common.model.TrafficConfig();
        if (request.getTraffic() != null) {
            trafficConfig.setTotalTraffic(request.getTraffic().getTotalTraffic());
            trafficConfig.setStrategy(com.pisces.common.model.TrafficConfig.TrafficStrategy.valueOf(
                    request.getTraffic().getStrategy()));
            trafficConfig.setHashKey(request.getTraffic().getHashKey());
            
            List<com.pisces.common.model.TrafficConfig.GroupAllocation> allocations = new ArrayList<>();
            for (ExperimentCreateRequest.GroupAllocationRequest allocationRequest : 
                    request.getTraffic().getAllocation()) {
                com.pisces.common.model.TrafficConfig.GroupAllocation allocation = 
                        new com.pisces.common.model.TrafficConfig.GroupAllocation();
                allocation.setGroup(allocationRequest.getGroup());
                allocation.setRatio(allocationRequest.getRatio());
                allocations.add(allocation);
            }
            trafficConfig.setAllocation(allocations);
        }
        
        // 构建实验元数据
        ExperimentMetadata metadata = new ExperimentMetadata();
        metadata.setConfigVersion(1L);
        metadata.setExperiment(experiment);
        metadata.setGroups(groups);
        metadata.setTraffic(trafficConfig);
        metadata.setWhitelist(request.getWhitelist() != null ? request.getWhitelist() : new ArrayList<>());
        metadata.setBlacklist(request.getBlacklist() != null ? request.getBlacklist() : new ArrayList<>());
        
        // 保存到Zookeeper
        try {
            configService.saveExperimentConfig(experimentId, metadata);
        } catch (Exception e) {
            log.error("保存实验配置失败: {}", experimentId, e);
            throw new BusinessException(ResponseCode.OPERATION_FAILED, "保存实验配置失败: " + e.getMessage());
        }
        
        log.info("创建实验成功: {}", experimentId);
        return experiment;
    }
    
    /**
     * 更新实验（无用户系统版本）
     */
    @Override
    public Experiment updateExperiment(String experimentId, ExperimentCreateRequest request) {
        ExperimentMetadata metadata = configService.getExperimentConfig(experimentId);
        if (metadata == null) {
            throw new BusinessException(ResponseCode.EXPERIMENT_NOT_FOUND);
        }
        
        Experiment experiment = metadata.getExperiment();
        experiment.setName(request.getName());
        experiment.setDescription(request.getDescription());
        experiment.setStartTime(request.getStartTime());
        experiment.setEndTime(request.getEndTime());
        experiment.setUpdateTime(LocalDateTime.now());
        
        // 更新实验组
        Map<String, com.pisces.common.model.ExperimentGroup> groups = new HashMap<>();
        if (request.getGroups() != null) {
            for (ExperimentCreateRequest.GroupConfig groupConfig : request.getGroups()) {
                com.pisces.common.model.ExperimentGroup group = new com.pisces.common.model.ExperimentGroup();
                group.setId(groupConfig.getId());
                group.setName(groupConfig.getName());
                group.setTrafficRatio(groupConfig.getTrafficRatio());
                group.setConfig(groupConfig.getConfig());
                groups.put(group.getId(), group);
            }
        }
        metadata.setGroups(groups);
        
        // 更新流量配置
        com.pisces.common.model.TrafficConfig trafficConfig = new com.pisces.common.model.TrafficConfig();
        if (request.getTraffic() != null) {
            trafficConfig.setTotalTraffic(request.getTraffic().getTotalTraffic());
            trafficConfig.setStrategy(com.pisces.common.model.TrafficConfig.TrafficStrategy.valueOf(
                    request.getTraffic().getStrategy()));
            trafficConfig.setHashKey(request.getTraffic().getHashKey());
            
            List<com.pisces.common.model.TrafficConfig.GroupAllocation> allocations = new ArrayList<>();
            for (ExperimentCreateRequest.GroupAllocationRequest allocationRequest : 
                    request.getTraffic().getAllocation()) {
                com.pisces.common.model.TrafficConfig.GroupAllocation allocation = 
                        new com.pisces.common.model.TrafficConfig.GroupAllocation();
                allocation.setGroup(allocationRequest.getGroup());
                allocation.setRatio(allocationRequest.getRatio());
                allocations.add(allocation);
            }
            trafficConfig.setAllocation(allocations);
        }
        metadata.setTraffic(trafficConfig);
        
        // 更新白名单和黑名单
        metadata.setWhitelist(request.getWhitelist() != null ? request.getWhitelist() : new ArrayList<>());
        metadata.setBlacklist(request.getBlacklist() != null ? request.getBlacklist() : new ArrayList<>());
        metadata.setConfigVersion(Math.max(1L, metadata.getConfigVersion()) + 1);
        
        try {
            configService.saveExperimentConfig(experimentId, metadata);
        } catch (Exception e) {
            log.error("保存实验配置失败: {}", experimentId, e);
            throw new BusinessException(ResponseCode.OPERATION_FAILED, "保存实验配置失败: " + e.getMessage());
        }
        
        log.info("更新实验成功: {}", experimentId);
        return experiment;
    }
    
    /**
     * 启动实验（无用户系统版本）
     */
    @Override
    public void startExperiment(String experimentId) {
        ExperimentMetadata metadata = configService.getExperimentConfig(experimentId);
        if (metadata == null) {
            throw new BusinessException(ResponseCode.EXPERIMENT_NOT_FOUND);
        }
        
        Experiment experiment = metadata.getExperiment();
        if (experiment.getStatus() != Experiment.ExperimentStatus.DRAFT) {
            throw new BusinessException(ResponseCode.EXPERIMENT_STATUS_ERROR, "只有草稿状态的实验才能启动");
        }
        
        experiment.setStatus(Experiment.ExperimentStatus.RUNNING);
        experiment.setUpdateTime(LocalDateTime.now());
        
        try {
            configService.saveExperimentConfig(experimentId, metadata);
        } catch (Exception e) {
            log.error("保存实验配置失败: {}", experimentId, e);
            throw new BusinessException(ResponseCode.OPERATION_FAILED, "保存实验配置失败: " + e.getMessage());
        }
        
        log.info("启动实验: {}", experimentId);
    }
    
    /**
     * 停止实验（无用户系统版本）
     */
    @Override
    public void stopExperiment(String experimentId) {
        ExperimentMetadata metadata = configService.getExperimentConfig(experimentId);
        if (metadata == null) {
            throw new BusinessException(ResponseCode.EXPERIMENT_NOT_FOUND);
        }
        
        Experiment experiment = metadata.getExperiment();
        experiment.setStatus(Experiment.ExperimentStatus.STOPPED);
        experiment.setUpdateTime(LocalDateTime.now());
        
        try {
            configService.saveExperimentConfig(experimentId, metadata);
        } catch (Exception e) {
            log.error("保存实验配置失败: {}", experimentId, e);
            throw new BusinessException(ResponseCode.OPERATION_FAILED, "保存实验配置失败: " + e.getMessage());
        }
        
        log.info("停止实验: {}", experimentId);
    }
    
    /**
     * 暂停实验（无用户系统版本）
     */
    @Override
    public void pauseExperiment(String experimentId) {
        ExperimentMetadata metadata = configService.getExperimentConfig(experimentId);
        if (metadata == null) {
            throw new BusinessException(ResponseCode.EXPERIMENT_NOT_FOUND);
        }
        
        Experiment experiment = metadata.getExperiment();
        if (experiment.getStatus() != Experiment.ExperimentStatus.RUNNING) {
            throw new BusinessException(ResponseCode.EXPERIMENT_STATUS_ERROR, "只有运行中的实验才能暂停");
        }
        
        experiment.setStatus(Experiment.ExperimentStatus.PAUSED);
        experiment.setUpdateTime(LocalDateTime.now());
        
        try {
            configService.saveExperimentConfig(experimentId, metadata);
        } catch (Exception e) {
            log.error("保存实验配置失败: {}", experimentId, e);
            throw new BusinessException(ResponseCode.OPERATION_FAILED, "保存实验配置失败: " + e.getMessage());
        }
        
        log.info("暂停实验: {}", experimentId);
    }
    
    /**
     * 恢复实验（从暂停状态恢复到运行状态）
     */
    @Override
    public void resumeExperiment(String experimentId) {
        ExperimentMetadata metadata = configService.getExperimentConfig(experimentId);
        if (metadata == null) {
            throw new BusinessException(ResponseCode.EXPERIMENT_NOT_FOUND);
        }
        
        Experiment experiment = metadata.getExperiment();
        if (experiment.getStatus() != Experiment.ExperimentStatus.PAUSED) {
            throw new BusinessException(ResponseCode.EXPERIMENT_STATUS_ERROR, "只有暂停状态的实验才能恢复");
        }
        
        experiment.setStatus(Experiment.ExperimentStatus.RUNNING);
        experiment.setUpdateTime(LocalDateTime.now());
        
        try {
            configService.saveExperimentConfig(experimentId, metadata);
        } catch (Exception e) {
            log.error("保存实验配置失败: {}", experimentId, e);
            throw new BusinessException(ResponseCode.OPERATION_FAILED, "保存实验配置失败: " + e.getMessage());
        }
        
        log.info("恢复实验: {}", experimentId);
    }
    
    /**
     * 获取实验
     */
    @Override
    public ExperimentResponse getExperiment(String experimentId) {
        ExperimentMetadata metadata = configService.getExperimentConfig(experimentId);
        if (metadata == null) {
            throw new BusinessException(ResponseCode.EXPERIMENT_NOT_FOUND);
        }
        return convertToResponse(metadata);
    }
    
    /**
     * 转换为响应对象
     */
    private ExperimentResponse convertToResponse(ExperimentMetadata metadata) {
        ExperimentResponse response = new ExperimentResponse();
        BeanUtils.copyProperties(metadata.getExperiment(), response);
        
        // 转换实验组
        if (metadata.getGroups() != null) {
            Map<String, ExperimentResponse.GroupResponse> groups = metadata.getGroups().entrySet().stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            e -> {
                                ExperimentResponse.GroupResponse gr = new ExperimentResponse.GroupResponse();
                                gr.setId(e.getValue().getId());
                                gr.setName(e.getValue().getName());
                                gr.setTrafficRatio(e.getValue().getTrafficRatio());
                                gr.setConfig(e.getValue().getConfig());
                                return gr;
                            }
                    ));
            response.setGroups(groups);
        }
        
        // 转换流量配置
        if (metadata.getTraffic() != null) {
            ExperimentResponse.TrafficConfigResponse traffic = new ExperimentResponse.TrafficConfigResponse();
            traffic.setTotalTraffic(metadata.getTraffic().getTotalTraffic());
            traffic.setStrategy(metadata.getTraffic().getStrategy().name());
            traffic.setHashKey(metadata.getTraffic().getHashKey());
            
            if (metadata.getTraffic().getAllocation() != null) {
                List<ExperimentResponse.GroupAllocationResponse> allocations = 
                        metadata.getTraffic().getAllocation().stream()
                                .map(a -> {
                                    ExperimentResponse.GroupAllocationResponse ar = 
                                            new ExperimentResponse.GroupAllocationResponse();
                                    ar.setGroup(a.getGroup());
                                    ar.setRatio(a.getRatio());
                                    return ar;
                                })
                                .collect(Collectors.toList());
                traffic.setAllocation(allocations);
            }
            
            response.setTraffic(traffic);
        }
        
        response.setWhitelist(metadata.getWhitelist());
        response.setBlacklist(metadata.getBlacklist());
        
        return response;
    }
    
    /**
     * 获取实验列表
     */
    @Override
    public List<Experiment> listExperiments() {
        try {
            List<String> experimentIds = configService.getAllExperimentIds();
            List<Experiment> experiments = new ArrayList<>();
            
            for (String experimentId : experimentIds) {
                try {
                    ExperimentMetadata metadata = configService.getExperimentConfig(experimentId);
                    if (metadata != null && metadata.getExperiment() != null) {
                        experiments.add(metadata.getExperiment());
                    }
                } catch (Exception e) {
                    log.warn("获取实验失败: {}", experimentId, e);
                }
            }
            
            return experiments;
        } catch (Exception e) {
            log.error("获取实验列表失败", e);
            return new ArrayList<>();
        }
    }
    
    /**
     * 根据状态查询实验列表
     */
    @Override
    public List<Experiment> listExperimentsByStatus(String status) {
        if (status == null || status.trim().isEmpty()) {
            return listExperiments();
        }
        
        // 验证状态值
        Experiment.ExperimentStatus targetStatus;
        try {
            targetStatus = Experiment.ExperimentStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ResponseCode.VALIDATION_ERROR, 
                    "不支持的实验状态: " + status + "，支持的状态: DRAFT, RUNNING, PAUSED, STOPPED");
        }
        
        try {
            List<String> experimentIds = configService.getAllExperimentIds();
            List<Experiment> experiments = new ArrayList<>();
            
            for (String experimentId : experimentIds) {
                try {
                    ExperimentMetadata metadata = configService.getExperimentConfig(experimentId);
                    if (metadata != null && metadata.getExperiment() != null) {
                        Experiment experiment = metadata.getExperiment();
                        if (experiment.getStatus() == targetStatus) {
                            experiments.add(experiment);
                        }
                    }
                } catch (Exception e) {
                    log.warn("获取实验失败: {}", experimentId, e);
                }
            }
            
            log.info("根据状态[{}]查询实验，共{}个", status, experiments.size());
            return experiments;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("根据状态查询实验列表失败", e);
            return new ArrayList<>();
        }
    }
    
    /**
     * 根据多个状态查询实验列表
     */
    @Override
    public List<Experiment> listExperimentsByStatuses(List<String> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return listExperiments();
        }
        
        // 验证并转换状态值
        List<Experiment.ExperimentStatus> targetStatuses = new ArrayList<>();
        for (String status : statuses) {
            try {
                targetStatuses.add(Experiment.ExperimentStatus.valueOf(status.toUpperCase()));
            } catch (IllegalArgumentException e) {
                throw new BusinessException(ResponseCode.VALIDATION_ERROR, 
                        "不支持的实验状态: " + status + "，支持的状态: DRAFT, RUNNING, PAUSED, STOPPED");
            }
        }
        
        try {
            List<String> experimentIds = configService.getAllExperimentIds();
            List<Experiment> experiments = new ArrayList<>();
            
            for (String experimentId : experimentIds) {
                try {
                    ExperimentMetadata metadata = configService.getExperimentConfig(experimentId);
                    if (metadata != null && metadata.getExperiment() != null) {
                        Experiment experiment = metadata.getExperiment();
                        if (targetStatuses.contains(experiment.getStatus())) {
                            experiments.add(experiment);
                        }
                    }
                } catch (Exception e) {
                    log.warn("获取实验失败: {}", experimentId, e);
                }
            }
            
            log.info("根据状态{}查询实验，共{}个", statuses, experiments.size());
            return experiments;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("根据状态查询实验列表失败", e);
            return new ArrayList<>();
        }
    }
    
    /**
     * 删除实验（无用户系统版本）
     */
    @Override
    public void deleteExperiment(String experimentId) {
        ExperimentMetadata metadata = configService.getExperimentConfig(experimentId);
        if (metadata == null) {
            throw new BusinessException(ResponseCode.EXPERIMENT_NOT_FOUND);
        }
        
        try {
            configService.deleteExperimentConfig(experimentId);
        } catch (Exception e) {
            log.error("删除实验配置失败: {}", experimentId, e);
            throw new BusinessException(ResponseCode.OPERATION_FAILED, "删除实验配置失败: " + e.getMessage());
        }
        
        log.info("删除实验: {}", experimentId);
    }
    
    /**
     * 批量暂停实验
     */
    @Override
    public Map<String, Object> batchPauseExperiments(List<String> experimentIds) {
        return batchOperation(experimentIds, "pause", this::pauseExperimentSafe);
    }
    
    /**
     * 批量停止实验
     */
    @Override
    public Map<String, Object> batchStopExperiments(List<String> experimentIds) {
        return batchOperation(experimentIds, "stop", this::stopExperimentSafe);
    }
    
    /**
     * 批量恢复实验
     */
    @Override
    public Map<String, Object> batchResumeExperiments(List<String> experimentIds) {
        return batchOperation(experimentIds, "resume", this::resumeExperimentSafe);
    }
    
    /**
     * 批量删除实验
     */
    @Override
    public Map<String, Object> batchDeleteExperiments(List<String> experimentIds) {
        return batchOperation(experimentIds, "delete", this::deleteExperimentSafe);
    }
    
    /**
     * 批量操作通用方法
     */
    private Map<String, Object> batchOperation(List<String> experimentIds, String operationName,
                                                java.util.function.Function<String, String> operation) {
        Map<String, Object> result = new HashMap<>();
        List<String> successIds = new ArrayList<>();
        List<Map<String, String>> failedItems = new ArrayList<>();
        
        if (experimentIds == null || experimentIds.isEmpty()) {
            result.put("success", false);
            result.put("message", "实验ID列表不能为空");
            return result;
        }
        
        for (String experimentId : experimentIds) {
            String error = operation.apply(experimentId);
            if (error == null) {
                successIds.add(experimentId);
            } else {
                Map<String, String> failedItem = new HashMap<>();
                failedItem.put("id", experimentId);
                failedItem.put("error", error);
                failedItems.add(failedItem);
            }
        }
        
        result.put("success", failedItems.isEmpty());
        result.put("operation", operationName);
        result.put("total", experimentIds.size());
        result.put("successCount", successIds.size());
        result.put("failedCount", failedItems.size());
        result.put("successIds", successIds);
        result.put("failedItems", failedItems);
        
        if (failedItems.isEmpty()) {
            result.put("message", String.format("批量%s成功，共%d个实验", 
                    getOperationLabel(operationName), successIds.size()));
        } else if (successIds.isEmpty()) {
            result.put("message", String.format("批量%s失败，全部%d个实验操作失败", 
                    getOperationLabel(operationName), failedItems.size()));
        } else {
            result.put("message", String.format("批量%s部分成功：%d个成功，%d个失败", 
                    getOperationLabel(operationName), successIds.size(), failedItems.size()));
        }
        
        log.info("批量{}实验: 总数={}, 成功={}, 失败={}", 
                operationName, experimentIds.size(), successIds.size(), failedItems.size());
        
        return result;
    }
    
    private String getOperationLabel(String operation) {
        switch (operation) {
            case "pause": return "暂停";
            case "stop": return "停止";
            case "resume": return "恢复";
            case "delete": return "删除";
            default: return operation;
        }
    }
    
    /**
     * 安全暂停实验（不抛异常，返回错误信息）
     */
    private String pauseExperimentSafe(String experimentId) {
        try {
            pauseExperiment(experimentId);
            return null;
        } catch (BusinessException e) {
            return e.getMessage();
        } catch (Exception e) {
            return "暂停失败: " + e.getMessage();
        }
    }
    
    /**
     * 安全停止实验
     */
    private String stopExperimentSafe(String experimentId) {
        try {
            stopExperiment(experimentId);
            return null;
        } catch (BusinessException e) {
            return e.getMessage();
        } catch (Exception e) {
            return "停止失败: " + e.getMessage();
        }
    }
    
    /**
     * 安全恢复实验
     */
    private String resumeExperimentSafe(String experimentId) {
        try {
            resumeExperiment(experimentId);
            return null;
        } catch (BusinessException e) {
            return e.getMessage();
        } catch (Exception e) {
            return "恢复失败: " + e.getMessage();
        }
    }
    
    /**
     * 安全删除实验
     */
    private String deleteExperimentSafe(String experimentId) {
        try {
            deleteExperiment(experimentId);
            return null;
        } catch (BusinessException e) {
            return e.getMessage();
        } catch (Exception e) {
            return "删除失败: " + e.getMessage();
        }
    }
    
    /**
     * 校验实验创建请求参数
     */
    private void validateExperimentRequest(ExperimentCreateRequest request) {
        // 校验实验名称
        if (request.getName() == null || request.getName().trim().isEmpty()) {
            throw new BusinessException(ResponseCode.VALIDATION_ERROR, "实验名称不能为空");
        }
        
        if (request.getName().length() > 100) {
            throw new BusinessException(ResponseCode.VALIDATION_ERROR, "实验名称长度不能超过100个字符");
        }
        
        // 校验实验组
        if (request.getGroups() == null || request.getGroups().isEmpty()) {
            throw new BusinessException(ResponseCode.VALIDATION_ERROR, "至少需要配置一个实验组");
        }
        
        if (request.getGroups().size() < 2) {
            throw new BusinessException(ResponseCode.VALIDATION_ERROR, "至少需要配置2个实验组（基准组和变体组）");
        }
        
        // 校验实验组ID唯一性
        java.util.Set<String> groupIds = new java.util.HashSet<>();
        for (ExperimentCreateRequest.GroupConfig group : request.getGroups()) {
            if (group.getId() == null || group.getId().trim().isEmpty()) {
                throw new BusinessException(ResponseCode.VALIDATION_ERROR, "实验组ID不能为空");
            }
            if (!groupIds.add(group.getId())) {
                throw new BusinessException(ResponseCode.VALIDATION_ERROR, "实验组ID重复: " + group.getId());
            }
            if (group.getName() == null || group.getName().trim().isEmpty()) {
                throw new BusinessException(ResponseCode.VALIDATION_ERROR, "实验组名称不能为空");
            }
        }
        
        // 校验流量配置
        if (request.getTraffic() != null) {
            ExperimentCreateRequest.TrafficConfigRequest traffic = request.getTraffic();
            
            // 校验总流量
            if (traffic.getTotalTraffic() != null) {
                if (traffic.getTotalTraffic() < 0 || traffic.getTotalTraffic() > 1) {
                    throw new BusinessException(ResponseCode.VALIDATION_ERROR, "总流量比例必须在0-1之间");
                }
            }
            
            // 校验流量分配策略
            if (traffic.getStrategy() != null) {
                try {
                    com.pisces.common.model.TrafficConfig.TrafficStrategy.valueOf(traffic.getStrategy());
                } catch (IllegalArgumentException e) {
                    throw new BusinessException(ResponseCode.VALIDATION_ERROR, 
                            "不支持的流量分配策略: " + traffic.getStrategy() + 
                            "，支持的策略: RANDOM, HASH, RULE, THOMPSON_SAMPLING, UCB");
                }
            }
            
            // 校验流量分配比例
            if (traffic.getAllocation() != null && !traffic.getAllocation().isEmpty()) {
                double totalRatio = 0.0;
                for (ExperimentCreateRequest.GroupAllocationRequest allocation : traffic.getAllocation()) {
                    if (allocation.getGroup() == null || allocation.getGroup().trim().isEmpty()) {
                        throw new BusinessException(ResponseCode.VALIDATION_ERROR, "流量分配的组ID不能为空");
                    }
                    if (!groupIds.contains(allocation.getGroup())) {
                        throw new BusinessException(ResponseCode.VALIDATION_ERROR, 
                                "流量分配的组ID不存在: " + allocation.getGroup());
                    }
                    if (allocation.getRatio() == null || allocation.getRatio() < 0 || allocation.getRatio() > 1) {
                        throw new BusinessException(ResponseCode.VALIDATION_ERROR, 
                                "流量分配比例必须在0-1之间: " + allocation.getGroup());
                    }
                    totalRatio += allocation.getRatio();
                }
                
                // 校验流量分配比例总和（允许小误差）
                if (Math.abs(totalRatio - 1.0) > 0.001) {
                    throw new BusinessException(ResponseCode.VALIDATION_ERROR, 
                            String.format("流量分配比例总和必须等于1，当前为%.4f", totalRatio));
                }
            }
        }
        
        // 校验时间范围
        if (request.getStartTime() != null && request.getEndTime() != null) {
            if (request.getEndTime().isBefore(request.getStartTime())) {
                throw new BusinessException(ResponseCode.VALIDATION_ERROR, "结束时间不能早于开始时间");
            }
        }
    }
}
