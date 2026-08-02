package com.pisces.common.response;

import com.pisces.common.model.EventDefinition;
import com.pisces.common.model.GroupConfigFieldDefinition;
import com.pisces.common.model.MetricDefinition;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 运行时实验配置响应
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/1 11:49
 */
@Data
public class RuntimeExperimentConfigResponse {

    /**
     * 实验ID
     */
    private String id;

    /**
     * 实验名称
     */
    private String name;

    /**
     * 实验描述
     */
    private String description;

    /**
     * 实验状态
     */
    private String status;

    /**
     * 配置版本
     */
    private Long configVersion;

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
     * 实验组配置
     */
    private Map<String, GroupConfigResponse> groups;

    /**
     * 流量配置
     */
    private TrafficConfigResponse traffic;

    /**
     * 实验组配置响应
     *
     * @author anran.xiang@atrenew.com
     * @date 2026/7/1 11:49
     */
    @Data
    public static class GroupConfigResponse {

        /**
         * 实验组ID
         */
        private String id;

        /**
         * 实验组名称
         */
        private String name;

        /**
         * 流量比例
         */
        private Double trafficRatio;

        /**
         * 业务配置
         */
        private Map<String, Object> config;
    }

    /**
     * 流量配置响应
     *
     * @author anran.xiang@atrenew.com
     * @date 2026/7/1 11:49
     */
    @Data
    public static class TrafficConfigResponse {

        /**
         * 总流量比例
         */
        private Double totalTraffic;

        /**
         * 实验组分配比例
         */
        private List<GroupAllocationResponse> allocation;

        /**
         * 分流策略
         */
        private String strategy;

        /**
         * 哈希字段
         */
        private String hashKey;
    }

    /**
     * 实验组分配响应
     *
     * @author anran.xiang@atrenew.com
     * @date 2026/7/1 11:49
     */
    @Data
    public static class GroupAllocationResponse {

        /**
         * 实验组ID
         */
        private String group;

        /**
         * 分配比例
         */
        private Double ratio;
    }
}
