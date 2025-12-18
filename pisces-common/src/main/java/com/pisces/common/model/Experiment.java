package com.pisces.common.model;

import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 实验实体
 */
@Data
public class Experiment implements Serializable {
    private static final long serialVersionUID = 1L;
    
    /**
     * 实验ID
     */
    private String id;
    
    /**
     * 实验名称
     */
    private String name;
    
    /**
     * 实验描述
     */
    private String description;
    
    /**
     * 实验状态：DRAFT-草稿, RUNNING-运行中, PAUSED-已暂停, STOPPED-已停止
     */
    private ExperimentStatus status;
    
    /**
     * 开始时间
     */
    private LocalDateTime startTime;
    
    /**
     * 结束时间
     */
    private LocalDateTime endTime;
    
    /**
     * 创建人
     */
    private String creator;
    
    /**
     * 创建时间
     */
    private LocalDateTime createTime;
    
    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
    
    /**
     * 实验状态枚举
     */
    public enum ExperimentStatus {
        DRAFT,      // 草稿
        RUNNING,    // 运行中
        PAUSED,     // 已暂停
        STOPPED     // 已停止
    }
}

