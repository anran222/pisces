package com.pisces.service.service.impl;

import com.pisces.common.model.ExperimentMetadata;
import com.pisces.common.model.MetricDefinition;
import com.pisces.service.service.BayesianAnalysisService;
import com.pisces.service.service.ConfigService;
import com.pisces.service.service.DataService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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
        ExperimentMetadata metadata = configService.getExperimentConfig(experimentId);
        MetricDefinition primaryMetricDefinition = resolveRateMetricForBayesian(metadata);
        String metricKey = primaryMetricDefinition != null ? primaryMetricDefinition.getKey() : "conversion_rate";

        // 先检查缓存
        String cacheKey = experimentId + ":" + (metadata != null ? metadata.getConfigVersion() : 0L)
                + ":" + metricKey + ":" + variantGroupId + ":" + baselineGroupId;
        CachedWinRate cached = winRateCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return cached.winRate;
        }
        
        long variantDenominator = resolveMetricDenominator(primaryMetricDefinition, experimentId, variantGroupId);
        long variantNumerator = resolveMetricNumerator(primaryMetricDefinition, experimentId, variantGroupId);
        long baselineDenominator = resolveMetricDenominator(primaryMetricDefinition, experimentId, baselineGroupId);
        long baselineNumerator = resolveMetricNumerator(primaryMetricDefinition, experimentId, baselineGroupId);
        
        // 使用Beta-Binomial共轭先验，先验分布：Beta(1, 1) - 均匀先验
        // 不再限制最大值：sampleFromGamma 内部对大 shape 值使用正态近似，性能可控
        int variantAlpha = (int) Math.max(variantNumerator + 1, 1);
        int variantBeta = (int) Math.max(variantDenominator - variantNumerator + 1, 1);
        int baselineAlpha = (int) Math.max(baselineNumerator + 1, 1);
        int baselineBeta = (int) Math.max(baselineDenominator - baselineNumerator + 1, 1);
        
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
        
        String baselineGroupId = resolveBaselineGroupId(metadata);
        result.put("baselineGroup", baselineGroupId);
        
        // 计算各变体相对于基准的胜率
        Map<String, Double> winRates = new LinkedHashMap<>();
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

    private String resolveBaselineGroupId(ExperimentMetadata metadata) {
        if (metadata == null || metadata.getGroups() == null || metadata.getGroups().isEmpty()) {
            return null;
        }
        if (metadata.getTraffic() != null && metadata.getTraffic().getAllocation() != null) {
            for (com.pisces.common.model.TrafficConfig.GroupAllocation allocation : metadata.getTraffic().getAllocation()) {
                if (allocation != null && StringUtils.hasText(allocation.getGroup())
                        && metadata.getGroups().containsKey(allocation.getGroup())) {
                    return allocation.getGroup();
                }
            }
        }
        return metadata.getGroups().keySet().stream().sorted().findFirst().orElse(null);
    }

    private MetricDefinition resolveRateMetricForBayesian(ExperimentMetadata metadata) {
        List<MetricDefinition> metricDefinitions = resolveMetricDefinitions(metadata);
        for (MetricDefinition metricDefinition : metricDefinitions) {
            if (Boolean.TRUE.equals(metricDefinition.getPrimaryMetric())
                    && metricDefinition.getAggregationType() == MetricDefinition.AggregationType.RATE) {
                return metricDefinition;
            }
        }
        for (MetricDefinition metricDefinition : metricDefinitions) {
            if (metricDefinition.getAggregationType() == MetricDefinition.AggregationType.RATE) {
                return metricDefinition;
            }
        }

        MetricDefinition conversionRateMetric = new MetricDefinition();
        conversionRateMetric.setKey("conversion_rate");
        conversionRateMetric.setAggregationType(MetricDefinition.AggregationType.RATE);
        conversionRateMetric.setNumeratorEventType("CONVERT");
        conversionRateMetric.setDenominatorType(MetricDefinition.DenominatorType.EVENT_COUNT);
        conversionRateMetric.setDenominatorEventType("VIEW");
        return conversionRateMetric;
    }

    private List<MetricDefinition> resolveMetricDefinitions(ExperimentMetadata metadata) {
        if (metadata != null && metadata.getMetricDefinitions() != null && !metadata.getMetricDefinitions().isEmpty()) {
            return metadata.getMetricDefinitions();
        }

        MetricDefinition conversionRateMetric = new MetricDefinition();
        conversionRateMetric.setKey("conversion_rate");
        conversionRateMetric.setAggregationType(MetricDefinition.AggregationType.RATE);
        conversionRateMetric.setNumeratorEventType("CONVERT");
        conversionRateMetric.setDenominatorType(MetricDefinition.DenominatorType.EVENT_COUNT);
        conversionRateMetric.setDenominatorEventType("VIEW");
        return List.of(conversionRateMetric);
    }

    private long resolveMetricNumerator(MetricDefinition metricDefinition, String experimentId, String groupId) {
        if (metricDefinition == null || !StringUtils.hasText(metricDefinition.getNumeratorEventType())) {
            return 0L;
        }
        return dataService.getEventCount(experimentId, groupId, metricDefinition.getNumeratorEventType().toUpperCase());
    }

    private long resolveMetricDenominator(MetricDefinition metricDefinition, String experimentId, String groupId) {
        if (metricDefinition == null || metricDefinition.getDenominatorType() == null) {
            return 0L;
        }
        return switch (metricDefinition.getDenominatorType()) {
            case VISITOR_COUNT -> dataService.getVisitorCount(experimentId, groupId);
            case ASSIGNMENT_COUNT -> dataService.getAssignmentCount(experimentId, groupId);
            case EXPOSURE_COUNT -> dataService.getExposureCount(experimentId, groupId);
            case EVENT_COUNT -> resolveEventDenominator(metricDefinition.getDenominatorEventType(), experimentId, groupId);
        };
    }

    private long resolveEventDenominator(String denominatorEventType, String experimentId, String groupId) {
        if (!StringUtils.hasText(denominatorEventType)) {
            return 0L;
        }
        return dataService.getEventCount(experimentId, groupId, denominatorEventType.toUpperCase());
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
