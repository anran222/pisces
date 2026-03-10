package com.pisces.service.service;

/**
 * 实验数据生成服务接口
 * 用于生成完整的实验流程数据，包括创建实验、分配访客、上报事件等
 */
public interface ExperimentDataGeneratorService {
    
    /**
     * 生成完整的实验流程数据
     * 
     * @param experimentName 实验名称
     * @param visitorCount 访客数量（每个实验组）
     * @param daysAgo 实验开始时间（几天前）
     * @return 生成的实验ID
     */
    String generateCompleteExperimentData(String experimentName, int visitorCount, int daysAgo);
    
    /**
     * 快速生成默认实验数据（使用默认参数）
     * 
     * @return 生成的实验ID
     */
    String generateDefaultExperimentData();
    
    /**
     * 快速生成实验数据（使用推荐参数）
     * 
     * @return 生成的实验ID
     */
    String generateQuickExperimentData();

    /**
     * 为已有实验补充演示数据。
     *
     * @param experimentId 实验ID
     * @param visitorCountPerGroup 每个实验组补充的访客数
     * @param daysSpan 数据覆盖的天数
     */
    void generateDataForExistingExperiment(String experimentId, int visitorCountPerGroup, int daysSpan);
}
