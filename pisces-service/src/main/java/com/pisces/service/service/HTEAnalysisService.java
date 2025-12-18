package com.pisces.service.service;

import java.util.List;
import java.util.Map;

/**
 * 异质处理效应（HTE）分析服务接口
 * AI赋能：识别对策略敏感的用户群体，从"群体平均"到"个体精准"，为个性化策略落地提供支撑
 */
public interface HTEAnalysisService {
    
    /**
     * 分析异质处理效应
     * 识别不同用户群体对策略的差异化反应
     * @param experimentId 实验ID
     * @param treatmentGroupId 处理组ID
     * @param controlGroupId 对照组ID
     * @param userFeatures 用户特征列表（用于分层分析）
     * @return HTE分析结果，包括各群体的处理效应
     */
    Map<String, Object> analyzeHTE(String experimentId, String treatmentGroupId,
                                   String controlGroupId, List<String> userFeatures);
    
    /**
     * 获取访客个体的处理效应估计（ITE）
     * @param experimentId 实验ID
     * @param visitorId 访客唯一标识（可以是设备ID、会话ID等）
     * @param treatmentGroupId 处理组ID
     * @param controlGroupId 对照组ID
     * @return 个体处理效应估计值
     */
    Map<String, Object> getIndividualTreatmentEffect(String experimentId, String visitorId,
                                                      String treatmentGroupId, String controlGroupId);
    
    /**
     * 识别敏感用户群体
     * 根据处理效应大小，将用户划分为高敏感、中敏感、低敏感群体
     * @param experimentId 实验ID
     * @param treatmentGroupId 处理组ID
     * @param controlGroupId 对照组ID
     * @param userFeatures 用户特征列表
     * @return 敏感群体划分结果
     */
    Map<String, Object> identifySensitiveGroups(String experimentId, String treatmentGroupId,
                                                 String controlGroupId, List<String> userFeatures);
}
