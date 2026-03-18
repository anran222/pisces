package com.pisces.common.model;

import lombok.Data;

import java.io.Serializable;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 流量配置实体
 */
@Data
public class TrafficConfig implements Serializable {
    private static final long serialVersionUID = 1L;
    
    /**
     * 总流量比例（0.0-1.0）
     */
    private Double totalTraffic;
    
    /**
     * 流量分配列表
     */
    private List<GroupAllocation> allocation;
    
    /**
     * 分配策略：RANDOM-随机, HASH-哈希, RULE-规则, THOMPSON_SAMPLING-汤普森采样, UCB-置信区间上界
     */
    private TrafficStrategy strategy;
    
    /**
     * 哈希键（用于一致性哈希）
     */
    private String hashKey;

    /**
     * 规则分流列表
     */
    private List<TrafficRule> rules;

    /**
     * 规则未命中时的回退策略
     */
    private RuleFallbackStrategy ruleFallbackStrategy;
    
    /**
     * 流量分配项
     */
    @Data
    public static class GroupAllocation implements Serializable {
        private static final long serialVersionUID = 1L;
        
        /**
         * 实验组ID
         */
        private String group;
        
        /**
         * 流量比例
         */
        private Double ratio;
    }

    /**
     * 规则分流配置
     */
    @Data
    public static class TrafficRule implements Serializable {
        private static final long serialVersionUID = 1L;

        /**
         * 规则名称
         */
        private String name;

        /**
         * 优先级，值越小优先级越高
         */
        private Integer priority;

        /**
         * 命中后分配的实验组
         */
        private String group;

        /**
         * 命中条件
         */
        private List<RuleCondition> conditions;
    }

    /**
     * 规则条件
     */
    @Data
    public static class RuleCondition implements Serializable {
        private static final long serialVersionUID = 1L;

        /**
         * 条件字段
         */
        private String field;

        /**
         * 操作符
         */
        private RuleOperator operator;

        /**
         * 单值条件
         */
        private String value;

        /**
         * 多值条件
         */
        private List<String> values;
    }
    
    /**
     * 流量分配策略 (Traffic strategy)
     */
    public enum TrafficStrategy {
        RANDOM,              // 随机分配
        HASH,                // 哈希分配
        RULE,                // 规则分配
        THOMPSON_SAMPLING,   // 汤普森采样（多臂老虎机算法）
        UCB;                 // 置信区间上界（多臂老虎机算法）

        public static TrafficStrategy of(String code) {
            if (code == null) {
                return null;
            }
            return Arrays.stream(values())
                    .filter(item -> item.name().equalsIgnoreCase(code))
                    .findFirst()
                    .orElse(null);
        }

        public static TrafficStrategy ofOrThrow(String code) {
            TrafficStrategy strategy = of(code);
            if (strategy == null) {
                throw new IllegalArgumentException("不支持的流量分配策略: " + code);
            }
            return strategy;
        }
    }

    /**
     * 规则操作符 (Rule operator)
     */
    public enum RuleOperator {
        EQ,
        IN,
        CONTAINS,
        EXISTS;

        public static RuleOperator of(String code) {
            if (code == null) {
                return null;
            }
            return Arrays.stream(values())
                    .filter(item -> item.name().equalsIgnoreCase(code))
                    .findFirst()
                    .orElse(null);
        }

        public static RuleOperator ofOrThrow(String code) {
            RuleOperator operator = of(code);
            if (operator == null) {
                throw new IllegalArgumentException("不支持的规则操作符: " + code);
            }
            return operator;
        }
    }

    /**
     * 规则回退策略 (Rule fallback strategy)
     */
    public enum RuleFallbackStrategy {
        HASH,
        FIRST_ALLOCATION;

        public static RuleFallbackStrategy of(String code) {
            if (code == null) {
                return null;
            }
            String normalizedCode = code.trim().toUpperCase(Locale.ROOT);
            return Arrays.stream(values())
                    .filter(item -> item.name().equals(normalizedCode))
                    .findFirst()
                    .orElse(null);
        }

        public static RuleFallbackStrategy ofOrThrow(String code) {
            RuleFallbackStrategy strategy = of(code);
            if (strategy == null) {
                throw new IllegalArgumentException("不支持的规则回退策略: " + code);
            }
            return strategy;
        }
    }
}
