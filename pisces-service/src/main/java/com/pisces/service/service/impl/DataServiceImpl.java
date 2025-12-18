package com.pisces.service.service.impl;

import com.pisces.common.model.Event;
import com.pisces.service.service.DataService;
import com.pisces.service.service.MultiArmedBanditService;
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
    
    @Autowired(required = false)
    private MultiArmedBanditService mabService;
    
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
     * 访客集合（实验ID:组ID -> 访客ID集合），用于快速统计访客数
     */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Boolean>> visitorSets = 
            new ConcurrentHashMap<>();
    
    /**
     * 上报事件（使用visitorId，可以是userId、设备ID、会话ID等）
     */
    @Override
    public void reportEvent(String experimentId, String visitorId, String eventType, 
                           String eventName, Map<String, Object> properties) {
        // 获取访客所在组
        String groupId = trafficService.getUserGroup(experimentId, visitorId);
        if (groupId == null) {
            log.warn("访客 {} 不在实验 {} 中", visitorId, experimentId);
            return;
        }
        
        // 创建事件
        Event event = new Event();
        event.setEventId("evt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        event.setExperimentId(experimentId);
        event.setUserId(visitorId);  // 使用visitorId（兼容原有字段名）
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
        
        // 更新访客集合（用于统计访客数）
        String visitorSetKey = experimentId + ":" + groupId;
        visitorSets.computeIfAbsent(visitorSetKey, k -> new ConcurrentHashMap<>())
                .put(visitorId, true);
        
        // 更新MAB算法奖励
        // 对于价格提升实验，使用成交价格作为奖励指标
        if (mabService != null && Event.EventType.CONVERT.name().equals(eventType)) {
            try {
                // 从properties中获取成交价格，用于计算奖励
                Object transactionPrice = properties.get("transactionPrice");
                if (transactionPrice != null) {
                    // 价格越高，奖励越大（简化处理：价格>4500视为成功）
                    boolean success = ((Number) transactionPrice).doubleValue() > 4500;
                    mabService.updateReward(experimentId, groupId, success);
                    log.debug("更新MAB奖励: 实验={}, 组={}, 价格={}, 成功={}", 
                            experimentId, groupId, transactionPrice, success);
                } else {
                    // 如果没有价格信息，默认视为成功
                    mabService.updateReward(experimentId, groupId, true);
                }
            } catch (Exception e) {
                log.warn("更新MAB奖励失败: 实验={}, 组={}", experimentId, groupId, e);
            }
        }
        
        log.debug("上报事件: 实验={}, 访客={}, 组={}, 事件={}", 
                experimentId, visitorId, groupId, eventName);
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
    
    /**
     * 获取实验组的访客数（去重后的访客ID数量）
     */
    @Override
    public long getVisitorCount(String experimentId, String groupId) {
        String visitorSetKey = experimentId + ":" + groupId;
        ConcurrentHashMap<String, Boolean> visitorSet = visitorSets.get(visitorSetKey);
        return visitorSet != null ? visitorSet.size() : 0;
    }
}

