package com.pisces.service.service.impl;

import com.pisces.common.model.ExperimentMetadata;
import com.pisces.service.service.ConfigService;
import com.pisces.service.service.MultiArmedBanditService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 多臂老虎机算法服务实现（基于Redis存储）
 * 实现Thompson Sampling和UCB算法，用于动态流量分配
 */
@Slf4j
@Service
public class MultiArmedBanditServiceImpl implements MultiArmedBanditService {
    
    @Autowired
    private ConfigService configService;
    
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    // Redis Key前缀
    private static final String BETA_PARAMS_PREFIX = "pisces:mab:beta:";  // Beta分布参数
    private static final String UCB_STATS_PREFIX = "pisces:mab:ucb:";  // UCB统计信息
    private static final String TOTAL_TRIALS_PREFIX = "pisces:mab:trials:";  // 总实验次数
    
    // 数据过期时间（天）
    private static final long DATA_EXPIRE_DAYS = 90;
    
    /**
     * Beta分布参数（用于Redis存储）
     */
    private static class BetaParams {
        int alpha = 1; // 成功次数 + 1
        int beta = 1;  // 失败次数 + 1
        
        BetaParams() {}
        
        BetaParams(int alpha, int beta) {
            this.alpha = alpha;
            this.beta = beta;
        }
    }
    
    /**
     * UCB统计信息（用于Redis存储）
     */
    private static class UCBStats {
        long trials = 0;      // 选择次数
        long successes = 0;  // 成功次数
        double averageReward = 0.0;  // 平均奖励
        
        UCBStats() {}
        
        UCBStats(long trials, long successes, double averageReward) {
            this.trials = trials;
            this.successes = successes;
            this.averageReward = averageReward;
        }
        
        /**
         * 更新奖励信息
         */
        void updateReward(boolean success) {
            if (success) {
                successes++;
            }
            // 更新平均奖励
            if (trials > 0) {
                averageReward = (double) successes / trials;
            }
        }
    }
    
    @Override
    public String selectGroupByThompsonSampling(String experimentId, String visitorId) {
        ExperimentMetadata metadata = configService.getExperimentConfig(experimentId);
        if (metadata == null || metadata.getGroups() == null || metadata.getGroups().isEmpty()) {
            return null;
        }
        
        String bestGroup = null;
        double maxSample = -1.0;
        
        // 从每个变体的Beta分布中采样，选择采样值最大的变体
        for (String groupId : metadata.getGroups().keySet()) {
            BetaParams params = getBetaParams(experimentId, groupId);
            
            // 从Beta分布中采样
            double sample = sampleFromBeta(params.alpha, params.beta);
            
            if (sample > maxSample) {
                maxSample = sample;
                bestGroup = groupId;
            }
        }
        
        log.debug("Thompson Sampling选择组: experimentId={}, visitorId={}, selectedGroup={}, sample={}", 
                experimentId, visitorId, bestGroup, maxSample);
        
        return bestGroup;
    }
    
    /**
     * 从Redis获取Beta参数
     */
    private BetaParams getBetaParams(String experimentId, String groupId) {
        String key = BETA_PARAMS_PREFIX + experimentId;
        Object paramsObj = redisTemplate.opsForHash().get(key, groupId);
        if (paramsObj != null && paramsObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> paramsMap = (Map<String, Object>) paramsObj;
            int alpha = paramsMap.get("alpha") != null ? 
                    ((Number) paramsMap.get("alpha")).intValue() : 1;
            int beta = paramsMap.get("beta") != null ? 
                    ((Number) paramsMap.get("beta")).intValue() : 1;
            return new BetaParams(alpha, beta);
        }
        // 如果不存在，返回默认值
        return new BetaParams();
    }
    
    /**
     * 保存Beta参数到Redis
     */
    private void saveBetaParams(String experimentId, String groupId, BetaParams params) {
        String key = BETA_PARAMS_PREFIX + experimentId;
        Map<String, Object> paramsMap = new HashMap<>();
        paramsMap.put("alpha", params.alpha);
        paramsMap.put("beta", params.beta);
        redisTemplate.opsForHash().put(key, groupId, paramsMap);
        redisTemplate.expire(key, DATA_EXPIRE_DAYS, TimeUnit.DAYS);
    }
    
