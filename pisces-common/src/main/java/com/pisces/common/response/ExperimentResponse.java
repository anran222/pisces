package com.pisces.common.response;

import com.pisces.common.model.Experiment;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.util.List;
import java.util.Map;

/**
 * 实验响应
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class ExperimentResponse extends Experiment {
    
    /**
     * 实验组列表
     */
    private Map<String, GroupResponse> groups;
    
    /**
     * 流量配置
     */
    private TrafficConfigResponse traffic;
    
    /**
     * 白名单
     */
    private List<String> whitelist;
    
    /**
     * 黑名单
     */
    private List<String> blacklist;
    
    @Data
    public static class GroupResponse {
        private String id;
        private String name;
        private Double trafficRatio;
        private Map<String, Object> config;
    }
    
    @Data
    public static class TrafficConfigResponse {
        private Double totalTraffic;
        private List<GroupAllocationResponse> allocation;
        private String strategy;
        private String hashKey;
    }
    
    @Data
    public static class GroupAllocationResponse {
        private String group;
        private Double ratio;
    }
}

