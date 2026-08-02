package com.pisces.service.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 应用指标定义实体
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/16 19:02
 */
@Data
public class ApplicationMetricDefinitionEntity {

    private Long id;

    private String appId;

    private String metricKey;

    private String name;

    private String description;

    private String aggregationType;

    private String numeratorEventType;

    private String denominatorType;

    private String denominatorEventType;

    private Boolean primaryMetric;

    private Boolean guardrailMetric;

    private String sourceExperimentId;

    private String updatedBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
