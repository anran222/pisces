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
    
    @Override
    public double calculateWinRate(String experimentId, String variantGroupId, String baselineGroupId) {
        // 获取两个组的统计数据
        long variantViews = dataService.getEventCount(experimentId, variantGroupId, "VIEW");
        long variantConverts = dataService.getEventCount(experimentId, variantGroupId, "CONVERT");
        long baselineViews = dataService.getEventCount(experimentId, baselineGroupId, "VIEW");
        long baselineConverts = dataService.getEventCount(experimentId, baselineGroupId, "CONVERT");
        
        // 使用Beta-Binomial共轭先验
        // 先验分布：Beta(1, 1) - 均匀先验
        int variantAlpha = (int) variantConverts + 1;
        int variantBeta = (int) (variantViews - variantConverts) + 1;
        int baselineAlpha = (int) baselineConverts + 1;
        int baselineBeta = (int) (baselineViews - baselineConverts) + 1;
        
        // 计算胜率：P(variant > baseline)
        // 使用蒙特卡洛方法近似计算
        double winRate = calculateWinRateMonteCarlo(variantAlpha, variantBeta, baselineAlpha, baselineBeta);
        
        log.debug("计算胜率: experimentId={}, variant={}, baseline={}, winRate={}", 
                experimentId, variantGroupId, baselineGroupId, winRate);
        
        return winRate;
    }
    
    @Override
    public Map<String, Object> getBayesianAnalysis(String experimentId) {
        ExperimentMetadata metadata = configService.getExperimentConfig(experimentId);
        if (metadata == null || metadata.getGroups() == null || metadata.getGroups().isEmpty()) {
            return null;
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
        
        // 判断是否可以提前终止
        Map<String, Object> earlyStopInfo = new HashMap<>();
        for (Map.Entry<String, Double> entry : winRates.entrySet()) {
            Map<String, Object> stopInfo = shouldEarlyStop(experimentId, entry.getKey(), 
                    baselineGroupId, 0.95);
            earlyStopInfo.put(entry.getKey(), stopInfo);
        }
        result.put("earlyStopInfo", earlyStopInfo);
        
        return result;
    }
    
    @Override
    public Map<String, Object> shouldEarlyStop(String experimentId, String variantGroupId, 
                                              String baselineGroupId, double winRateThreshold) {
        double winRate = calculateWinRate(experimentId, variantGroupId, baselineGroupId);
        
        Map<String, Object> result = new HashMap<>();
        result.put("winRate", winRate);
        result.put("threshold", winRateThreshold);
        
        boolean canStop = false;
        String reason = "";
        
        if (winRate >= winRateThreshold) {
            canStop = true;
            reason = "正向显著：变体优于基准的概率达到" + (winRate * 100) + "%，可以提前终止实验并全量上线";
        } else if (winRate <= (1 - winRateThreshold)) {
            canStop = true;
            reason = "负向显著：变体优于基准的概率仅为" + (winRate * 100) + "%，可以提前终止实验并放弃该变体";
        } else {
            reason = "继续实验：变体优于基准的概率为" + (winRate * 100) + "%，需要收集更多数据";
        }
        
        result.put("canStop", canStop);
        result.put("reason", reason);
        
        return result;
    }
    
    /**
     * 使用蒙特卡洛方法计算胜率
     * P(variant > baseline) = ∫∫ I(v > b) * Beta(v|α_v, β_v) * Beta(b|α_b, β_b) dv db
     */
    private double calculateWinRateMonteCarlo(int variantAlpha, int variantBeta, 
                                              int baselineAlpha, int baselineBeta) {
        int numSamples = 10000; // 蒙特卡洛采样次数
        int wins = 0;
        
        for (int i = 0; i < numSamples; i++) {
            // 从变体的Beta分布中采样
            double variantSample = sampleFromBeta(variantAlpha, variantBeta);
            // 从基准的Beta分布中采样
            double baselineSample = sampleFromBeta(baselineAlpha, baselineBeta);
            
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
    private double sampleFromBeta(int alpha, int beta) {
        // 简化实现：使用Gamma分布的近似
        double gammaAlpha = sampleFromGamma(alpha);
        double gammaBeta = sampleFromGamma(beta);
        double sum = gammaAlpha + gammaBeta;
        return sum > 0 ? gammaAlpha / sum : 0.5;
    }
    
    /**
     * 从Gamma分布中采样（简化实现）
     */
    private double sampleFromGamma(int shape) {
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
