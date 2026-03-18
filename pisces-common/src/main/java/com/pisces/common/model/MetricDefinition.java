package com.pisces.common.model;

import lombok.Data;

import java.io.Serializable;

/**
 * 指标定义
 */
@Data
public class MetricDefinition implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 指标编码
     */
    private String key;

    /**
     * 指标名称
     */
    private String name;

    /**
     * 指标描述
     */
    private String description;

    /**
     * 聚合类型
     */
    private AggregationType aggregationType;

    /**
     * 分子事件类型
     */
    private String numeratorEventType;

    /**
     * 分母类型
     */
    private DenominatorType denominatorType;

    /**
     * 分母事件类型
     */
    private String denominatorEventType;

    /**
     * 是否主指标
     */
    private Boolean primaryMetric;

    /**
     * 是否护栏指标
     */
    private Boolean guardrailMetric;

    /**
     * 聚合类型
     */
    public enum AggregationType {
        RATE,
        COUNT
    }

    /**
     * 分母类型
     */
    public enum DenominatorType {
        EVENT_COUNT,
        VISITOR_COUNT,
        ASSIGNMENT_COUNT,
        EXPOSURE_COUNT
    }
}
