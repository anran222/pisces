package com.pisces.service.service;

import java.util.List;
import java.util.Map;

/**
 * 因果推断分析服务接口
 * AI赋能：通过构建无偏因果模型，精准计算策略的真实处理效应，剥离混淆变量影响
 */
public interface CausalInferenceService {
    
    /**
     * 双重差分法（DID）分析
     * 适用于存在时间趋势混淆的场景（如节假日、平台促销）
     * @param experimentId 实验ID
     * @param treatmentGroupId 处理组ID
     * @param controlGroupId 对照组ID
     * @param beforePeriodStart 处理前时间段开始
     * @param beforePeriodEnd 处理前时间段结束
     * @param afterPeriodStart 处理后时间段开始
     * @param afterPeriodEnd 处理后时间段结束
     * @return DID估计量和分析结果
     */
    Map<String, Object> analyzeByDID(String experimentId, String treatmentGroupId, 
                                     String controlGroupId,
                                     String beforePeriodStart, String beforePeriodEnd,
                                     String afterPeriodStart, String afterPeriodEnd);
    
    /**
     * 倾向得分匹配（PSM）分析
     * 适用于存在观测混淆变量的场景（如用户画像差异、渠道质量差异）
     * @param experimentId 实验ID
     * @param treatmentGroupId 处理组ID
     * @param controlGroupId 对照组ID
     * @param userFeatures 用户特征列表（用于计算倾向得分）
     * @return PSM估计量和匹配结果
     */
    Map<String, Object> analyzeByPSM(String experimentId, String treatmentGroupId,
                                      String controlGroupId, List<String> userFeatures);
    
    /**
     * 因果森林（Causal Forest）分析
     * 可同时估计平均处理效应（ATE）和条件平均处理效应（CATE），识别敏感用户群体
     * @param experimentId 实验ID
     * @param treatmentGroupId 处理组ID
     * @param controlGroupId 对照组ID
     * @param userFeatures 用户特征列表
     * @return ATE、CATE和敏感群体划分结果
     */
    Map<String, Object> analyzeByCausalForest(String experimentId, String treatmentGroupId,
                                               String controlGroupId, List<String> userFeatures);
}
