package com.pisces.service.service.impl;

import com.pisces.common.model.Event;
import com.pisces.service.service.DataService;
import com.pisces.service.service.MultiArmedBanditService;
import com.pisces.service.service.TrafficService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import org.springframework.data.redis.core.ScanOptions;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    
    // 事件去重键前缀（用于客户端幂等上报）
    private static final String EVENT_DEDUP_PREFIX = "pisces:event:dedup:";
    // 事件去重过期时间（天）—— 与数据保留时长一致
    private static final long DEDUP_EXPIRE_DAYS = 90;
    // 数据过期时间（天）
    private static final long DATA_EXPIRE_DAYS = 90;
    
    /**
     * 上报事件（使用visitorId，可以是userId、设备ID、会话ID等）
     * properties 中可携带 clientEventId 字段用于客户端幂等去重
     */
    @Override
    public void reportEvent(String experimentId, String visitorId, String eventType,
                           String eventName, Map<String, Object> properties) {
        // 客户端幂等去重：若调用方携带了 clientEventId，则以此作为去重键（原子 SetIfAbsent）
        if (properties != null && properties.containsKey("clientEventId")) {
            String clientEventId = String.valueOf(properties.get("clientEventId"));
            String dedupKey = EVENT_DEDUP_PREFIX + experimentId + ":" + clientEventId;
            Boolean added = redisTemplate.opsForValue().setIfAbsent(dedupKey, "1", DEDUP_EXPIRE_DAYS, TimeUnit.DAYS);
            if (Boolean.FALSE.equals(added)) {
                log.debug("重复事件已丢弃: clientEventId={}, experimentId={}", clientEventId, experimentId);
                return;
            }
        }

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
        event.setTimestamp(resolveEventTimestamp(properties));
        
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
        // 转化事件默认视为成功，业务方可通过 properties.mabSuccess=false 覆盖
        if (mabService != null && Event.EventType.CONVERT.name().equals(eventType)) {
            try {
                boolean success = true;
                if (properties != null && properties.containsKey("mabSuccess")) {
                    Object flag = properties.get("mabSuccess");
                    if (flag instanceof Boolean) {
                        success = (Boolean) flag;
                    } else {
                        success = Boolean.parseBoolean(String.valueOf(flag));
                    }
                }
                mabService.updateReward(experimentId, groupId, success);
                log.debug("更新MAB奖励: 实验={}, 组={}, 成功={}", experimentId, groupId, success);
            } catch (Exception e) {
                log.warn("更新MAB奖励失败: 实验={}, 组={}", experimentId, groupId, e);
            }
        }
        
        log.debug("上报事件: 实验={}, 访客={}, 组={}, 事件={}", 
                experimentId, visitorId, groupId, eventName);
    }

    private LocalDateTime resolveEventTimestamp(Map<String, Object> properties) {
        if (properties == null || properties.isEmpty()) {
            return LocalDateTime.now();
        }

        for (String key : List.of("eventTime", "timestamp", "transactionDate")) {
            Object value = properties.get(key);
            if (value == null) {
                continue;
            }
            if (value instanceof LocalDateTime localDateTime) {
                return localDateTime;
            }
            if (value instanceof String stringValue) {
                try {
                    return LocalDateTime.parse(stringValue);
                } catch (Exception ignored) {
                    // Continue trying the next field/value.
                }
            }
        }

        return LocalDateTime.now();
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
    
    /**
     * 获取指定时间范围内的事件计数
     */
    @Override
    public long getEventCountInTimeRange(String experimentId, String groupId, String eventType,
                                          LocalDateTime startTime, LocalDateTime endTime) {
        List<Event> events = getEventsInTimeRange(experimentId, groupId, startTime, endTime);
        return events.stream()
                .filter(e -> e.getEventType() != null && e.getEventType().name().equals(eventType))
                .count();
    }
    
    /**
     * 获取实验组的所有事件
     */
    @Override
    public List<Event> getEvents(String experimentId, String groupId) {
        String eventStoreKey = EVENT_STORE_PREFIX + experimentId + ":" + groupId;
        List<Object> eventObjects = redisTemplate.opsForList().range(eventStoreKey, 0, -1);
        
        List<Event> events = new ArrayList<>();
        if (eventObjects != null) {
            for (Object obj : eventObjects) {
                if (obj instanceof Event) {
                    events.add((Event) obj);
                } else if (obj instanceof Map) {
                    // 如果Redis序列化为Map，需要手动转换
                    Event event = convertMapToEvent((Map<String, Object>) obj);
                    if (event != null) {
                        events.add(event);
                    }
                }
            }
        }
        return events;
    }
    
    /**
     * 获取实验组在指定时间范围内的事件
     */
    @Override
    public List<Event> getEventsInTimeRange(String experimentId, String groupId,
                                            LocalDateTime startTime, LocalDateTime endTime) {
        List<Event> allEvents = getEvents(experimentId, groupId);
        
        return allEvents.stream()
                .filter(event -> {
                    LocalDateTime timestamp = event.getTimestamp();
                    if (timestamp == null) return false;
                    
                    boolean afterStart = startTime == null || !timestamp.isBefore(startTime);
                    boolean beforeEnd = endTime == null || !timestamp.isAfter(endTime);
                    return afterStart && beforeEnd;
                })
                .collect(java.util.stream.Collectors.toList());
    }
    
    /**
     * 获取实验的总体统计摘要
     */
    @Override
    public Map<String, Object> getExperimentSummary(String experimentId) {
        Map<String, Object> summary = new java.util.HashMap<>();
        
        // 获取实验配置以获取所有组
        try {
            // 统计所有组的数据
            long totalVisitors = 0;
            long totalViews = 0;
            long totalClicks = 0;
            long totalConversions = 0;
            
            // 使用 SCAN 游标遍历键，避免 KEYS 命令在大数据量时阻塞 Redis
            String pattern = VISITOR_SET_PREFIX + experimentId + ":*";
            Set<String> visitorKeys = scanKeys(pattern);
            
            if (visitorKeys != null) {
                for (String key : visitorKeys) {
                    String groupId = key.substring(key.lastIndexOf(":") + 1);
                    totalVisitors += getVisitorCount(experimentId, groupId);
                    totalViews += getEventCount(experimentId, groupId, "VIEW");
                    totalClicks += getEventCount(experimentId, groupId, "CLICK");
                    totalConversions += getEventCount(experimentId, groupId, "CONVERT");
                }
            }
            
            summary.put("experimentId", experimentId);
            summary.put("totalVisitors", totalVisitors);
            summary.put("totalViews", totalViews);
            summary.put("totalClicks", totalClicks);
            summary.put("totalConversions", totalConversions);
            summary.put("overallClickRate", totalViews > 0 ? (double) totalClicks / totalViews : 0.0);
            summary.put("overallConversionRate", totalViews > 0 ? (double) totalConversions / totalViews : 0.0);
            
        } catch (Exception e) {
            log.error("获取实验摘要失败: {}", experimentId, e);
        }
        
        return summary;
    }
    
    /**
     * 使用 SCAN 游标安全扫描 Redis 键，替代阻塞式 KEYS 命令
     */
    private Set<String> scanKeys(String pattern) {
        Set<String> keys = new HashSet<>();
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(200).build();
        try (var cursor = redisTemplate.execute(
                (org.springframework.data.redis.core.RedisCallback<org.springframework.data.redis.core.Cursor<byte[]>>) connection ->
                        connection.keyCommands().scan(options))) {
            if (cursor != null) {
                cursor.forEachRemaining(key -> keys.add(new String(key, StandardCharsets.UTF_8)));
            }
        } catch (Exception e) {
            log.error("扫描 Redis 键失败: pattern={}", pattern, e);
        }
        return keys;
    }

    /**
     * 将Map转换为Event对象
     */
    @SuppressWarnings("unchecked")
    private Event convertMapToEvent(Map<String, Object> map) {
        try {
            Event event = new Event();
            event.setEventId((String) map.get("eventId"));
            event.setExperimentId((String) map.get("experimentId"));
            event.setUserId((String) map.get("userId"));
            event.setGroupId((String) map.get("groupId"));
            
            Object eventTypeObj = map.get("eventType");
            if (eventTypeObj instanceof String) {
                event.setEventType(Event.EventType.valueOf((String) eventTypeObj));
            } else if (eventTypeObj instanceof Event.EventType) {
                event.setEventType((Event.EventType) eventTypeObj);
            }
            
            event.setEventName((String) map.get("eventName"));
            event.setProperties((Map<String, Object>) map.get("properties"));
            
            // 处理时间戳
            Object timestampObj = map.get("timestamp");
            if (timestampObj instanceof LocalDateTime) {
                event.setTimestamp((LocalDateTime) timestampObj);
            } else if (timestampObj instanceof String) {
                event.setTimestamp(LocalDateTime.parse((String) timestampObj));
            }
            
            return event;
        } catch (Exception e) {
            log.warn("转换事件对象失败: {}", e.getMessage());
            return null;
        }
    }
}
