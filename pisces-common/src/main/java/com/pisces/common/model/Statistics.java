package com.pisces.common.model;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 统计数据实体
 */
@Data
public class Statistics implements Serializable {
    private static final long serialVersionUID = 1L;
    
    /**
     * 实验ID
     */
    private String experimentId;
    
    /**
     * 实验名称
     */
    private String experimentName;
    
    /**
     * 实验状态
     */
    private String experimentStatus;
    
    /**
     * 统计开始时间
     */
    private LocalDateTime statisticsStartTime;
    
    /**
     * 统计结束时间
     */
    private LocalDateTime statisticsEndTime;
    
    /**
     * 各实验组的统计数据
     */
    private Map<String, GroupStatistics> groupStatistics;
    
    /**
     * 实验总览
     */
    private ExperimentSummary summary;

    /**
     * 数据质量校验
     */
    private DataQualityCheck dataQualityCheck;
    
    /**
     * 实验总览统计
     */
    @Data
    public static class ExperimentSummary implements Serializable {
        private static final long serialVersionUID = 1L;
        
        /**
         * 总访客数
         */
        private Long totalVisitors;
        
        /**
         * 总事件数
         */
        private Long totalEvents;

        /**
         * 总分流数
         */
        private Long totalAssignments;

        /**
         * 总曝光数
         */
        private Long totalExposures;
        
        /**
         * 总体转化率
         */
        private Double overallConversionRate;
        
        /**
         * 总体点击率
         */
        private Double overallClickRate;
        
        /**
         * 最佳表现组ID
         */
        private String bestPerformingGroup;

        /**
         * 主指标编码
         */
        private String primaryMetricKey;

        /**
         * 最佳表现组主指标值
         */
        private Double bestPrimaryMetricValue;
        
        /**
         * 最佳表现组转化率
         */
        private Double bestConversionRate;

        /**
         * 护栏指标异常列表
         */
        private List<String> breachedGuardrails;
    }

    /**
     * 数据质量校验结果
     */
    @Data
    public static class DataQualityCheck implements Serializable {
        private static final long serialVersionUID = 1L;

        /**
         * 当前分析是否可直接用于结论判断
         */
        private Boolean analysisReady;

        /**
         * 是否存在 SRM
         */
        private Boolean hasSrm;

        /**
         * SRM p 值
         */
        private Double srmPValue;

        /**
         * 每组建议样本量
         */
        private Long requiredSampleSizePerGroup;

        /**
         * 是否达到建议样本量
         */
        private Boolean sampleSizeReached;

        /**
         * 阻断问题
         */
        private List<String> blockingIssues;

        /**
         * 警告信息
         */
        private List<String> warnings;
    }
    
    /**
     * 实验组统计数据
     */
    @Data
    public static class GroupStatistics implements Serializable {
        private static final long serialVersionUID = 1L;
        
        /**
         * 实验组ID
         */
        private String groupId;
        
        /**
         * 实验组名称
         */
        private String groupName;
        
        /**
         * 访客总数（注意：字段名保持为userCount以兼容现有接口，但实际存储的是visitorCount）
         */
        private Long userCount;
        
        /**
         * 事件统计（事件类型 -> 数量）
         */
        private Map<String, Long> eventCounts;
        
        /**
         * 浏览数
         */
        private Long viewCount;
        
        /**
         * 点击数
         */
        private Long clickCount;
        
        /**
         * 转化数
         */
        private Long conversionCount;
        
        /**
         * 点击率（Click Through Rate）
         */
        private Double clickRate;
        
        /**
         * 转化率（Conversion Rate）
         */
        private Double conversionRate;
        
        /**
         * 相对于基准组的提升率
         */
        private Double liftRate;
        
        /**
         * 流量占比
         */
        private Double trafficRatio;
        
        /**
         * 是否为基准组
         */
        private Boolean isBaseline;

        /**
         * 指标值
         */
        private Map<String, Double> metricValues;
    }
}
