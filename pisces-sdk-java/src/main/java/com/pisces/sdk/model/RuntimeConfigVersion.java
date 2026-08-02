package com.pisces.sdk.model;

/**
 * 运行时配置版本模型
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/1 11:53
 */
public class RuntimeConfigVersion {

    private String experimentId;

    private Long knownVersion;

    private Long currentVersion;

    private Boolean changed;

    private String status;

    public String getExperimentId() {
        return experimentId;
    }

    public void setExperimentId(String experimentId) {
        this.experimentId = experimentId;
    }

    public Long getKnownVersion() {
        return knownVersion;
    }

    public void setKnownVersion(Long knownVersion) {
        this.knownVersion = knownVersion;
    }

    public Long getCurrentVersion() {
        return currentVersion;
    }

    public void setCurrentVersion(Long currentVersion) {
        this.currentVersion = currentVersion;
    }

    public Boolean getChanged() {
        return changed;
    }

    public void setChanged(Boolean changed) {
        this.changed = changed;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
