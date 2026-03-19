package com.pisces.service.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 实验事件事实实体
 */
@Data
public class ExperimentEventEntity {

    /**
     * 主键ID
     */
    private Long id;

    /**
     * 事件事实ID
     */
    private String eventId;

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
     * 事件类型
     */
    private String eventType;

    /**
     * 事件名称
     */
    private String eventName;

    /**
     * 客户端幂等事件ID
     */
    private String clientEventId;

    /**
     * 事件属性JSON
     */
    private String propertiesJson;

    /**
     * 事件时间
     */
    private LocalDateTime eventTime;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
}
