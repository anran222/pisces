package com.pisces.common.model;

import lombok.Data;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 实验元数据（存储在Zookeeper中的完整配置）
 */
@Data
public class ExperimentMetadata implements Serializable {
    private static final long serialVersionUID = 1L;
    
    /**
     * 实验基本信息
     */
    private Experiment experiment;
    
    /**
     * 实验组列表
     */
    private Map<String, ExperimentGroup> groups;
    
    /**
     * 流量配置
     */
    private TrafficConfig traffic;
    
    /**
     * 白名单用户ID列表
     */
    private List<String> whitelist;
    
    /**
     * 黑名单用户ID列表
     */
    private List<String> blacklist;
}

