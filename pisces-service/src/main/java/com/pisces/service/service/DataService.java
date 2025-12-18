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
}

