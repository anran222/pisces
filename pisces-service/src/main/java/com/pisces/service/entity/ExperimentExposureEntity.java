package com.pisces.service.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 实验曝光事实实体
 */
@Data
public class ExperimentExposureEntity {

    /**
     * 主键ID
     */
    private Long id;

    /**
     * 曝光事实ID
     */
    private String exposureId;

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
     * 曝光场景
     */
    private String scene;

    /**
     * 曝光属性JSON
     */
    private String propertiesJson;

    /**
     * 幂等键
     */
    private String idempotencyKey;

    /**
     * 曝光时间
     */
    private LocalDateTime exposedAt;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
}
