package com.pisces.common.model;

import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 事件实体
 */
@Data
public class Event implements Serializable {
    private static final long serialVersionUID = 1L;
    
    /**
     * 事件ID
     */
    private String eventId;
    
    /**
     * 实验ID
     */
    private String experimentId;
    
    /**
     * 用户ID
     */
    private String userId;
    
    /**
     * 实验组ID
     */
    private String groupId;
    
    /**
     * 事件类型：VIEW-浏览, CLICK-点击, CONVERT-转化
     */
    private EventType eventType;
    
    /**
     * 事件名称
     */
    private String eventName;
    
    /**
     * 事件属性
     */
    private Map<String, Object> properties;
    
    /**
     * 时间戳
     */
    private LocalDateTime timestamp;
    
    /**
     * 事件类型枚举
     */
    public enum EventType {
        VIEW,     // 浏览
        CLICK,    // 点击
        CONVERT   // 转化
    }
}

