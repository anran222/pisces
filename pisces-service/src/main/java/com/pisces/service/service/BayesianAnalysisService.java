package com.pisces.service.service;

import java.util.Map;

/**
 * 贝叶斯统计分析服务接口
 * 用于实时计算变体击败基准的概率（胜率），实现实验提前终止
 */
public interface BayesianAnalysisService {
    
    /**
     * 计算变体击败基准的概率（胜率）
     * @param experimentId 实验ID
     * @param variantGroupId 变体组ID
     * @param baselineGroupId 基准组ID
     * @return 胜率（0.0-1.0），表示变体优于基准的概率
     */
    double calculateWinRate(String experimentId, String variantGroupId, String baselineGroupId);
    
    /**
     * 获取实验的贝叶斯分析结果
     * @param experimentId 实验ID
     * @return 包含各变体胜率、是否可提前终止等信息
     */
    Map<String, Object> getBayesianAnalysis(String experimentId);
    
    /**
     * 判断是否可以提前终止实验
     * @param experimentId 实验ID
     * @param variantGroupId 变体组ID
     * @param baselineGroupId 基准组ID
     * @param winRateThreshold 胜率阈值（默认0.95）
     * @return 是否可以提前终止，以及终止原因
     */
    Map<String, Object> shouldEarlyStop(String experimentId, String variantGroupId, 
                                        String baselineGroupId, double winRateThreshold);
}
