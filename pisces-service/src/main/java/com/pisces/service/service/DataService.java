package com.pisces.service.service;

import java.util.Map;

/**
 * 数据收集服务接口
 */
public interface DataService {
    
    /**
     * 上报事件
     * @param experimentId 实验ID
     * @param visitorId 访客唯一标识（可以是userId、设备ID、会话ID等）
     * @param eventType 事件类型
     * @param eventName 事件名称
     * @param properties 事件属性
     */
    void reportEvent(String experimentId, String visitorId, String eventType, 
                    String eventName, Map<String, Object> properties);
    
    /**
     * 获取事件计数
     */
    long getEventCount(String experimentId, String groupId, String eventType);
    
    /**
     * 获取实验组的访客数（去重后的访客ID数量）
     */
    long getVisitorCount(String experimentId, String groupId);
    
    /**
     * 获取指定时间范围内的事件计数
     * @param experimentId 实验ID
     * @param groupId 实验组ID
     * @param eventType 事件类型
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return 事件数量
     */
    long getEventCountInTimeRange(String experimentId, String groupId, String eventType,
                                   java.time.LocalDateTime startTime, java.time.LocalDateTime endTime);
    
    /**
     * 获取实验组的所有事件
     * @param experimentId 实验ID
     * @param groupId 实验组ID
     * @return 事件列表
     */
    java.util.List<com.pisces.common.model.Event> getEvents(String experimentId, String groupId);
    
    /**
     * 获取实验组在指定时间范围内的事件
     * @param experimentId 实验ID
     * @param groupId 实验组ID
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return 事件列表
     */
    java.util.List<com.pisces.common.model.Event> getEventsInTimeRange(String experimentId, String groupId,
                                                                         java.time.LocalDateTime startTime,
                                                                         java.time.LocalDateTime endTime);
    
    /**
     * 获取实验的总体统计摘要
     * @param experimentId 实验ID
     * @return 包含总访客数、总事件数等统计信息
     */
    java.util.Map<String, Object> getExperimentSummary(String experimentId);
}

