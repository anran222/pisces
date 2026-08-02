package com.pisces.sdk.model;

/**
 * 分流响应模型
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/3/20 18:09
 */
public class TrafficAssignResponse {

    private String experimentId;

    private String visitorId;

    private String canonicalVisitorId;

    private String groupId;

    private Boolean assigned;

    private String reason;

    private String source;

    private String strategy;

    private Long configVersion;

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

    public String getCanonicalVisitorId() {
        return canonicalVisitorId;
    }

    public void setCanonicalVisitorId(String canonicalVisitorId) {
        this.canonicalVisitorId = canonicalVisitorId;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public Boolean getAssigned() {
        return assigned;
    }

    public void setAssigned(Boolean assigned) {
        this.assigned = assigned;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getStrategy() {
        return strategy;
    }

    public void setStrategy(String strategy) {
        this.strategy = strategy;
    }

    public Long getConfigVersion() {
        return configVersion;
    }

    public void setConfigVersion(Long configVersion) {
        this.configVersion = configVersion;
    }
}
