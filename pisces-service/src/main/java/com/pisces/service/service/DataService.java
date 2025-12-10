package com.pisces.service.service;

import java.util.Map;

/**
 * 数据收集服务接口
 */
public interface DataService {
    
    /**
     * 上报事件
     */
    void reportEvent(String experimentId, String userId, String eventType, 
                    String eventName, Map<String, Object> properties);
    
    /**
     * 获取事件计数
     */
    long getEventCount(String experimentId, String groupId, String eventType);
}

