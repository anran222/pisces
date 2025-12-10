package com.pisces.service.service.impl;

import com.pisces.common.model.Event;
import com.pisces.service.service.DataService;
import com.pisces.service.service.TrafficService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 数据收集服务实现
 */
@Slf4j
@Service
public class DataServiceImpl implements DataService {
    
    @Autowired
    private TrafficService trafficService;
    
    /**
     * 事件存储（实际应该存储到数据库或消息队列）
     */
    private final ConcurrentHashMap<String, List<Event>> eventStore = new ConcurrentHashMap<>();
    
    /**
     * 事件计数器（实验ID -> 事件类型 -> 数量）
     */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, AtomicLong>> eventCounters = 
            new ConcurrentHashMap<>();
    
    /**
     * 上报事件
     */
    @Override
    public void reportEvent(String experimentId, String userId, String eventType, 
                           String eventName, Map<String, Object> properties) {
        // 获取用户所在组
        String groupId = trafficService.getUserGroup(experimentId, userId);
        if (groupId == null) {
            log.warn("用户 {} 不在实验 {} 中", userId, experimentId);
            return;
        }
        
        // 创建事件
        Event event = new Event();
        event.setEventId("evt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        event.setExperimentId(experimentId);
        event.setUserId(userId);
        event.setGroupId(groupId);
        event.setEventType(Event.EventType.valueOf(eventType));
        event.setEventName(eventName);
        event.setProperties(properties);
        event.setTimestamp(LocalDateTime.now());
        
        // 存储事件
        String key = experimentId + ":" + groupId;
        eventStore.computeIfAbsent(key, k -> new ArrayList<>()).add(event);
        
        // 更新计数器
        updateEventCounter(experimentId, groupId, eventType);
        
        log.debug("上报事件: 实验={}, 用户={}, 组={}, 事件={}", 
                experimentId, userId, groupId, eventName);
    }

    /**
     * 更新事件计数器
     */
    private void updateEventCounter(String experimentId, String groupId, String eventType) {
        String counterKey = experimentId + ":" + groupId;
        eventCounters.computeIfAbsent(counterKey, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(eventType, k -> new AtomicLong(0))
                .incrementAndGet();
    }
    
    /**
     * 获取事件计数
     */
    @Override
    public long getEventCount(String experimentId, String groupId, String eventType) {
        String counterKey = experimentId + ":" + groupId;
        ConcurrentHashMap<String, AtomicLong> counters = eventCounters.get(counterKey);
        if (counters == null) {
            return 0;
        }
        AtomicLong counter = counters.get(eventType);
        return counter != null ? counter.get() : 0;
    }
}

