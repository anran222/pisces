package com.pisces.common.model;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 应用指标定义
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/16 19:02
 */
@Data
public class ApplicationMetricDefinition implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 应用ID
     */
    private String appId;

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
    private MetricDefinition.AggregationType aggregationType;

    /**
     * 分子事件类型
     */
    private String numeratorEventType;

    /**
     * 分母类型
     */
    private MetricDefinition.DenominatorType denominatorType;

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
     * 来源实验ID
     */
    private String sourceExperimentId;

    /**
     * 更新人
     */
    private String updatedBy;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
