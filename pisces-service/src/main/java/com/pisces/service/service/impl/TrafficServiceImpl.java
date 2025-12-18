package com.pisces.service.service.impl;

import com.pisces.common.enums.ResponseCode;
import com.pisces.common.model.ExperimentMetadata;
import com.pisces.common.model.TrafficConfig;
import com.pisces.service.service.ConfigService;
import com.pisces.service.service.MultiArmedBanditService;
import com.pisces.service.service.TrafficService;
import com.pisces.service.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 流量分配服务实现
 */
@Slf4j
@Service
public class TrafficServiceImpl implements TrafficService {
    
    @Autowired
    private ConfigService configService;
    
    @Autowired
    private MultiArmedBanditService mabService;
    
    /**
     * 用户分组缓存（用户ID -> 实验ID -> 组ID）
     */
    private final ConcurrentHashMap<String, Map<String, String>> userGroupCache = new ConcurrentHashMap<>();
    
    /**
     * 分配用户到实验组
     */
    @Override
    public String assignGroup(String experimentId, String visitorId) {
        // visitorId可以是userId、设备ID、会话ID等
        // 检查缓存
        Map<String, String> userExperiments = userGroupCache.get(visitorId);
        if (userExperiments != null && userExperiments.containsKey(experimentId)) {
            return userExperiments.get(experimentId);
        }
        
        // 获取实验配置
        ExperimentMetadata metadata = configService.getExperimentConfig(experimentId);
        if (metadata == null) {
            throw new BusinessException(ResponseCode.EXPERIMENT_NOT_FOUND);
        }
        
        // 检查实验状态
        if (metadata.getExperiment().getStatus() != com.pisces.common.model.Experiment.ExperimentStatus.RUNNING) {
            return null; // 实验未运行，不分配
        }
        
        // 检查白名单/黑名单
        if (metadata.getWhitelist() != null && metadata.getWhitelist().contains(visitorId)) {
            // 白名单访客默认分配到第一个组
            if (metadata.getGroups() != null && !metadata.getGroups().isEmpty()) {
                String groupId = metadata.getGroups().keySet().iterator().next();
                cacheUserGroup(visitorId, experimentId, groupId);
                return groupId;
            }
        }
        
        if (metadata.getBlacklist() != null && metadata.getBlacklist().contains(visitorId)) {
            return null; // 黑名单访客不参与实验
        }
        
        // 检查时间范围
        if (!isInTimeRange(metadata.getExperiment())) {
            return null;
        }
        
        // 根据流量配置分配
        TrafficConfig trafficConfig = metadata.getTraffic();
        if (trafficConfig == null || trafficConfig.getTotalTraffic() == null) {
            return null;
        }
        
        // 检查是否在流量范围内
        double randomValue = generateHashValue(visitorId + experimentId);
        if (randomValue >= trafficConfig.getTotalTraffic()) {
            return null; // 不在流量范围内
        }
        
        // 根据策略分配组
        String groupId = allocateGroup(trafficConfig, visitorId, experimentId);
        if (groupId != null) {
            cacheUserGroup(visitorId, experimentId, groupId);
            return groupId;
        }
        
        // 如果分配失败，返回null（由调用方决定是否抛异常）
        return null;
    }
    
    /**
     * 获取访客所在组
     */
    @Override
    public String getUserGroup(String experimentId, String visitorId) {
        Map<String, String> userExperiments = userGroupCache.get(visitorId);
        if (userExperiments != null && userExperiments.containsKey(experimentId)) {
            String groupId = userExperiments.get(experimentId);
            if (groupId != null) {
                return groupId;
            }
        }
        
        // 如果缓存中没有，重新分配
        return assignGroup(experimentId, visitorId);
    }
    
