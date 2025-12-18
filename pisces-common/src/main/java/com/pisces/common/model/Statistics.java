package com.pisces.common.model;

import lombok.Data;
import java.io.Serializable;
import java.util.Map;

/**
 * 统计数据实体
 */
@Data
public class Statistics implements Serializable {
    private static final long serialVersionUID = 1L;
    
    /**
     * 实验ID
     */
    private String experimentId;
    
    /**
     * 各实验组的统计数据
     */
    private Map<String, GroupStatistics> groupStatistics;
    
    /**
     * 实验组统计数据
     */
    @Data
    public static class GroupStatistics implements Serializable {
        private static final long serialVersionUID = 1L;
        
        /**
         * 实验组ID
         */
        private String groupId;
        
        /**
         * 访客总数（注意：字段名保持为userCount以兼容现有接口，但实际存储的是visitorCount）
         */
        private Long userCount;
        
        /**
         * 事件统计（事件类型 -> 数量）
         */
        private Map<String, Long> eventCounts;
        
        /**
         * 转化率
         */
        private Double conversionRate;
    }
}

