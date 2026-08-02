package com.pisces.common.request;

import com.pisces.common.model.GroupConfigFieldDefinition;
import com.pisces.common.model.EventDefinition;
import com.pisces.common.model.MetricDefinition;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 创建实验请求
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ExperimentCreateRequest extends BaseRequest {
    
    @NotBlank(message = "实验名称不能为空")
    private String name;
    
    private String description;

    /**
     * 所属应用ID。非管理员 API Key 会强制使用 Key 绑定的 appId。
     */
    private String appId;

    /**
     * 归属人。未传时使用 API Key owner 或 system。
     */
    private String owner;

    /**
     * 所属流量分层 ID。互斥层内同一应用只允许一个实验运行。
     */
    private String layerId;
    
    @NotNull(message = "开始时间不能为空")
    private LocalDateTime startTime;
    
    @NotNull(message = "结束时间不能为空")
    private LocalDateTime endTime;
    
    /**
     * 实验组配置
     */
    private List<GroupConfig> groups;
    
    /**
     * 流量配置
     */
    private TrafficConfigRequest traffic;
    
    /**
     * 白名单用户ID列表
     */
    private List<String> whitelist;
    
    /**
     * 黑名单用户ID列表
     */
    private List<String> blacklist;

    /**
     * 事件定义列表
     */
    private List<EventDefinition> eventDefinitions;

    /**
     * 指标定义列表
     */
    private List<MetricDefinition> metricDefinitions;

    /**
     * 实验组配置字段定义
     */
    private List<GroupConfigFieldDefinition> groupConfigSchema;
    
    @Data
    public static class GroupConfig {
        @NotBlank(message = "实验组ID不能为空")
        private String id;
        
        @NotBlank(message = "实验组名称不能为空")
        private String name;
        
        @NotNull(message = "流量比例不能为空")
        private Double trafficRatio;
        
        /**
         * 实验组配置参数
         */
        private Map<String, Object> config;
    }
    
    @Data
    public static class TrafficConfigRequest {
        @NotNull(message = "总流量不能为空")
        private Double totalTraffic;
        
        @NotNull(message = "流量分配不能为空")
        private List<GroupAllocationRequest> allocation;
        
        @NotNull(message = "分配策略不能为空")
        private String strategy; // RANDOM, HASH, RULE, THOMPSON_SAMPLING, UCB
        
        private String hashKey;

        private List<TrafficRuleRequest> rules;

        private String ruleFallbackStrategy;
    }
    
    @Data
    public static class GroupAllocationRequest {
        @NotBlank(message = "实验组ID不能为空")
        private String group;
        
        @NotNull(message = "流量比例不能为空")
        private Double ratio;
    }

    @Data
    public static class TrafficRuleRequest {
        private String name;
        private Integer priority;
        private String group;
        private List<RuleConditionRequest> conditions;
    }

    @Data
    public static class RuleConditionRequest {
        private String field;
        private String operator;
        private String value;
        private List<String> values;
    }
}
