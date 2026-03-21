package com.pisces.sdk.model;

import java.util.Map;

/**
 * 事件上报模型
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/3/20 18:09
 */
public class EventReportRequest {

    private String experimentId;
    private String visitorId;
    private String eventType;
    private String eventName;
    private Map<String, Object> properties;

    public String getExperimentId() {
        return experimentId;
    }

    public void setExperimentId(String experimentId) {
        this.experimentId = experimentId;
    }

    public String getVisitorId() {
        return visitorId;
    }

    public void setVisitorId(String visitorId) {
        this.visitorId = visitorId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getEventName() {
        return eventName;
    }

    public void setEventName(String eventName) {
        this.eventName = eventName;
    }

    public Map<String, Object> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, Object> properties) {
        this.properties = properties;
    }
}
