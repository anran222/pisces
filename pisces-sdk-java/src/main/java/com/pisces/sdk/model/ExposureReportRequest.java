package com.pisces.sdk.model;

import java.util.Map;

/**
 * 曝光上报模型
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/3/20 18:09
 */
public class ExposureReportRequest {

    private String experimentId;
    private String visitorId;
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

    public Map<String, Object> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, Object> properties) {
        this.properties = properties;
    }
}
