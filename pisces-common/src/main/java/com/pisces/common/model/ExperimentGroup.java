package com.pisces.common.model;

import lombok.Data;
import java.io.Serializable;
import java.util.Map;

/**
 * 实验组实体
 */
@Data
public class ExperimentGroup implements Serializable {
    private static final long serialVersionUID = 1L;
    
    /**
     * 实验组ID（A、B、C等）
     */
    private String id;
    
    /**
     * 实验组名称
     */
    private String name;
    
    /**
     * 流量分配比例（0.0-1.0）
     */
    private Double trafficRatio;
    
    /**
     * 实验组配置（JSON格式的配置参数）
     */
    private Map<String, Object> config;
}

