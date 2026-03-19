package com.pisces.common.model;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 实验事件事实
 */
@Data
public class ExperimentEventFact implements Serializable {
    private static final long serialVersionUID = 1L;

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
     * 事件属性
     */
    private Map<String, Object> properties;

    /**
     * 事件时间
     */
    private LocalDateTime eventTime;
}
