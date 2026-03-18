package com.pisces.common.response;

import com.pisces.common.model.Experiment;
import com.pisces.common.model.ExperimentMetadata;
import com.pisces.common.model.MetricDefinition;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 实验响应
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class ExperimentResponse extends Experiment {
    
    /**
     * 实验组列表
     */
    private Map<String, GroupResponse> groups;
    
    /**
     * 流量配置
     */
    private TrafficConfigResponse traffic;
    
    /**
     * 白名单
     */
    private List<String> whitelist;
    
    /**
     * 黑名单
     */
    private List<String> blacklist;

    /**
     * 指标定义
     */
    private List<MetricDefinition> metricDefinitions;

    /**
     * 当前人工确认结论状态
     */
    private ExperimentMetadata.ConclusionStatus conclusionStatus;

    /**
     * 结论状态更新时间
     */
    private LocalDateTime conclusionUpdatedAt;

    /**
     * 系统建议结论状态（由报告快照推导）
     */
    private ExperimentMetadata.ConclusionStatus suggestedConclusionStatus;

    /**
     * 系统建议结论状态更新时间（由报告快照推导）
     */
    private LocalDateTime suggestedConclusionUpdatedAt;
    
    @Data
    public static class GroupResponse {
        private String id;
        private String name;
        private Double trafficRatio;
        private Map<String, Object> config;
    }
    
    @Data
    public static class TrafficConfigResponse {
        private Double totalTraffic;
        private List<GroupAllocationResponse> allocation;
        private String strategy;
        private String hashKey;
        private List<TrafficRuleResponse> rules;
        private String ruleFallbackStrategy;
    }
    
    @Data
    public static class GroupAllocationResponse {
        private String group;
        private Double ratio;
    }

    @Data
    public static class TrafficRuleResponse {
        private String name;
        private Integer priority;
        private String group;
        private List<RuleConditionResponse> conditions;
    }

    @Data
    public static class RuleConditionResponse {
        private String field;
        private String operator;
        private String value;
        private List<String> values;
    }
}