    @Override
    public String selectGroupByUCB(String experimentId, String visitorId) {
        ExperimentMetadata metadata = configService.getExperimentConfig(experimentId);
        if (metadata == null || metadata.getGroups() == null || metadata.getGroups().isEmpty()) {
            return null;
        }
        
        // 获取当前总选择次数（在选择前）
        long totalTrials = getTotalTrials(experimentId);
        long t = totalTrials + 1; // 本次选择后的总次数
        String bestGroup = null;
        double maxUCB = -1.0;
        
        // UCB探索参数（可根据业务需求调整）
        double c = 2.0;
        
        // 首先检查是否有从未选择过的组（优先探索）
        for (String groupId : metadata.getGroups().keySet()) {
            UCBStats stats = getUCBStats(experimentId, groupId);
            if (stats.trials == 0) {
                // 如果从未选择过，优先选择（探索）
                bestGroup = groupId;
                maxUCB = Double.MAX_VALUE;
                break; // 找到未选择的组，直接返回
            }
        }
        
        // 如果所有组都被选择过，计算UCB值选择最优组
        if (bestGroup == null) {
            for (String groupId : metadata.getGroups().keySet()) {
                UCBStats stats = getUCBStats(experimentId, groupId);
                long n = stats.trials;
                
                // 计算UCB值: UCB_i = r̄_i + c * sqrt(ln(t) / n_i)
                double ucb = stats.averageReward + c * Math.sqrt(Math.log(t) / n);
                
                if (ucb > maxUCB) {
                    maxUCB = ucb;
                    bestGroup = groupId;
                }
            }
        }
        
        // 更新选择次数（选择后）
        if (bestGroup != null) {
            UCBStats stats = getUCBStats(experimentId, bestGroup);
            stats.trials++;
            saveUCBStats(experimentId, bestGroup, stats);
            incrementTotalTrials(experimentId);
        } else {
            // 如果所有组都未被选择（理论上不应该发生），选择第一个组
            if (!metadata.getGroups().isEmpty()) {
                bestGroup = metadata.getGroups().keySet().iterator().next();
                UCBStats stats = getUCBStats(experimentId, bestGroup);
                stats.trials++;
                saveUCBStats(experimentId, bestGroup, stats);
                incrementTotalTrials(experimentId);
                log.warn("UCB算法未找到最优组，使用第一个组: experimentId={}", experimentId);
            }
        }
        
        log.debug("UCB选择组: experimentId={}, visitorId={}, selectedGroup={}, ucb={}", 
                experimentId, visitorId, bestGroup, maxUCB);
        
        return bestGroup;
    }
    
    /**
     * 从Redis获取UCB统计信息
     */
    private UCBStats getUCBStats(String experimentId, String groupId) {
        String key = UCB_STATS_PREFIX + experimentId;
        Object statsObj = redisTemplate.opsForHash().get(key, groupId);
        if (statsObj != null && statsObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> statsMap = (Map<String, Object>) statsObj;
            long trials = statsMap.get("trials") != null ? 
                    ((Number) statsMap.get("trials")).longValue() : 0;
            long successes = statsMap.get("successes") != null ? 
                    ((Number) statsMap.get("successes")).longValue() : 0;
            double averageReward = statsMap.get("averageReward") != null ? 
                    ((Number) statsMap.get("averageReward")).doubleValue() : 0.0;
            return new UCBStats(trials, successes, averageReward);
        }
        // 如果不存在，返回默认值
        return new UCBStats();
    }
    
    /**
     * 保存UCB统计信息到Redis
     */
    private void saveUCBStats(String experimentId, String groupId, UCBStats stats) {
        String key = UCB_STATS_PREFIX + experimentId;
        Map<String, Object> statsMap = new HashMap<>();
        statsMap.put("trials", stats.trials);
        statsMap.put("successes", stats.successes);
        statsMap.put("averageReward", stats.averageReward);
        redisTemplate.opsForHash().put(key, groupId, statsMap);
        redisTemplate.expire(key, DATA_EXPIRE_DAYS, TimeUnit.DAYS);
    }
    
