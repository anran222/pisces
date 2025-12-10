package com.pisces.service.service.impl;

import com.pisces.common.enums.Permission;
import com.pisces.common.enums.ResponseCode;
import com.pisces.common.model.Experiment;
import com.pisces.common.model.ExperimentMetadata;
import com.pisces.common.request.ExperimentCreateRequest;
import com.pisces.common.response.ExperimentResponse;
import com.pisces.service.service.ConfigService;
import com.pisces.service.service.ExperimentService;
import com.pisces.service.service.UserService;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 实验管理服务实现
 */
@Slf4j
@Service
public class ExperimentServiceImpl implements ExperimentService {
    
    @Autowired
    private ConfigService configService;
    
    @Autowired
    private UserService userService;
    
    /**
     * 实验缓存（内存存储实验基本信息）
     */
    private final ConcurrentHashMap<String, Experiment> experimentCache = new ConcurrentHashMap<>();
    
    /**
     * 创建实验
     */
    @Override
    public Experiment createExperiment(ExperimentCreateRequest request, String creatorUsername) {
        // 检查权限
        if (!userService.hasPermission(creatorUsername, Permission.EXPERIMENT_CREATE)) {
            throw new BusinessException(ResponseCode.EXPERIMENT_PERMISSION_DENIED, "没有权限创建实验");
        }
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
        experiment.setCreator(creatorUsername);
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
        
        // 缓存实验基本信息
        experimentCache.put(experimentId, experiment);
        
        log.info("创建实验成功: {}", experimentId);
        return experiment;
    }
    
    /**
     * 更新实验
     */
    @Override
    public Experiment updateExperiment(String experimentId, ExperimentCreateRequest request, String username) {
        // 检查权限
        if (!userService.hasPermission(username, Permission.EXPERIMENT_UPDATE)) {
            throw new BusinessException(ResponseCode.EXPERIMENT_PERMISSION_DENIED, "没有权限更新实验");
        }
        
        // 检查是否为实验创建者或管理员
        ExperimentMetadata metadata = configService.getExperimentConfig(experimentId);
        if (metadata == null) {
            throw new BusinessException(ResponseCode.EXPERIMENT_NOT_FOUND);
        }
        if (metadata.getExperiment() != null) {
            String creator = metadata.getExperiment().getCreator();
            if (creator != null && !creator.equals(username) && !userService.isAdmin(username)) {
                throw new BusinessException(ResponseCode.EXPERIMENT_PERMISSION_DENIED, "只能修改自己创建的实验");
            }
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
        
        try {
            configService.saveExperimentConfig(experimentId, metadata);
        } catch (Exception e) {
            log.error("保存实验配置失败: {}", experimentId, e);
            throw new BusinessException(ResponseCode.OPERATION_FAILED, "保存实验配置失败: " + e.getMessage());
        }
        experimentCache.put(experimentId, experiment);
        
        log.info("更新实验成功: {}", experimentId);
        return experiment;
    }
    
    /**
     * 启动实验
     */
    @Override
    public void startExperiment(String experimentId, String username) {
        // 检查权限
        if (!userService.hasPermission(username, Permission.EXPERIMENT_UPDATE)) {
            throw new BusinessException(ResponseCode.EXPERIMENT_PERMISSION_DENIED, "没有权限启动实验");
        }
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
        experimentCache.put(experimentId, experiment);
        
        log.info("启动实验: {}", experimentId);
    }
    
    /**
     * 停止实验
     */
    @Override
    public void stopExperiment(String experimentId, String username) {
        // 检查权限
        if (!userService.hasPermission(username, Permission.EXPERIMENT_UPDATE)) {
            throw new BusinessException(ResponseCode.EXPERIMENT_PERMISSION_DENIED, "没有权限停止实验");
        }
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
        experimentCache.put(experimentId, experiment);
        
        log.info("停止实验: {}", experimentId);
    }
    
    /**
     * 暂停实验
     */
    @Override
    public void pauseExperiment(String experimentId, String username) {
        // 检查权限
        if (!userService.hasPermission(username, Permission.EXPERIMENT_UPDATE)) {
            throw new BusinessException(ResponseCode.EXPERIMENT_PERMISSION_DENIED, "没有权限暂停实验");
        }
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
        experimentCache.put(experimentId, experiment);
        
        log.info("暂停实验: {}", experimentId);
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
        return new ArrayList<>(experimentCache.values());
    }
    
    /**
     * 删除实验
     */
    @Override
    public void deleteExperiment(String experimentId, String username) {
        // 检查权限
        if (!userService.hasPermission(username, Permission.EXPERIMENT_DELETE)) {
            throw new BusinessException(ResponseCode.EXPERIMENT_PERMISSION_DENIED, "没有权限删除实验");
        }
        
        // 检查是否为实验创建者或管理员
        ExperimentMetadata metadata = configService.getExperimentConfig(experimentId);
        if (metadata == null) {
            throw new BusinessException(ResponseCode.EXPERIMENT_NOT_FOUND);
        }
        if (metadata.getExperiment() != null) {
            String creator = metadata.getExperiment().getCreator();
            if (creator != null && !creator.equals(username) && !userService.isAdmin(username)) {
                throw new BusinessException(ResponseCode.EXPERIMENT_PERMISSION_DENIED, "只能删除自己创建的实验");
            }
        }
        
        try {
            configService.deleteExperimentConfig(experimentId);
        } catch (Exception e) {
            log.error("删除实验配置失败: {}", experimentId, e);
            throw new BusinessException(ResponseCode.OPERATION_FAILED, "删除实验配置失败: " + e.getMessage());
        }
        experimentCache.remove(experimentId);
        
        log.info("删除实验: {}", experimentId);
    }
}

