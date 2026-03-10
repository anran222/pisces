package com.pisces.service.service.impl;

import com.pisces.common.enums.ResponseCode;
import com.pisces.common.model.ExperimentLayer;
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
    private static final String LAYER_ASSIGN_PREFIX = "pisces:layer:assign:";        // 分层互斥缓存

    // 缓存过期时间（天）
    private static final long CACHE_EXPIRE_DAYS = 30;
    // 版本字段后缀（同一 Hash key 中存储上次缓存时的 configVersion）
    private static final String VER_SUFFIX = ":ver";

    /**
     * 分配用户到实验组
     * 加入 configVersion 校验：若实验配置变更，旧缓存自动失效并重新分配
     */
    @Override
    public String assignGroup(String experimentId, String visitorId) {
        // 先获取实验配置（需要 configVersion 做缓存校验）
        ExperimentMetadata metadata = configService.getExperimentConfig(experimentId);
        if (metadata == null) {
            throw new BusinessException(ResponseCode.EXPERIMENT_NOT_FOUND);
        }

        // 检查 Redis 缓存（带版本校验）
        String cacheKey = USER_GROUP_CACHE_PREFIX + visitorId;
        String verField  = experimentId + VER_SUFFIX;
        Object cachedGroupId = redisTemplate.opsForHash().get(cacheKey, experimentId);
        Object cachedVersion = redisTemplate.opsForHash().get(cacheKey, verField);

        if (cachedGroupId != null && cachedVersion != null) {
            try {
                long cachedVer = Long.parseLong(cachedVersion.toString());
                if (cachedVer == metadata.getConfigVersion()) {
                    return cachedGroupId.toString(); // 版本匹配，缓存有效
                }
                // 配置已更新，删除旧缓存字段，重新分配
                redisTemplate.opsForHash().delete(cacheKey, experimentId, verField);
                log.info("实验配置变更（v{} → v{}），访客 {} 重新分配分组",
                        cachedVer, metadata.getConfigVersion(), visitorId);
            } catch (NumberFormatException ignored) {
                // 格式异常，视为版本不匹配，重新分配
            }
        }

        // 检查实验状态
        if (metadata.getExperiment().getStatus() != com.pisces.common.model.Experiment.ExperimentStatus.RUNNING) {
            return null; // 实验未运行，不分配
        }

        // 检查白名单/黑名单
        if (metadata.getWhitelist() != null && metadata.getWhitelist().contains(visitorId)) {
            if (metadata.getGroups() != null && !metadata.getGroups().isEmpty()) {
                String groupId = metadata.getGroups().keySet().iterator().next();
                cacheUserGroup(visitorId, experimentId, groupId, metadata.getConfigVersion());
                return groupId;
            }
        }

        if (metadata.getBlacklist() != null && metadata.getBlacklist().contains(visitorId)) {
            return null;
        }

        // 检查时间范围
        if (!isInTimeRange(metadata.getExperiment())) {
            return null;
        }

        // 分层互斥检查：同一 MUTEX 层内，每个访客只能进入一个实验
        if (metadata.getLayerId() != null) {
            String blockedExperiment = checkLayerMutex(metadata.getLayerId(), experimentId, visitorId);
            if (blockedExperiment != null) {
                log.debug("访客 {} 已在层 {} 的实验 {} 中，实验 {} 被拒绝（互斥）",
                        visitorId, metadata.getLayerId(), blockedExperiment, experimentId);
                return null;
            }
        }

        // 根据流量配置分配
        TrafficConfig trafficConfig = metadata.getTraffic();
        if (trafficConfig == null || trafficConfig.getTotalTraffic() == null) {
            return null;
        }

        // 检查是否在流量范围内
        double randomValue = generateHashValue(visitorId + experimentId);
        if (randomValue >= trafficConfig.getTotalTraffic()) {
            return null;
        }

        // 根据策略分配组
        String groupId = allocateGroup(trafficConfig, visitorId, experimentId);
        if (groupId != null) {
            cacheUserGroup(visitorId, experimentId, groupId, metadata.getConfigVersion());
            recordLayerAssignment(metadata.getLayerId(), experimentId, visitorId);
            return groupId;
        }

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
        // hashKey用于确定哈希的字段（如visitorId, deviceId等），目前实现中使用visitorId + experimentId组合
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
            
            // 转换为均匀分布的0.0-1.0（使用无符号32位整数，避免模运算带来的分布偏差）
            return (hash & 0xFFFFFFFFL) / 4294967296.0; // 除以 2^32
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
     * 缓存访客分组（同时存储 configVersion 用于失效校验）
     */
    private void cacheUserGroup(String visitorId, String experimentId, String groupId, long configVersion) {
        String cacheKey = USER_GROUP_CACHE_PREFIX + visitorId;
        redisTemplate.opsForHash().put(cacheKey, experimentId, groupId);
        redisTemplate.opsForHash().put(cacheKey, experimentId + VER_SUFFIX, String.valueOf(configVersion));
        redisTemplate.expire(cacheKey, CACHE_EXPIRE_DAYS, TimeUnit.DAYS);
    }

    /**
     * 检查分层互斥：若该访客已在同一层的其他实验中，返回已分配的实验ID；否则返回 null。
     * 仅对 MUTEX 策略生效；ORTHOGONAL 层直接放行。
     */
    private String checkLayerMutex(String layerId, String currentExperimentId, String visitorId) {
        ExperimentLayer layer = configService.

            getLayerConfig(layerId);
        if (layer == null || layer.getStrategy() != ExperimentLayer.LayerStrategy.MUTEX) {
            return null; // 层不存在或为正交层，不互斥
        }

        String layerKey = LAYER_ASSIGN_PREFIX + layerId + ":" + visitorId;
        Object assigned = redisTemplate.opsForValue().get(layerKey);
        if (assigned == null) {
            return null; // 尚未分配，可以进入
        }
        String assignedExperiment = assigned.toString();
        // 已分配到当前实验（重入），放行
        if (assignedExperiment.equals(currentExperimentId)) {
            return null;
        }
        return assignedExperiment; // 已分配到其他实验，拒绝
    }

    /**
     * 记录分层分配（访客进入某实验后标记，用于 MUTEX 互斥）
     */
    private void recordLayerAssignment(String layerId, String experimentId, String visitorId) {
        if (layerId == null) return;
        String layerKey = LAYER_ASSIGN_PREFIX + layerId + ":" + visitorId;
        redisTemplate.opsForValue().setIfAbsent(layerKey, experimentId, CACHE_EXPIRE_DAYS, TimeUnit.DAYS);
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

