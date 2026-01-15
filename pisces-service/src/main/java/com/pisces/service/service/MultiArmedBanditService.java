package com.pisces.service.service;

import java.util.Map;

/**
 * 多臂老虎机算法服务接口
 * 用于实现动态流量分配，在探索与利用之间找到最优平衡
 */
public interface MultiArmedBanditService {
    
    /**
     * 选择实验组（Thompson Sampling算法）
     * @param experimentId 实验ID
     * @param visitorId 访客唯一标识（可以是设备ID、会话ID等）
     * @return 选择的实验组ID
     */
    String selectGroupByThompsonSampling(String experimentId, String visitorId);
    
    /**
     * 选择实验组（UCB算法）
     * @param experimentId 实验ID
     * @param visitorId 访客唯一标识（可以是设备ID、会话ID等）
     * @return 选择的实验组ID
     */
    String selectGroupByUCB(String experimentId, String visitorId);
    
    /**
     * 更新变体的奖励数据（用于更新Beta分布参数）
     * @param experimentId 实验ID
     * @param groupId 实验组ID
     * @param success 是否成功（true表示成功，false表示失败）
     */
    void updateReward(String experimentId, String groupId, boolean success);
    
    /**
     * 获取变体的Beta分布参数（用于Thompson Sampling）
     * @param experimentId 实验ID
     * @param groupId 实验组ID
     * @return Map包含alpha和beta参数
     */
    Map<String, Integer> getBetaParameters(String experimentId, String groupId);
    
    /**
     * 获取变体的统计信息（用于UCB）
     * @param experimentId 实验ID
     * @param groupId 实验组ID
     * @return Map包含平均奖励、选择次数等信息
     */
    Map<String, Object> getGroupStatistics(String experimentId, String groupId);
    
    /**
     * 获取实验的所有组的MAB分配概率
     * 根据当前的Beta参数计算每个组被选中的概率
     * @param experimentId 实验ID
     * @return 各组的分配概率（组ID -> 概率）
     */
    Map<String, Double> getAllocationProbabilities(String experimentId);
    
    /**
     * 获取实验的MAB算法综合统计信息
     * @param experimentId 实验ID
     * @return 包含所有组的统计信息、分配概率、收敛状态等
     */
    Map<String, Object> getMABSummary(String experimentId);
    
    /**
     * 重置实验的MAB统计数据
     * @param experimentId 实验ID
     */
    void resetMABData(String experimentId);
}
