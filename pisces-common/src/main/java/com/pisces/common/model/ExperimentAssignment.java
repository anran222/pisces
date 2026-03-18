package com.pisces.common.model;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 实验分流事实
 */
@Data
public class ExperimentAssignment implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 实验ID
     */
    private String experimentId;

    /**
     * 访客ID
     */
    private String visitorId;

    /**
     * 实验组ID
     */
    private String groupId;

    /**
     * 分流策略
     */
    private String strategy;

    /**
     * 配置版本
     */
    private Long configVersion;

    /**
     * 分流时间
     */
    private LocalDateTime assignedAt;
}