    /**
     * 获取总实验次数
     */
    private long getTotalTrials(String experimentId) {
        String key = TOTAL_TRIALS_PREFIX + experimentId;
        Object trials = redisTemplate.opsForValue().get(key);
        return trials != null ? ((Number) trials).longValue() : 0;
    }
    
    /**
     * 增加总实验次数
     */
    private void incrementTotalTrials(String experimentId) {
        String key = TOTAL_TRIALS_PREFIX + experimentId;
        redisTemplate.opsForValue().increment(key);
        redisTemplate.expire(key, DATA_EXPIRE_DAYS, TimeUnit.DAYS);
    }
    
    @Override
    public void updateReward(String experimentId, String groupId, boolean success) {
        // 更新Beta分布参数（用于Thompson Sampling）
        BetaParams params = getBetaParams(experimentId, groupId);
        if (success) {
            params.alpha++;
        } else {
            params.beta++;
        }
        saveBetaParams(experimentId, groupId, params);
        
        // 更新UCB统计信息
        UCBStats stats = getUCBStats(experimentId, groupId);
        stats.updateReward(success);
        saveUCBStats(experimentId, groupId, stats);
        
        log.debug("更新奖励: experimentId={}, groupId={}, success={}", experimentId, groupId, success);
    }
    
    @Override
    public Map<String, Integer> getBetaParameters(String experimentId, String groupId) {
        BetaParams params = getBetaParams(experimentId, groupId);
        Map<String, Integer> result = new HashMap<>();
        result.put("alpha", params.alpha);
        result.put("beta", params.beta);
        return result;
    }
    
    @Override
    public Map<String, Object> getGroupStatistics(String experimentId, String groupId) {
        UCBStats stats = getUCBStats(experimentId, groupId);
        Map<String, Object> result = new HashMap<>();
        result.put("trials", stats.trials);
        result.put("successes", stats.successes);
        result.put("averageReward", stats.averageReward);
        return result;
    }
    
    /**
     * 从Beta分布中采样
     * 使用近似方法：通过Gamma分布采样
     */
    private double sampleFromBeta(int alpha, int beta) {
        // 简化实现：使用Gamma分布的近似
        // 实际应用中可以使用Apache Commons Math库的BetaDistribution
        double gammaAlpha = sampleFromGamma(alpha);
        double gammaBeta = sampleFromGamma(beta);
        double sum = gammaAlpha + gammaBeta;
        return sum > 0 ? gammaAlpha / sum : 0.5;
    }
    
    /**
     * 从Gamma分布中采样（简化实现）
     * 实际应用中应使用专业的统计库
     */
    private double sampleFromGamma(int shape) {
        // 简化实现：使用Box-Muller变换生成正态分布，然后转换为Gamma分布
        // 这里使用更简单的近似方法
        if (shape <= 0) {
            return 0.0;
        }
        
        // 使用中心极限定理近似
        double sum = 0.0;
        for (int i = 0; i < shape; i++) {
            sum += -Math.log(Math.random());
        }
        return sum;
    }
    
    @Override
    public Map<String, Double> getAllocationProbabilities(String experimentId) {
        ExperimentMetadata metadata = configService.getExperimentConfig(experimentId);
        if (metadata == null || metadata.getGroups() == null || metadata.getGroups().isEmpty()) {
            return new HashMap<>();
        }
        
        // 使用蒙特卡洛模拟计算各组被选中的概率
        int numSimulations = 10000;
        Map<String, Integer> selectionCounts = new HashMap<>();
        
        for (String groupId : metadata.getGroups().keySet()) {
            selectionCounts.put(groupId, 0);
        }
        
        for (int i = 0; i < numSimulations; i++) {
            String bestGroup = null;
            double maxSample = -1.0;
            
            for (String groupId : metadata.getGroups().keySet()) {
                BetaParams params = getBetaParams(experimentId, groupId);
                double sample = sampleFromBeta(params.alpha, params.beta);
                
                if (sample > maxSample) {
                    maxSample = sample;
                    bestGroup = groupId;
                }
            }
            
            if (bestGroup != null) {
                selectionCounts.put(bestGroup, selectionCounts.get(bestGroup) + 1);
            }
        }
        
        // 转换为概率
        Map<String, Double> probabilities = new HashMap<>();
        for (Map.Entry<String, Integer> entry : selectionCounts.entrySet()) {
            probabilities.put(entry.getKey(), (double) entry.getValue() / numSimulations);
        }
        
        return probabilities;
    }
    
