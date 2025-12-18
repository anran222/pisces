package com.pisces.service.service;

import com.pisces.common.model.ExperimentMetadata;
import com.pisces.common.model.Statistics;

import java.util.Map;

/**
 * 数据分析服务接口
 */
public interface AnalysisService {
    
    /**
     * 获取实验统计数据
     */
    Statistics getStatistics(String experimentId);
    
    /**
     * 对比实验组
     */
    Map<String, Object> compareGroups(String experimentId);
    
    /**
     * 获取贝叶斯分析结果（实时胜率计算）
     */
    Map<String, Object> getBayesianAnalysis(String experimentId);
    
    /**
     * 判断是否可以提前终止实验
     */
    Map<String, Object> shouldEarlyStop(String experimentId, String variantGroupId, 
                                        String baselineGroupId, Double winRateThreshold);
    
    /**
     * 执行因果推断分析（DID、PSM、因果森林）
     */
    Map<String, Object> causalInference(String experimentId, String treatmentGroupId,
                                        String controlGroupId, String method,
                                        Map<String, Object> params);
    
    /**
     * 分析异质处理效应（HTE）
     */
    Map<String, Object> analyzeHTE(String experimentId, String treatmentGroupId,
                                   String controlGroupId, java.util.List<String> userFeatures);
    
    /**
     * 识别敏感用户群体
     */
    Map<String, Object> identifySensitiveGroups(String experimentId, String treatmentGroupId,
                                                 String controlGroupId, java.util.List<String> userFeatures);
}

