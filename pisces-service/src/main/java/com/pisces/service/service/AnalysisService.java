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
}

