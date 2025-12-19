package com.pisces.service.service.impl;

import com.pisces.common.model.Event;
import com.pisces.service.service.DataService;
import com.pisces.service.service.MultiArmedBanditService;
import com.pisces.service.service.TrafficService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 数据收集服务实现（基于Redis存储）
 */
@Slf4j
@Service
public class DataServiceImpl implements DataService {
    
    @Autowired
    private TrafficService trafficService;
    
    @Autowired(required = false)
    private MultiArmedBanditService mabService;
    
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    // Redis Key前缀
    private static final String EVENT_STORE_PREFIX = "pisces:event:store:";  // 事件存储
    private static final String EVENT_COUNTER_PREFIX = "pisces:event:counter:";  // 事件计数器
    private static final String VISITOR_SET_PREFIX = "pisces:visitor:set:";  // 访客集合
    
    // 数据过期时间（天）
    private static final long DATA_EXPIRE_DAYS = 90;
    
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
        
        // 存储事件到Redis（使用List存储）
        String eventStoreKey = EVENT_STORE_PREFIX + experimentId + ":" + groupId;
        redisTemplate.opsForList().rightPush(eventStoreKey, event);
        redisTemplate.expire(eventStoreKey, DATA_EXPIRE_DAYS, TimeUnit.DAYS);
        
        // 更新计数器（使用Hash存储）
        updateEventCounter(experimentId, groupId, eventType);
        
        // 更新访客集合（使用Set存储，自动去重）
        String visitorSetKey = VISITOR_SET_PREFIX + experimentId + ":" + groupId;
        redisTemplate.opsForSet().add(visitorSetKey, visitorId);
        redisTemplate.expire(visitorSetKey, DATA_EXPIRE_DAYS, TimeUnit.DAYS);
        
        // 更新MAB算法奖励
        // 对于价格提升实验，使用成交价格作为奖励指标
        if (mabService != null && Event.EventType.CONVERT.name().equals(eventType)) {
            try {
                // 从properties中获取成交价格，用于计算奖励
                // 检查properties是否为null
                if (properties != null) {
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
                } else {
                    // 如果properties为null，默认视为成功
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
     * 更新事件计数器（使用Redis Hash）
     */
    private void updateEventCounter(String experimentId, String groupId, String eventType) {
        String counterKey = EVENT_COUNTER_PREFIX + experimentId + ":" + groupId;
        redisTemplate.opsForHash().increment(counterKey, eventType, 1);
        redisTemplate.expire(counterKey, DATA_EXPIRE_DAYS, TimeUnit.DAYS);
    }
    
    /**
     * 获取事件计数
     */
    @Override
    public long getEventCount(String experimentId, String groupId, String eventType) {
        String counterKey = EVENT_COUNTER_PREFIX + experimentId + ":" + groupId;
        Object count = redisTemplate.opsForHash().get(counterKey, eventType);
        if (count == null) {
            return 0;
        }
        return count instanceof Number ? ((Number) count).longValue() : Long.parseLong(count.toString());
    }
    
    /**
     * 获取实验组的访客数（去重后的访客ID数量）
     */
    @Override
    public long getVisitorCount(String experimentId, String groupId) {
        String visitorSetKey = VISITOR_SET_PREFIX + experimentId + ":" + groupId;
        Long size = redisTemplate.opsForSet().size(visitorSetKey);
        return size != null ? size : 0;
    }
}

