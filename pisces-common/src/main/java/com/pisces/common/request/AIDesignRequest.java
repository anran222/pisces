package com.pisces.common.request;

import com.pisces.common.model.GroupConfigFieldDefinition;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * AI实验设计请求
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/3/20 13:34
 */
@Data
public class AIDesignRequest {

    /**
     * 业务场景
     */
    private String businessScenario;

    /**
     * 目标指标
     */
    private String targetMetric;

    /**
     * 约束条件
     */
    private List<String> constraints;

    /**
     * 设计上下文
     */
    private DesignContext designContext;

    /**
     * 当前基线配置
     */
    private Map<String, Object> baselineConfig;

    /**
     * 已有配置字段定义
     */
    private List<GroupConfigFieldDefinition> existingSchema;

    /**
     * 设计偏好
     */
    private DesignPreferences designPreferences;

    @Data
    public static class DesignContext {

        /**
         * 建议重点关注的配置字段
         */
        private List<String> schemaKeys;

        /**
         * 默认实验组骨架
         */
        private List<String> draftGroupIds;

        /**
         * 默认流量策略
         */
        private String trafficStrategy;

        /**
         * 约束优先级
         */
        private List<String> prioritizedConstraints;
    }

    @Data
    public static class DesignPreferences {

        /**
         * 期望实验组数量
         */
        private Integer expectedGroupCount;

        /**
         * 偏好的流量策略
         */
        private String preferredTrafficStrategy;

        /**
         * 禁止使用的字段
         */
        private List<String> disabledSchemaKeys;
    }
}
