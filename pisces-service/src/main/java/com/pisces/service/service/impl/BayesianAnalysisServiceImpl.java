package com.pisces.service.service.impl;

import com.pisces.common.model.ExperimentMetadata;
import com.pisces.service.service.BayesianAnalysisService;
import com.pisces.service.service.ConfigService;
import com.pisces.service.service.DataService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 贝叶斯统计分析服务实现
 * 基于贝叶斯统计方法，实时计算变体击败基准的概率，支持实验提前终止
 */
@Slf4j
@Service
public class BayesianAnalysisServiceImpl implements BayesianAnalysisService {
    
    @Autowired
    private ConfigService configService;
    
    @Autowired
    private DataService dataService;
    
    // 缓存胜率计算结果，避免重复计算（缓存10秒）
    private final java.util.concurrent.ConcurrentHashMap<String, CachedWinRate> winRateCache = 
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 10000; // 10秒缓存
    
    private static class CachedWinRate {
        double winRate;
        long timestamp;
        
        CachedWinRate(double winRate) {
            this.winRate = winRate;
            this.timestamp = System.currentTimeMillis();
        }
        
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL_MS;
        }
    }
    
    @Override
    public double calculateWinRate(String experimentId, String variantGroupId, String baselineGroupId) {
        // 先检查缓存
        String cacheKey = experimentId + ":" + variantGroupId + ":" + baselineGroupId;
        CachedWinRate cached = winRateCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return cached.winRate;
        }
        
        // 获取两个组的统计数据
        long variantViews = dataService.getEventCount(experimentId, variantGroupId, "VIEW");
        long variantConverts = dataService.getEventCount(experimentId, variantGroupId, "CONVERT");
        long baselineViews = dataService.getEventCount(experimentId, baselineGroupId, "VIEW");
        long baselineConverts = dataService.getEventCount(experimentId, baselineGroupId, "CONVERT");
        
        // 使用Beta-Binomial共轭先验，先验分布：Beta(1, 1) - 均匀先验
        // 不再限制最大值：sampleFromGamma 内部对大 shape 值使用正态近似，性能可控
        int variantAlpha = (int) Math.max(variantConverts + 1, 1);
        int variantBeta = (int) Math.max(variantViews - variantConverts + 1, 1);
        int baselineAlpha = (int) Math.max(baselineConverts + 1, 1);
        int baselineBeta = (int) Math.max(baselineViews - baselineConverts + 1, 1);
        
        // 计算胜率：P(variant > baseline)
        // 使用蒙特卡洛方法近似计算
        double winRate = calculateWinRateMonteCarlo(variantAlpha, variantBeta, baselineAlpha, baselineBeta);
        
        // 缓存结果
        winRateCache.put(cacheKey, new CachedWinRate(winRate));
        
        log.info("计算胜率: experimentId={}, variant={}, baseline={}, winRate={}", 
                experimentId, variantGroupId, baselineGroupId, String.format("%.4f", winRate));
        
        return winRate;
    }
    
    @Override
    public Map<String, Object> getBayesianAnalysis(String experimentId) {
        ExperimentMetadata metadata = configService.getExperimentConfig(experimentId);
        if (metadata == null || metadata.getGroups() == null || metadata.getGroups().isEmpty()) {
            // 返回空结果而不是null
            Map<String, Object> emptyResult = new HashMap<>();
            emptyResult.put("baselineGroup", null);
            emptyResult.put("winRates", new HashMap<>());
            emptyResult.put("message", "实验不存在或没有实验组");
            return emptyResult;
        }
        
        Map<String, Object> result = new HashMap<>();
        
        // 获取第一个组作为基准
        String baselineGroupId = metadata.getGroups().keySet().iterator().next();
        result.put("baselineGroup", baselineGroupId);
        
        // 计算各变体相对于基准的胜率
        Map<String, Double> winRates = new HashMap<>();
        for (String groupId : metadata.getGroups().keySet()) {
            if (!groupId.equals(baselineGroupId)) {
                double winRate = calculateWinRate(experimentId, groupId, baselineGroupId);
                winRates.put(groupId, winRate);
            }
        }
        result.put("winRates", winRates);
        
        // 使用已经计算的胜率，避免重复计算
        Map<String, Object> earlyStopInfo = new HashMap<>();
        for (Map.Entry<String, Double> entry : winRates.entrySet()) {
            Map<String, Object> stopInfo = createEarlyStopInfo(entry.getValue(), 0.95);
            earlyStopInfo.put(entry.getKey(), stopInfo);
        }
        result.put("earlyStopInfo", earlyStopInfo);
        
        return result;
    }
    
    /**
     * 根据已计算的胜率创建提前终止信息（避免重复计算）
     */
    private Map<String, Object> createEarlyStopInfo(double winRate, double winRateThreshold) {
        Map<String, Object> result = new HashMap<>();
        result.put("winRate", winRate);
        result.put("threshold", winRateThreshold);
        
        boolean canStop = false;
        String reason = "";
        
        if (winRate >= winRateThreshold) {
            canStop = true;
            reason = "正向显著：变体优于基准的概率达到" + String.format("%.1f", winRate * 100) + "%，可以提前终止实验并全量上线";
        } else if (winRate <= (1 - winRateThreshold)) {
            canStop = true;
            reason = "负向显著：变体优于基准的概率仅为" + String.format("%.1f", winRate * 100) + "%，可以提前终止实验并放弃该变体";
        } else {
            reason = "继续实验：变体优于基准的概率为" + String.format("%.1f", winRate * 100) + "%，需要收集更多数据";
        }
        
        result.put("canStop", canStop);
        result.put("reason", reason);
        
        return result;
    }
    
    @Override
    public Map<String, Object> shouldEarlyStop(String experimentId, String variantGroupId, 
                                              String baselineGroupId, double winRateThreshold) {
        double winRate = calculateWinRate(experimentId, variantGroupId, baselineGroupId);
        return createEarlyStopInfo(winRate, winRateThreshold);
    }
    
    /**
     * 使用蒙特卡洛方法计算胜率
     * P(variant > baseline) = ∫∫ I(v > b) * Beta(v|α_v, β_v) * Beta(b|α_b, β_b) dv db
     */
    private double calculateWinRateMonteCarlo(int variantAlpha, int variantBeta, 
                                              int baselineAlpha, int baselineBeta) {
        int numSamples = 10000; // 10000次采样兼顾精度与性能
        int wins = 0;
        
        java.util.Random random = new java.util.Random();
        
        for (int i = 0; i < numSamples; i++) {
            // 从变体的Beta分布中采样
            double variantSample = sampleFromBeta(variantAlpha, variantBeta, random);
            // 从基准的Beta分布中采样
            double baselineSample = sampleFromBeta(baselineAlpha, baselineBeta, random);
            
            if (variantSample > baselineSample) {
                wins++;
            }
        }
        
        return (double) wins / numSamples;
    }
    
    /**
     * 从Beta分布中采样
     * 使用Gamma分布的近似方法
     */
    private double sampleFromBeta(int alpha, int beta, java.util.Random random) {
        // 使用Gamma分布的近似
        double gammaAlpha = sampleFromGamma(alpha, random);
        double gammaBeta = sampleFromGamma(beta, random);
        double sum = gammaAlpha + gammaBeta;
        return sum > 0 ? gammaAlpha / sum : 0.5;
    }
    
    /**
     * 从Gamma分布中采样（优化版本）
     */
    private double sampleFromGamma(int shape, java.util.Random random) {
        if (shape <= 0) {
            return 0.0;
        }
        
        // 对于大的shape值，使用正态分布近似（中心极限定理）
        if (shape > 100) {
            // Gamma(shape, 1) 近似为 N(shape, sqrt(shape))
            double mean = shape;
            double std = Math.sqrt(shape);
            return Math.max(0, mean + std * random.nextGaussian());
        }
        
        // 对于小的shape值，使用精确方法
        double sum = 0.0;
        for (int i = 0; i < shape; i++) {
            sum += -Math.log(random.nextDouble());
        }
        return sum;
    }
}
