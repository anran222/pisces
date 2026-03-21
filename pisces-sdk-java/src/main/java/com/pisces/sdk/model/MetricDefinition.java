package com.pisces.sdk.model;

/**
 * 实验指标定义
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/3/21 18:42
 */
public class MetricDefinition {

    private String key;

    private String name;

    private String description;

    private AggregationType aggregationType;

    private String numeratorEventType;

    private DenominatorType denominatorType;

    private String denominatorEventType;

    private Boolean primaryMetric;

    private Boolean guardrailMetric;

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public AggregationType getAggregationType() {
        return aggregationType;
    }

    public void setAggregationType(AggregationType aggregationType) {
        this.aggregationType = aggregationType;
    }

    public String getNumeratorEventType() {
        return numeratorEventType;
    }

    public void setNumeratorEventType(String numeratorEventType) {
        this.numeratorEventType = numeratorEventType;
    }

    public DenominatorType getDenominatorType() {
        return denominatorType;
    }

    public void setDenominatorType(DenominatorType denominatorType) {
        this.denominatorType = denominatorType;
    }

    public String getDenominatorEventType() {
        return denominatorEventType;
    }

    public void setDenominatorEventType(String denominatorEventType) {
        this.denominatorEventType = denominatorEventType;
    }

    public Boolean getPrimaryMetric() {
        return primaryMetric;
    }

    public void setPrimaryMetric(Boolean primaryMetric) {
        this.primaryMetric = primaryMetric;
    }

    public Boolean getGuardrailMetric() {
        return guardrailMetric;
    }

    public void setGuardrailMetric(Boolean guardrailMetric) {
        this.guardrailMetric = guardrailMetric;
    }

    public enum AggregationType {
        RATE,
        COUNT
    }

    public enum DenominatorType {
        EVENT_COUNT,
        VISITOR_COUNT,
        ASSIGNMENT_COUNT,
        EXPOSURE_COUNT
    }
}