    /**
     * 根据策略分配组
     */
    private String allocateGroup(TrafficConfig trafficConfig, String visitorId, String experimentId) {
        TrafficConfig.TrafficStrategy strategy = trafficConfig.getStrategy();
        
        if (strategy == TrafficConfig.TrafficStrategy.HASH) {
            return allocateByHash(trafficConfig, visitorId, experimentId);
        } else if (strategy == TrafficConfig.TrafficStrategy.RANDOM) {
            return allocateByRandom(trafficConfig);
        } else if (strategy == TrafficConfig.TrafficStrategy.THOMPSON_SAMPLING) {
            // 多臂老虎机算法：Thompson Sampling
            return mabService.selectGroupByThompsonSampling(experimentId, visitorId);
        } else if (strategy == TrafficConfig.TrafficStrategy.UCB) {
            // 多臂老虎机算法：UCB
            return mabService.selectGroupByUCB(experimentId, visitorId);
        } else {
            // RULE策略需要根据业务规则实现
            return allocateByHash(trafficConfig, visitorId, experimentId);
        }
    }
    
    /**
     * 哈希分配（一致性哈希）
     */
    private String allocateByHash(TrafficConfig trafficConfig, String visitorId, String experimentId) {
        String hashKey = trafficConfig.getHashKey() != null ? 
                trafficConfig.getHashKey() : "visitorId";
        String hashValue = visitorId + experimentId;
        
        double hash = generateHashValue(hashValue);
        double cumulativeRatio = 0.0;
        
        List<TrafficConfig.GroupAllocation> allocations = trafficConfig.getAllocation();
        if (allocations == null || allocations.isEmpty()) {
            return null;
        }
        
        for (TrafficConfig.GroupAllocation allocation : allocations) {
            cumulativeRatio += allocation.getRatio();
            if (hash < cumulativeRatio) {
                return allocation.getGroup();
            }
        }
        
        // 默认返回第一个组
        return allocations.get(0).getGroup();
    }
    
    /**
     * 随机分配
     */
    private String allocateByRandom(TrafficConfig trafficConfig) {
        double random = Math.random();
        double cumulativeRatio = 0.0;
        
        List<TrafficConfig.GroupAllocation> allocations = trafficConfig.getAllocation();
        if (allocations == null || allocations.isEmpty()) {
            return null;
        }
        
        for (TrafficConfig.GroupAllocation allocation : allocations) {
            cumulativeRatio += allocation.getRatio();
            if (random < cumulativeRatio) {
                return allocation.getGroup();
            }
        }
        
        return allocations.get(0).getGroup();
    }
    
    /**
     * 生成哈希值（0.0-1.0）
     */
    private double generateHashValue(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hashBytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            
            // 取前4个字节转换为整数
            long hash = 0;
            for (int i = 0; i < 4; i++) {
                hash = (hash << 8) | (hashBytes[i] & 0xFF);
            }
            
            // 转换为0.0-1.0之间的值
            return Math.abs(hash % 10000) / 10000.0;
        } catch (Exception e) {
            log.error("生成哈希值失败", e);
            return Math.random();
        }
    }
    
    /**
     * 检查是否在时间范围内
     */
    private boolean isInTimeRange(com.pisces.common.model.Experiment experiment) {
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        return (experiment.getStartTime() == null || now.isAfter(experiment.getStartTime()) ||
                now.isEqual(experiment.getStartTime())) &&
               (experiment.getEndTime() == null || now.isBefore(experiment.getEndTime()) ||
                now.isEqual(experiment.getEndTime()));
    }
    
    /**
     * 缓存访客分组
     */
    private void cacheUserGroup(String visitorId, String experimentId, String groupId) {
        userGroupCache.computeIfAbsent(visitorId, k -> new ConcurrentHashMap<>())
                .put(experimentId, groupId);
    }
    
    /**
     * 获取访客参与的所有实验
     */
    @Override
    public Map<String, String> getUserExperiments(String visitorId) {
        return userGroupCache.getOrDefault(visitorId, new ConcurrentHashMap<>());
    }
}

