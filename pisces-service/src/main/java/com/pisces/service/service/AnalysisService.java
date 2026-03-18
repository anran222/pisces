package com.pisces.service.service;

import com.pisces.common.model.ExperimentReportSnapshot;
import com.pisces.common.model.Statistics;

import java.util.List;
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
     * 生成实验报告快照
     *
     * @param experimentId 实验ID
     * @param generatedBy 生成人
     * @return 报告快照
     */
    ExperimentReportSnapshot createReportSnapshot(String experimentId, String generatedBy);

    /**
     * 查询实验报告快照列表
     *
     * @param experimentId 实验ID
     * @return 报告快照列表
     */
    List<ExperimentReportSnapshot> listReportSnapshots(String experimentId);
    
    /**
     * 获取实验时间线数据
     * 用于展示实验指标随时间变化的趋势
     * @param experimentId 实验ID
     * @param metricType 指标类型（CONVERSION_RATE, CLICK_RATE, VISITOR_COUNT等）
     * @param granularity 时间粒度（HOUR, DAY, WEEK）
     * @return 时间线数据
     */
    Map<String, Object> getExperimentTimeline(String experimentId, String metricType, String granularity);
    
    /**
     * AI智能实验解读
     * 调用AI分析实验数据，生成专业的分析报告和建议
     * @param experimentId 实验ID
     * @return AI生成的分析结论和建议
     */
    Map<String, Object> getAIInsights(String experimentId);
    
    /**
     * AI实验设计建议
     * 根据业务场景和目标，生成最佳实验设计方案
     * @param businessScenario 业务场景描述
     * @param targetMetric 目标指标
     * @param constraints 约束条件
     * @return AI推荐的实验设计方案
     */
    Map<String, Object> getAIExperimentDesign(String businessScenario, String targetMetric, 
                                               java.util.List<String> constraints);
    
    /**
     * AI自动毕业决策
     * 根据实验数据判断是否可以全量发布最佳变体
     * @param experimentId 实验ID
     * @return 毕业决策结果，包含推荐变体、置信度、风险评估等
     */
    Map<String, Object> autoGraduateDecision(String experimentId);
    
    /**
     * AI预测实验完成时间
     * 根据当前数据趋势预测实验何时能达到统计显著性
     * @param experimentId 实验ID
     * @return 预测结果，包含预计完成时间、当前进度等
     */
    Map<String, Object> predictExperimentCompletion(String experimentId);

    /**
     * SRM 检测（Sample Ratio Mismatch）
     * 检测实验各组的实际流量比例是否与预设比例一致
     * 若存在 SRM，实验结论不可信
     *
     * @param experimentId 实验ID
     * @return 卡方统计量、p 值、是否存在 SRM 等
     */
    Map<String, Object> detectSRM(String experimentId);

    /**
     * 序贯检验（SPRT — Sequential Probability Ratio Test）
     * 适合实验期间持续监控，解决传统频繁查看导致的 Peeking Problem
     *
     * @param experimentId   实验ID
     * @param variantGroupId 变体组ID
     * @param baselineGroupId 基准组ID
     * @param mde            最小可检测效应（相对，如 0.05 表示 5%）
     * @param alpha          I 类错误率（如 0.05）
     * @param beta           II 类错误率（如 0.20，即 power=0.80）
     * @return 当前检验决策（ACCEPT_H1/ACCEPT_H0/CONTINUE）及说明
     */
    Map<String, Object> sequentialTest(String experimentId, String variantGroupId,
                                       String baselineGroupId, Double mde,
                                       Double alpha, Double beta);
}
