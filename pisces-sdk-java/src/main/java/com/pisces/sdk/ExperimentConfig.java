package com.pisces.sdk;

import lombok.Data;
import java.util.Map;

/**
 * 实验配置
 */
@Data
public class ExperimentConfig {
    private String id;
    private String name;
    private String description;
    private String status;
    private Map<String, GroupConfig> groups;
    
    @Data
    public static class GroupConfig {
        private String id;
        private String name;
        private Double trafficRatio;
        private Map<String, Object> config;
    }
}
