package com.pisces.service.service.impl;

import com.pisces.common.model.ExperimentAssignment;
import com.pisces.common.enums.ResponseCode;
import com.pisces.common.model.ExperimentLayer;
import com.pisces.common.model.ExperimentMetadata;
import com.pisces.common.model.TrafficConfig;
import com.pisces.common.response.TrafficAssignmentResponse;
import com.pisces.service.repository.ExperimentAssignmentRepository;
import com.pisces.service.metrics.TrafficAssignmentMetrics;
import com.pisces.service.security.ApiKeyContextHolder;
import com.pisces.service.service.ConfigService;
import com.pisces.service.service.IdentityService;
import com.pisces.service.service.MultiArmedBanditService;
import com.pisces.service.service.TrafficService;
import com.pisces.service.exception.BusinessException;
import com.pisces.service.rule.TrafficRuleEvaluator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
    private IdentityService identityService;

    @Autowired
    private TrafficRuleEvaluator trafficRuleEvaluator;

    @Autowired
    private ExperimentAssignmentRepository experimentAssignmentRepository;
    
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired(required = false)
    private TrafficAssignmentMetrics trafficAssignmentMetrics;
    
    // Redis Key前缀
    private static final String USER_GROUP_CACHE_PREFIX = "pisces:traffic:group:";  // 访客分组缓存
    private static final String LAYER_ASSIGN_PREFIX = "pisces:layer:assign:";        // 分层互斥缓存
    private static final String ASSIGNMENT_PREFIX = "pisces:assignment:";
    private static final String ASSIGNMENT_SET_PREFIX = "pisces:assignment:set:";

    // 缓存过期时间（天）
    private static final long CACHE_EXPIRE_DAYS = 30;
    // 版本字段后缀（同一 Hash key 中存储上次缓存时的 configVersion）
    private static final String VER_SUFFIX = ":ver";
    private static final String CACHE_OPERATION_USER_GROUP = "USER_GROUP";
    private static final String CACHE_OPERATION_USER_GROUP_VERSION = "USER_GROUP_VERSION";
    private static final String CACHE_OPERATION_USER_GROUP_DELETE = "USER_GROUP_DELETE";
    private static final String CACHE_OPERATION_USER_GROUP_WRITE = "USER_GROUP_WRITE";
    private static final String CACHE_OPERATION_LAYER_ASSIGNMENT_READ = "LAYER_ASSIGNMENT_READ";
    private static final String CACHE_OPERATION_LAYER_ASSIGNMENT_WRITE = "LAYER_ASSIGNMENT_WRITE";
    private static final String CACHE_OPERATION_ASSIGNMENT_PROJECTION_WRITE = "ASSIGNMENT_PROJECTION_WRITE";
    private static final String CACHE_OPERATION_ASSIGNMENT_SET_WRITE = "ASSIGNMENT_SET_WRITE";
    private static final String CACHE_OPERATION_ASSIGNMENT_SET_DELETE = "ASSIGNMENT_SET_DELETE";
    private static final String CACHE_RESULT_HIT = "HIT";
    private static final String CACHE_RESULT_MISS = "MISS";
    private static final String CACHE_RESULT_SUCCESS = "SUCCESS";
    private static final String CACHE_RESULT_ERROR = "ERROR";

    /**
     * 分配用户到实验组
     * 加入 configVersion 校验：若实验配置变更，旧缓存自动失效并重新分配
     */
    @Override
    public String assignGroup(String experimentId, String visitorId) {
        return assignGroup(experimentId, visitorId, Collections.emptyMap());
    }

    @Override
    public String assignGroup(String experimentId, String visitorId, Map<String, Object> attributes) {
        return assignGroupWithTrace(experimentId, visitorId, attributes).getGroupId();
    }

    @Override
    public TrafficAssignmentResponse assignGroupWithTrace(String experimentId, String visitorId,
                                                          Map<String, Object> attributes) {
        long startedNanos = System.nanoTime();
        try {
            TrafficAssignmentResponse response = doAssignGroupWithTrace(experimentId, visitorId, attributes);
            recordAssignmentMetric(response, startedNanos);
            return response;
        } catch (RuntimeException exception) {
            recordAssignmentError(startedNanos);
            throw exception;
        }
    }

    private TrafficAssignmentResponse doAssignGroupWithTrace(String experimentId, String visitorId,
                                                             Map<String, Object> attributes) {
        String canonicalVisitorId = resolveCanonicalVisitorId(visitorId);

        // 先获取实验配置（需要 configVersion 做缓存校验）
        ExperimentMetadata metadata = getAccessibleExperimentMetadata(experimentId);

        migrateExistingIdentityAssignmentIfNeeded(experimentId, visitorId, canonicalVisitorId, metadata);

        // 检查 Redis 缓存（带版本校验）
        String cacheKey = USER_GROUP_CACHE_PREFIX + canonicalVisitorId;
        String verField  = experimentId + VER_SUFFIX;
        Object cachedGroupId = readTrafficCache(cacheKey, experimentId, CACHE_OPERATION_USER_GROUP);
        Object cachedVersion = readTrafficCache(cacheKey, verField, CACHE_OPERATION_USER_GROUP_VERSION);

        if (cachedGroupId != null && cachedVersion != null) {
            try {
                long cachedVer = Long.parseLong(cachedVersion.toString());
                if (cachedVer == metadata.getConfigVersion()) {
                    return buildAssignmentResponse(experimentId, visitorId, canonicalVisitorId,
                            cachedGroupId.toString(), metadata, "CACHE_HIT", "CACHE");
                }
                // 配置已更新，删除旧缓存字段，重新分配
                deleteTrafficCacheFields(cacheKey, experimentId, verField);
                log.info("实验配置变更（v{} → v{}），访客 {} 重新分配分组",
                        cachedVer, metadata.getConfigVersion(), canonicalVisitorId);
            } catch (NumberFormatException ignored) {
                // 格式异常，视为版本不匹配，重新分配
                deleteTrafficCacheFields(cacheKey, experimentId, verField);
            }
        }

        // 检查实验状态
        if (metadata.getExperiment().getStatus() != com.pisces.common.model.Experiment.ExperimentStatus.RUNNING) {
            return buildAssignmentResponse(experimentId, visitorId, canonicalVisitorId,
                    null, metadata, "EXPERIMENT_NOT_RUNNING", "BLOCKED");
        }

        // 检查白名单/黑名单
        if (metadata.getWhitelist() != null && metadata.getWhitelist().contains(canonicalVisitorId)) {
            if (metadata.getGroups() != null && !metadata.getGroups().isEmpty()) {
                String groupId = metadata.getGroups().keySet().iterator().next();
                cacheUserGroup(canonicalVisitorId, experimentId, groupId, metadata.getConfigVersion());
                recordAssignment(experimentId, canonicalVisitorId, groupId, metadata, Collections.emptyMap());
                return buildAssignmentResponse(experimentId, visitorId, canonicalVisitorId,
                        groupId, metadata, "WHITELIST", "NEW_ASSIGNMENT");
            }
        }

        if (metadata.getBlacklist() != null && metadata.getBlacklist().contains(canonicalVisitorId)) {
            return buildAssignmentResponse(experimentId, visitorId, canonicalVisitorId,
                    null, metadata, "BLACKLIST", "BLOCKED");
        }

        // 检查时间范围
        if (!isInTimeRange(metadata.getExperiment())) {
            return buildAssignmentResponse(experimentId, visitorId, canonicalVisitorId,
                    null, metadata, "OUT_OF_TIME_RANGE", "BLOCKED");
        }

        // 分层互斥检查：同一 MUTEX 层内，每个访客只能进入一个实验
        if (metadata.getLayerId() != null) {
            String blockedExperiment = checkLayerMutex(metadata.getLayerId(), experimentId, canonicalVisitorId);
            if (blockedExperiment != null) {
                log.debug("访客 {} 已在层 {} 的实验 {} 中，实验 {} 被拒绝（互斥）",
                        canonicalVisitorId, metadata.getLayerId(), blockedExperiment, experimentId);
                return buildAssignmentResponse(experimentId, visitorId, canonicalVisitorId,
                        null, metadata, "LAYER_MUTEX:" + blockedExperiment, "BLOCKED");
            }
        }

        // 根据流量配置分配
        TrafficConfig trafficConfig = metadata.getTraffic();
        if (trafficConfig == null || trafficConfig.getTotalTraffic() == null) {
            return buildAssignmentResponse(experimentId, visitorId, canonicalVisitorId,
                    null, metadata, "TRAFFIC_NOT_CONFIGURED", "BLOCKED");
        }

        // 检查是否在流量范围内
        double randomValue = generateHashValue(canonicalVisitorId + experimentId);
        if (randomValue >= trafficConfig.getTotalTraffic()) {
            return buildAssignmentResponse(experimentId, visitorId, canonicalVisitorId,
                    null, metadata, "OUT_OF_TRAFFIC", "BLOCKED");
        }

        // 根据策略分配组
        Map<String, Object> ruleContext = buildRuleContext(experimentId, canonicalVisitorId, attributes);
        String groupId = allocateGroup(trafficConfig, canonicalVisitorId, experimentId, ruleContext);
        if (groupId != null) {
            cacheUserGroup(canonicalVisitorId, experimentId, groupId, metadata.getConfigVersion());
            recordAssignment(experimentId, canonicalVisitorId, groupId, metadata, attributes);
            recordLayerAssignment(metadata.getLayerId(), experimentId, canonicalVisitorId);
            return buildAssignmentResponse(experimentId, visitorId, canonicalVisitorId,
                    groupId, metadata, "ALLOCATED", "NEW_ASSIGNMENT");
        }

        return buildAssignmentResponse(experimentId, visitorId, canonicalVisitorId,
                null, metadata, "NO_GROUP_ALLOCATED", "BLOCKED");
    }

    private TrafficAssignmentResponse buildAssignmentResponse(String experimentId, String visitorId,
                                                              String canonicalVisitorId, String groupId,
                                                              ExperimentMetadata metadata, String reason,
                                                              String source) {
        TrafficAssignmentResponse response = new TrafficAssignmentResponse();
        response.setExperimentId(experimentId);
        response.setVisitorId(visitorId);
        response.setCanonicalVisitorId(canonicalVisitorId);
        response.setGroupId(groupId);
        response.setAssigned(groupId != null);
        response.setReason(reason);
        response.setSource(source);
        response.setStrategy(resolveTrafficStrategy(metadata));
        response.setConfigVersion(metadata != null ? metadata.getConfigVersion() : null);
        return response;
    }

    private void recordAssignmentMetric(TrafficAssignmentResponse response, long startedNanos) {
        if (trafficAssignmentMetrics == null) {
            return;
        }
        trafficAssignmentMetrics.recordAssignment(response, elapsedNanos(startedNanos));
    }

    private void recordAssignmentError(long startedNanos) {
        if (trafficAssignmentMetrics == null) {
            return;
        }
        trafficAssignmentMetrics.recordAssignmentError(elapsedNanos(startedNanos));
    }

    private void recordCacheEvent(String operation, String result) {
        if (trafficAssignmentMetrics == null) {
            return;
        }
        trafficAssignmentMetrics.recordCacheEvent(operation, result);
    }

    private long elapsedNanos(long startedNanos) {
        return System.nanoTime() - startedNanos;
    }

    private String resolveTrafficStrategy(ExperimentMetadata metadata) {
        if (metadata == null || metadata.getTraffic() == null || metadata.getTraffic().getStrategy() == null) {
            return null;
        }
        return metadata.getTraffic().getStrategy().name();
    }
    
    /**
     * 获取访客所在组
     */
    @Override
    public String getUserGroup(String experimentId, String visitorId) {
        String canonicalVisitorId = resolveCanonicalVisitorId(visitorId);
        getAccessibleExperimentMetadata(experimentId);

        // 从Redis缓存获取
        String cacheKey = USER_GROUP_CACHE_PREFIX + canonicalVisitorId;
        Object cachedGroupId = readTrafficCache(cacheKey, experimentId, CACHE_OPERATION_USER_GROUP);
        if (cachedGroupId != null) {
            return cachedGroupId.toString();
        }

        if (!canonicalVisitorId.equals(visitorId)) {
            Object legacyGroupId = readTrafficCache(USER_GROUP_CACHE_PREFIX + visitorId, experimentId,
                    CACHE_OPERATION_USER_GROUP);
            if (legacyGroupId != null) {
                return legacyGroupId.toString();
            }
        }
        
        // 如果缓存中没有，重新分配
        return assignGroup(experimentId, canonicalVisitorId);
    }

    @Override
    public ExperimentAssignment getAssignment(String experimentId, String visitorId) {
        String canonicalVisitorId = resolveCanonicalVisitorId(visitorId);
        getAccessibleExperimentMetadata(experimentId);
        return experimentAssignmentRepository.findByExperimentIdAndVisitorId(experimentId, canonicalVisitorId)
                .orElse(null);
    }

    private ExperimentMetadata getAccessibleExperimentMetadata(String experimentId) {
        ExperimentMetadata metadata = configService.getExperimentConfig(experimentId);
        if (metadata == null) {
            throw new BusinessException(ResponseCode.EXPERIMENT_NOT_FOUND);
        }
        ApiKeyContextHolder.assertCanAccess(metadata);
        return metadata;
    }
    
    /**
     * 根据策略分配组
     */
    private String allocateGroup(TrafficConfig trafficConfig, String visitorId, String experimentId,
                                 Map<String, Object> ruleContext) {
        TrafficConfig.TrafficStrategy strategy = trafficConfig.getStrategy();
        
        if (strategy == TrafficConfig.TrafficStrategy.HASH) {
            return allocateByHash(trafficConfig, visitorId, experimentId);
        }
        if (strategy == TrafficConfig.TrafficStrategy.RANDOM) {
            return allocateByRandom(trafficConfig);
        }
        if (strategy == TrafficConfig.TrafficStrategy.RULE) {
            String matchedGroup = trafficRuleEvaluator.evaluateGroup(trafficConfig, ruleContext);
            if (matchedGroup != null) {
                return matchedGroup;
            }
            return allocateByRuleFallback(trafficConfig, visitorId, experimentId);
        }
        if (strategy == TrafficConfig.TrafficStrategy.THOMPSON_SAMPLING) {
            // 多臂老虎机算法：Thompson Sampling
            return mabService.selectGroupByThompsonSampling(experimentId, visitorId);
        }
        if (strategy == TrafficConfig.TrafficStrategy.UCB) {
            // 多臂老虎机算法：UCB
            return mabService.selectGroupByUCB(experimentId, visitorId);
        }
        return null;
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

    private String allocateByRuleFallback(TrafficConfig trafficConfig, String visitorId, String experimentId) {
        TrafficConfig.RuleFallbackStrategy fallbackStrategy = trafficConfig.getRuleFallbackStrategy();
        if (fallbackStrategy == null || fallbackStrategy == TrafficConfig.RuleFallbackStrategy.HASH) {
            return allocateByHash(trafficConfig, visitorId, experimentId);
        }

        List<TrafficConfig.GroupAllocation> allocations = trafficConfig.getAllocation();
        if (CollectionUtils.isEmpty(allocations)) {
            return null;
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
        try {
            redisTemplate.opsForHash().put(cacheKey, experimentId, groupId);
            redisTemplate.opsForHash().put(cacheKey, experimentId + VER_SUFFIX, String.valueOf(configVersion));
            redisTemplate.expire(cacheKey, CACHE_EXPIRE_DAYS, TimeUnit.DAYS);
            recordCacheEvent(CACHE_OPERATION_USER_GROUP_WRITE, CACHE_RESULT_SUCCESS);
        } catch (Exception exception) {
            recordCacheEvent(CACHE_OPERATION_USER_GROUP_WRITE, CACHE_RESULT_ERROR);
            log.warn("分流缓存写入失败，降级为无缓存分流: experimentId={}, visitorId={}",
                    experimentId, visitorId, exception);
        }
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
        Object assigned = readLayerAssignment(layerKey, layerId, currentExperimentId, visitorId);
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
        if (layerId == null) {
            return;
        }
        String layerKey = LAYER_ASSIGN_PREFIX + layerId + ":" + visitorId;
        try {
            redisTemplate.opsForValue().setIfAbsent(layerKey, experimentId, CACHE_EXPIRE_DAYS, TimeUnit.DAYS);
            recordCacheEvent(CACHE_OPERATION_LAYER_ASSIGNMENT_WRITE, CACHE_RESULT_SUCCESS);
        } catch (Exception exception) {
            recordCacheEvent(CACHE_OPERATION_LAYER_ASSIGNMENT_WRITE, CACHE_RESULT_ERROR);
            log.warn("分层互斥缓存写入失败，保留分流结果但互斥缓存暂不可用: layerId={}, experimentId={}, visitorId={}",
                    layerId, experimentId, visitorId, exception);
        }
    }
    
    /**
     * 获取访客参与的所有实验
     */
    @Override
    public Map<String, String> getUserExperiments(String visitorId) {
        String canonicalVisitorId = resolveCanonicalVisitorId(visitorId);
        List<ExperimentAssignment> assignments = experimentAssignmentRepository.listByVisitorId(canonicalVisitorId);
        Map<String, String> result = new HashMap<>();
        for (ExperimentAssignment assignment : assignments) {
            result.put(assignment.getExperimentId(), assignment.getGroupId());
        }
        return result;
    }

    private void recordAssignment(String experimentId, String visitorId, String groupId, ExperimentMetadata metadata,
                                  Map<String, Object> attributes) {
        Optional<ExperimentAssignment> previousAssignmentOptional = experimentAssignmentRepository
                .findByExperimentIdAndVisitorId(experimentId, visitorId);
        ExperimentAssignment previousAssignment = previousAssignmentOptional.orElse(null);

        ExperimentAssignment assignment = buildAssignment(experimentId, visitorId, groupId, metadata, attributes, previousAssignment);
        experimentAssignmentRepository.save(assignment);
        cacheAssignmentProjection(assignment);

        if (previousAssignment != null && previousAssignment.getGroupId() != null
                && !previousAssignment.getGroupId().equals(groupId)) {
            removeAssignmentSetProjection(experimentId, previousAssignment.getGroupId(), visitorId);
        }

        addAssignmentSetProjection(experimentId, groupId, visitorId);
    }

    private ExperimentAssignment buildAssignment(String experimentId, String visitorId, String groupId,
                                                 ExperimentMetadata metadata, Map<String, Object> attributes,
                                                 ExperimentAssignment previousAssignment) {
        ExperimentAssignment assignment = new ExperimentAssignment();
        assignment.setAssignmentId(previousAssignment != null ? previousAssignment.getAssignmentId()
                : buildAssignmentId());
        assignment.setExperimentId(experimentId);
        assignment.setVisitorId(visitorId);
        assignment.setGroupId(groupId);
        assignment.setAssignedAt(java.time.LocalDateTime.now());
        assignment.setConfigVersion(metadata.getConfigVersion());
        assignment.setStrategy(metadata.getTraffic() != null && metadata.getTraffic().getStrategy() != null
                ? metadata.getTraffic().getStrategy().name() : null);
        assignment.setHashKey(metadata.getTraffic() != null ? metadata.getTraffic().getHashKey() : null);
        assignment.setAttributes(attributes != null ? new HashMap<>(attributes) : Collections.emptyMap());
        assignment.setIdempotencyKey(buildAssignmentIdempotencyKey(experimentId, visitorId));
        return assignment;
    }

    private void cacheAssignmentProjection(ExperimentAssignment assignment) {
        try {
            redisTemplate.opsForValue().set(buildAssignmentKey(assignment.getExperimentId(), assignment.getVisitorId()),
                    assignment, CACHE_EXPIRE_DAYS, TimeUnit.DAYS);
            recordCacheEvent(CACHE_OPERATION_ASSIGNMENT_PROJECTION_WRITE, CACHE_RESULT_SUCCESS);
        } catch (Exception exception) {
            recordCacheEvent(CACHE_OPERATION_ASSIGNMENT_PROJECTION_WRITE, CACHE_RESULT_ERROR);
            log.warn("分流事实缓存写入失败，数据库事实已保留: experimentId={}, visitorId={}",
                    assignment.getExperimentId(), assignment.getVisitorId(), exception);
        }
    }

    private String buildAssignmentKey(String experimentId, String visitorId) {
        return ASSIGNMENT_PREFIX + experimentId + ":" + visitorId;
    }

    private String buildAssignmentSetKey(String experimentId, String groupId) {
        return ASSIGNMENT_SET_PREFIX + experimentId + ":" + groupId;
    }

    private String buildAssignmentId() {
        return "asn_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private String buildAssignmentIdempotencyKey(String experimentId, String visitorId) {
        return experimentId + ":" + visitorId;
    }

    private Map<String, Object> buildRuleContext(String experimentId, String visitorId, Map<String, Object> attributes) {
        Map<String, Object> ruleContext = new LinkedHashMap<>();
        ruleContext.put("experimentId", experimentId);
        ruleContext.put("visitorId", visitorId);
        if (attributes != null && !attributes.isEmpty()) {
            ruleContext.putAll(attributes);
        }
        return ruleContext;
    }

    private String resolveCanonicalVisitorId(String visitorId) {
        return identityService != null ? identityService.resolveCanonicalId(visitorId) : visitorId;
    }

    private void migrateExistingIdentityAssignmentIfNeeded(String experimentId, String rawVisitorId,
                                                           String canonicalVisitorId, ExperimentMetadata metadata) {
        if (canonicalVisitorId == null || canonicalVisitorId.equals(rawVisitorId)) {
            return;
        }

        Object legacyGroupId = readTrafficCache(USER_GROUP_CACHE_PREFIX + rawVisitorId, experimentId,
                CACHE_OPERATION_USER_GROUP);
        Object legacyVersion = readTrafficCache(USER_GROUP_CACHE_PREFIX + rawVisitorId, experimentId + VER_SUFFIX,
                CACHE_OPERATION_USER_GROUP_VERSION);
        if (legacyGroupId == null) {
            return;
        }

        long configVersion = metadata.getConfigVersion();
        if (legacyVersion != null) {
            try {
                configVersion = Long.parseLong(legacyVersion.toString());
            } catch (NumberFormatException ignored) {
                // 使用当前配置版本继续迁移
            }
        }

        cacheUserGroup(canonicalVisitorId, experimentId, legacyGroupId.toString(), configVersion);
        recordAssignment(experimentId, canonicalVisitorId, legacyGroupId.toString(), metadata,
                Collections.emptyMap());
    }

    private Object readTrafficCache(String cacheKey, String field, String operation) {
        try {
            Object cacheValue = redisTemplate.opsForHash().get(cacheKey, field);
            recordCacheEvent(operation, cacheValue != null ? CACHE_RESULT_HIT : CACHE_RESULT_MISS);
            return cacheValue;
        } catch (Exception exception) {
            recordCacheEvent(operation, CACHE_RESULT_ERROR);
            log.warn("分流缓存读取失败，降级为重新计算: cacheKey={}, field={}", cacheKey, field, exception);
            return null;
        }
    }

    private void deleteTrafficCacheFields(String cacheKey, String... fields) {
        try {
            redisTemplate.opsForHash().delete(cacheKey, (Object[]) fields);
            recordCacheEvent(CACHE_OPERATION_USER_GROUP_DELETE, CACHE_RESULT_SUCCESS);
        } catch (Exception exception) {
            recordCacheEvent(CACHE_OPERATION_USER_GROUP_DELETE, CACHE_RESULT_ERROR);
            log.warn("分流缓存删除失败，将在后续请求继续按版本校验: cacheKey={}", cacheKey, exception);
        }
    }

    private Object readLayerAssignment(String layerKey, String layerId, String experimentId, String visitorId) {
        try {
            Object assignment = redisTemplate.opsForValue().get(layerKey);
            recordCacheEvent(CACHE_OPERATION_LAYER_ASSIGNMENT_READ,
                    assignment != null ? CACHE_RESULT_HIT : CACHE_RESULT_MISS);
            return assignment;
        } catch (Exception exception) {
            recordCacheEvent(CACHE_OPERATION_LAYER_ASSIGNMENT_READ, CACHE_RESULT_ERROR);
            log.warn("分层互斥缓存读取失败，分流降级为放行: layerId={}, experimentId={}, visitorId={}",
                    layerId, experimentId, visitorId, exception);
            return null;
        }
    }

    private void removeAssignmentSetProjection(String experimentId, String groupId, String visitorId) {
        try {
            redisTemplate.opsForSet().remove(buildAssignmentSetKey(experimentId, groupId), visitorId);
            recordCacheEvent(CACHE_OPERATION_ASSIGNMENT_SET_DELETE, CACHE_RESULT_SUCCESS);
        } catch (Exception exception) {
            recordCacheEvent(CACHE_OPERATION_ASSIGNMENT_SET_DELETE, CACHE_RESULT_ERROR);
            log.warn("分流集合缓存删除失败: experimentId={}, groupId={}, visitorId={}",
                    experimentId, groupId, visitorId, exception);
        }
    }

    private void addAssignmentSetProjection(String experimentId, String groupId, String visitorId) {
        String assignmentSetKey = buildAssignmentSetKey(experimentId, groupId);
        try {
            redisTemplate.opsForSet().add(assignmentSetKey, visitorId);
            redisTemplate.expire(assignmentSetKey, CACHE_EXPIRE_DAYS, TimeUnit.DAYS);
            recordCacheEvent(CACHE_OPERATION_ASSIGNMENT_SET_WRITE, CACHE_RESULT_SUCCESS);
        } catch (Exception exception) {
            recordCacheEvent(CACHE_OPERATION_ASSIGNMENT_SET_WRITE, CACHE_RESULT_ERROR);
            log.warn("分流集合缓存写入失败，数据库事实已保留: experimentId={}, groupId={}, visitorId={}",
                    experimentId, groupId, visitorId, exception);
        }
    }
}
