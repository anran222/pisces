package com.pisces.sdk.model;

import java.util.Map;

/**
 * 实验组配置模型
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/3/20 18:09
 */
public class ExperimentGroupConfig {

    private String id;
    private String name;
    private Double trafficRatio;
    private Map<String, Object> config;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Double getTrafficRatio() {
        return trafficRatio;
    }

    public void setTrafficRatio(Double trafficRatio) {
        this.trafficRatio = trafficRatio;
    }

    public Map<String, Object> getConfig() {
        return config;
    }

    public void setConfig(Map<String, Object> config) {
        this.config = config;
    }
}
