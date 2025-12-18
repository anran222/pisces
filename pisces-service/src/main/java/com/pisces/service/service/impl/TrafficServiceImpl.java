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
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 流量分配服务实现（基于Redis存储）
 */
@Slf4j
@Service
public class TrafficServiceImpl implements TrafficService {
    
    @Autowired
    private ConfigService configService;
    
    @Autowired
    private MultiArmedBanditService mabService;
    
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    // Redis Key前缀
    private static final String USER_GROUP_CACHE_PREFIX = "pisces:traffic:group:";  // 访客分组缓存
    
    // 缓存过期时间（天）
    private static final long CACHE_EXPIRE_DAYS = 30;
    
    /**
     * 分配用户到实验组
     */
    @Override
    public String assignGroup(String experimentId, String visitorId) {
        // visitorId可以是userId、设备ID、会话ID等
        // 检查Redis缓存
        String cacheKey = USER_GROUP_CACHE_PREFIX + visitorId;
        Object cachedGroupId = redisTemplate.opsForHash().get(cacheKey, experimentId);
        if (cachedGroupId != null) {
            return cachedGroupId.toString();
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
        // 从Redis缓存获取
        String cacheKey = USER_GROUP_CACHE_PREFIX + visitorId;
        Object cachedGroupId = redisTemplate.opsForHash().get(cacheKey, experimentId);
        if (cachedGroupId != null) {
            return cachedGroupId.toString();
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
     * 缓存访客分组（使用Redis Hash）
     */
    private void cacheUserGroup(String visitorId, String experimentId, String groupId) {
        String cacheKey = USER_GROUP_CACHE_PREFIX + visitorId;
        redisTemplate.opsForHash().put(cacheKey, experimentId, groupId);
        redisTemplate.expire(cacheKey, CACHE_EXPIRE_DAYS, TimeUnit.DAYS);
    }
    
    /**
     * 获取访客参与的所有实验
     */
    @Override
    public Map<String, String> getUserExperiments(String visitorId) {
        String cacheKey = USER_GROUP_CACHE_PREFIX + visitorId;
        Map<Object, Object> hash = redisTemplate.opsForHash().entries(cacheKey);
        Map<String, String> result = new HashMap<>();
        if (hash != null) {
            for (Map.Entry<Object, Object> entry : hash.entrySet()) {
                result.put(entry.getKey().toString(), entry.getValue().toString());
            }
        }
        return result;
    }
}

