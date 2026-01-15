package com.pisces.service.service;

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
     * 传统统计显著性检验（t检验、卡方检验、置信区间）
     * @param experimentId 实验ID
     * @param variantGroupId 变体组ID
     * @param baselineGroupId 基准组ID
     * @param confidenceLevel 置信水平（如0.95表示95%置信度）
     * @return 包含t检验、p值、置信区间、提升率等统计结果
     */
    Map<String, Object> statisticalSignificanceTest(String experimentId, String variantGroupId,
                                                     String baselineGroupId, Double confidenceLevel);
    
    /**
     * 计算所需样本量（功效分析）
     * @param baselineRate 基准转化率
     * @param minimumDetectableEffect 最小可检测效应（如0.05表示5%的提升）
     * @param power 统计功效（如0.8表示80%）
     * @param significance 显著性水平（如0.05表示5%）
     * @return 每组所需样本量
     */
    Map<String, Object> calculateSampleSize(Double baselineRate, Double minimumDetectableEffect,
                                            Double power, Double significance);
    
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
    
    /**
     * 导出实验报告
     * @param experimentId 实验ID
     * @return 完整的实验报告，包含统计数据、分析结果、结论建议等
     */
    Map<String, Object> exportExperimentReport(String experimentId);
    
    /**
     * 获取实验时间线数据
     * 用于展示实验指标随时间变化的趋势
     * @param experimentId 实验ID
     * @param metricType 指标类型（CONVERSION_RATE, CLICK_RATE, VISITOR_COUNT等）
     * @param granularity 时间粒度（HOUR, DAY, WEEK）
     * @return 时间线数据
     */
    Map<String, Object> getExperimentTimeline(String experimentId, String metricType, String granularity);
}

