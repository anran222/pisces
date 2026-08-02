package com.pisces.common.response;

import com.pisces.common.model.Experiment;
import com.pisces.common.model.ExperimentMetadata;
import com.pisces.common.model.EventDefinition;
import com.pisces.common.model.GroupConfigFieldDefinition;
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
     * 配置版本
     */
    private Long configVersion;

    /**
     * 所属流量分层 ID
     */
    private String layerId;

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
     * 事件定义
     */
    private List<EventDefinition> eventDefinitions;

    /**
     * 指标定义
     */
    private List<MetricDefinition> metricDefinitions;

    /**
     * 实验组配置字段定义
     */
    private List<GroupConfigFieldDefinition> groupConfigSchema;

    /**
     * 当前人工确认结论状态
     */
    private ExperimentMetadata.ConclusionStatus conclusionStatus;

    /**
     * 结论状态更新时间
     */
    private LocalDateTime conclusionUpdatedAt;

    /**
     * 当前人工结论绑定的配置版本
     */
    private Long conclusionConfigVersion;

    /**
     * 当前人工结论绑定的报告快照版本
     */
    private Integer conclusionReportSnapshotVersion;

    /**
     * 当前人工结论操作人
     */
    private String conclusionOperator;

    /**
     * 当前人工结论备注
     */
    private String conclusionComment;

    /**
     * 系统建议结论状态（由报告快照推导）
     */
    private ExperimentMetadata.ConclusionStatus suggestedConclusionStatus;

    /**
     * 系统建议结论状态更新时间（由报告快照推导）
     */
    private LocalDateTime suggestedConclusionUpdatedAt;

    /**
     * 实验启动审批状态
     */
    private ExperimentMetadata.ApprovalStatus approvalStatus;

    /**
     * 审批操作人
     */
    private String approvalOperator;

    /**
     * 审批备注
     */
    private String approvalComment;

    /**
     * 审批状态更新时间
     */
    private LocalDateTime approvalUpdatedAt;
    
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
