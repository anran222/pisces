package com.pisces.service.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 实验分流事实实体
 */
@Data
public class ExperimentAssignmentEntity {

    /**
     * 主键ID
     */
    private Long id;

    /**
     * 分流事实ID
     */
    private String assignmentId;

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
     * 哈希字段
     */
    private String hashKey;

    /**
     * 配置版本
     */
    private Long configVersion;

    /**
     * 分流属性JSON
     */
    private String attributesJson;

    /**
     * 幂等键
     */
    private String idempotencyKey;

    /**
     * 分流时间
     */
    private LocalDateTime assignedAt;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
