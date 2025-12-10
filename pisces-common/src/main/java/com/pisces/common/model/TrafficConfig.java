package com.pisces.common.model;

import lombok.Data;
import java.io.Serializable;
import java.util.List;

/**
 * 流量配置实体
 */
@Data
public class TrafficConfig implements Serializable {
    private static final long serialVersionUID = 1L;
    
    /**
     * 总流量比例（0.0-1.0）
     */
    private Double totalTraffic;
    
    /**
     * 流量分配列表
     */
    private List<GroupAllocation> allocation;
    
    /**
     * 分配策略：RANDOM-随机, HASH-哈希, RULE-规则
     */
    private TrafficStrategy strategy;
    
    /**
     * 哈希键（用于一致性哈希）
     */
    private String hashKey;
    
    /**
     * 流量分配项
     */
    @Data
    public static class GroupAllocation implements Serializable {
        private static final long serialVersionUID = 1L;
        
        /**
         * 实验组ID
         */
        private String group;
        
        /**
         * 流量比例
         */
        private Double ratio;
    }
    
    /**
     * 流量分配策略枚举
     */
    public enum TrafficStrategy {
        RANDOM,  // 随机分配
        HASH,    // 哈希分配
        RULE     // 规则分配
    }
}

