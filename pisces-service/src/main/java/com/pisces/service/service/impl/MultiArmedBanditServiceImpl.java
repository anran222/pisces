package com.pisces.service.service.impl;

import com.pisces.common.model.ExperimentMetadata;
import com.pisces.service.service.ConfigService;
import com.pisces.service.service.MultiArmedBanditService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 多臂老虎机算法服务实现
 * 实现Thompson Sampling和UCB算法，用于动态流量分配
 */
@Slf4j
@Service
public class MultiArmedBanditServiceImpl implements MultiArmedBanditService {
    
    @Autowired
    private ConfigService configService;
    
    /**
     * Beta分布参数缓存（experimentId -> groupId -> {alpha, beta}）
     * 用于Thompson Sampling算法
     */
    private final ConcurrentHashMap<String, Map<String, BetaParams>> betaParamsCache = new ConcurrentHashMap<>();
    
    /**
     * UCB统计信息缓存（experimentId -> groupId -> UCBStats）
     * 用于UCB算法
     */
    private final ConcurrentHashMap<String, Map<String, UCBStats>> ucbStatsCache = new ConcurrentHashMap<>();
    
    /**
     * 总实验次数（用于UCB算法）
     */
    private final ConcurrentHashMap<String, AtomicLong> totalTrialsCache = new ConcurrentHashMap<>();
    
    /**
     * Beta分布参数
     */
    private static class BetaParams {
        AtomicInteger alpha = new AtomicInteger(1); // 成功次数 + 1
        AtomicInteger beta = new AtomicInteger(1);  // 失败次数 + 1
        
        BetaParams() {}
    }
    
    /**
     * UCB统计信息
     */
    private static class UCBStats {
        AtomicLong trials = new AtomicLong(0);      // 选择次数（在选择时递增）
        AtomicLong successes = new AtomicLong(0);  // 成功次数
        double averageReward = 0.0;                // 平均奖励
        
        /**
         * 更新奖励信息（不递增trials，因为trials在选择时已经递增）
         */
        void updateReward(boolean success) {
            if (success) {
                successes.incrementAndGet();
            }
            // 更新平均奖励
            long n = trials.get();
            if (n > 0) {
                averageReward = (double) successes.get() / n;
            }
        }
    }
    
    @Override
    public String selectGroupByThompsonSampling(String experimentId, String visitorId) {
        ExperimentMetadata metadata = configService.getExperimentConfig(experimentId);
        if (metadata == null || metadata.getGroups() == null || metadata.getGroups().isEmpty()) {
            return null;
        }
        
        // 初始化Beta参数（如果不存在）
        Map<String, BetaParams> betaParams = betaParamsCache.computeIfAbsent(
            experimentId, k -> new ConcurrentHashMap<>());
        
        String bestGroup = null;
        double maxSample = -1.0;
        
        // 从每个变体的Beta分布中采样，选择采样值最大的变体
        for (String groupId : metadata.getGroups().keySet()) {
            BetaParams params = betaParams.computeIfAbsent(groupId, k -> new BetaParams());
            
            // 从Beta分布中采样
            double sample = sampleFromBeta(params.alpha.get(), params.beta.get());
            
            if (sample > maxSample) {
                maxSample = sample;
                bestGroup = groupId;
            }
        }
        
        log.debug("Thompson Sampling选择组: experimentId={}, visitorId={}, selectedGroup={}, sample={}", 
                experimentId, visitorId, bestGroup, maxSample);
        
        return bestGroup;
    }
    
    @Override
    public String selectGroupByUCB(String experimentId, String visitorId) {
        ExperimentMetadata metadata = configService.getExperimentConfig(experimentId);
        if (metadata == null || metadata.getGroups() == null || metadata.getGroups().isEmpty()) {
            return null;
        }
        
        // 初始化统计信息
        Map<String, UCBStats> statsMap = ucbStatsCache.computeIfAbsent(
            experimentId, k -> new ConcurrentHashMap<>());
        AtomicLong totalTrials = totalTrialsCache.computeIfAbsent(
            experimentId, k -> new AtomicLong(0));
        
        // 获取当前总选择次数（在选择前）
        long t = totalTrials.get() + 1; // 本次选择后的总次数
        String bestGroup = null;
        double maxUCB = -1.0;
        
        // UCB探索参数（可根据业务需求调整）
        double c = 2.0;
        
        // 首先检查是否有从未选择过的组（优先探索）
        for (String groupId : metadata.getGroups().keySet()) {
            UCBStats stats = statsMap.computeIfAbsent(groupId, k -> new UCBStats());
            if (stats.trials.get() == 0) {
                // 如果从未选择过，优先选择（探索）
                bestGroup = groupId;
                maxUCB = Double.MAX_VALUE;
                break; // 找到未选择的组，直接返回
            }
        }
        
        // 如果所有组都被选择过，计算UCB值选择最优组
        if (bestGroup == null) {
            for (String groupId : metadata.getGroups().keySet()) {
                UCBStats stats = statsMap.get(groupId);
                long n = stats.trials.get();
                
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
            statsMap.get(bestGroup).trials.incrementAndGet();
            totalTrials.incrementAndGet(); // 更新总选择次数
        } else {
            // 如果所有组都未被选择（理论上不应该发生），选择第一个组
            if (!metadata.getGroups().isEmpty()) {
                bestGroup = metadata.getGroups().keySet().iterator().next();
                statsMap.get(bestGroup).trials.incrementAndGet();
                totalTrials.incrementAndGet();
                log.warn("UCB算法未找到最优组，使用第一个组: experimentId={}", experimentId);
            }
        }
        
        log.debug("UCB选择组: experimentId={}, visitorId={}, selectedGroup={}, ucb={}", 
                experimentId, visitorId, bestGroup, maxUCB);
        
        return bestGroup;
    }
    
    @Override
    public void updateReward(String experimentId, String groupId, boolean success) {
        // 更新Beta分布参数（用于Thompson Sampling）
        Map<String, BetaParams> betaParams = betaParamsCache.get(experimentId);
        if (betaParams != null) {
            BetaParams params = betaParams.computeIfAbsent(groupId, k -> new BetaParams());
            if (success) {
                params.alpha.incrementAndGet();
            } else {
                params.beta.incrementAndGet();
            }
        }
        
        // 更新UCB统计信息
        Map<String, UCBStats> statsMap = ucbStatsCache.get(experimentId);
        if (statsMap != null) {
            UCBStats stats = statsMap.computeIfAbsent(groupId, k -> new UCBStats());
            stats.updateReward(success);
        }
        
        log.debug("更新奖励: experimentId={}, groupId={}, success={}", experimentId, groupId, success);
    }
    
    @Override
    public Map<String, Integer> getBetaParameters(String experimentId, String groupId) {
        Map<String, BetaParams> betaParams = betaParamsCache.get(experimentId);
        if (betaParams == null) {
            return null;
        }
        
        BetaParams params = betaParams.get(groupId);
        if (params == null) {
            return null;
        }
        
        Map<String, Integer> result = new HashMap<>();
        result.put("alpha", params.alpha.get());
        result.put("beta", params.beta.get());
        return result;
    }
    
    @Override
    public Map<String, Object> getGroupStatistics(String experimentId, String groupId) {
        Map<String, UCBStats> statsMap = ucbStatsCache.get(experimentId);
        if (statsMap == null) {
            return null;
        }
        
        UCBStats stats = statsMap.get(groupId);
        if (stats == null) {
            return null;
        }
        
        Map<String, Object> result = new HashMap<>();
        result.put("trials", stats.trials.get());
        result.put("successes", stats.successes.get());
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
}