    @Override
    public Map<String, Object> getMABSummary(String experimentId) {
        ExperimentMetadata metadata = configService.getExperimentConfig(experimentId);
        if (metadata == null || metadata.getGroups() == null || metadata.getGroups().isEmpty()) {
            return new HashMap<>();
        }
        
        Map<String, Object> summary = new HashMap<>();
        summary.put("experimentId", experimentId);
        
        // 获取分配概率
        Map<String, Double> probabilities = getAllocationProbabilities(experimentId);
        summary.put("allocationProbabilities", probabilities);
        
        // 获取每个组的详细统计
        Map<String, Object> groupDetails = new HashMap<>();
        long totalTrials = getTotalTrials(experimentId);
        
        double maxProbability = 0.0;
        String leadingGroup = null;
        
        for (String groupId : metadata.getGroups().keySet()) {
            Map<String, Object> groupDetail = new HashMap<>();
            
            // Beta参数
            BetaParams params = getBetaParams(experimentId, groupId);
            groupDetail.put("alpha", params.alpha);
            groupDetail.put("beta", params.beta);
            groupDetail.put("successRate", (double) (params.alpha - 1) / (params.alpha + params.beta - 2));
            
            // UCB统计
            UCBStats stats = getUCBStats(experimentId, groupId);
            groupDetail.put("trials", stats.trials);
            groupDetail.put("successes", stats.successes);
            groupDetail.put("averageReward", stats.averageReward);
            
            // 分配概率
            Double probability = probabilities.get(groupId);
            groupDetail.put("allocationProbability", probability);
            
            if (probability != null && probability > maxProbability) {
                maxProbability = probability;
                leadingGroup = groupId;
            }
            
            groupDetails.put(groupId, groupDetail);
        }
        
        summary.put("groupDetails", groupDetails);
        summary.put("totalTrials", totalTrials);
        summary.put("leadingGroup", leadingGroup);
        summary.put("leadingGroupProbability", maxProbability);
        
        // 判断是否已收敛（当领先组概率>95%时认为已收敛）
        boolean converged = maxProbability >= 0.95;
        summary.put("converged", converged);
        summary.put("convergenceThreshold", 0.95);
        
        if (converged) {
            summary.put("recommendation", 
                    "实验已收敛，建议停止实验并将流量全量切换到 " + leadingGroup);
        } else {
            summary.put("recommendation", 
                    "实验尚未收敛，当前领先组为 " + leadingGroup + "（概率" + 
                    String.format("%.1f%%", maxProbability * 100) + "），建议继续收集数据");
        }
        
        return summary;
    }
    
    @Override
    public void resetMABData(String experimentId) {
        ExperimentMetadata metadata = configService.getExperimentConfig(experimentId);
        if (metadata == null || metadata.getGroups() == null) {
            return;
        }
        
        // 重置Beta参数
        String betaKey = BETA_PARAMS_PREFIX + experimentId;
        redisTemplate.delete(betaKey);
        
        // 重置UCB统计
        String ucbKey = UCB_STATS_PREFIX + experimentId;
        redisTemplate.delete(ucbKey);
        
        // 重置总实验次数
        String trialsKey = TOTAL_TRIALS_PREFIX + experimentId;
        redisTemplate.delete(trialsKey);
        
        log.info("重置MAB数据: experimentId={}", experimentId);
    }
}
