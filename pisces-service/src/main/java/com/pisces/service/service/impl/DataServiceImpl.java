package com.pisces.service.service.impl;

import com.pisces.common.model.Event;
import com.pisces.common.model.ExperimentEventFact;
import com.pisces.common.model.ExperimentExposure;
import com.pisces.common.model.ExperimentMetadata;
import com.pisces.common.model.MetricDefinition;
import com.pisces.service.repository.ExperimentAssignmentRepository;
import com.pisces.service.repository.ExperimentEventRepository;
import com.pisces.service.repository.ExperimentExposureRepository;
import com.pisces.service.service.ConfigService;
import com.pisces.service.service.DataService;
import com.pisces.service.service.IdentityService;
import com.pisces.service.service.MultiArmedBanditService;
import com.pisces.service.service.TrafficService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

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

    @Autowired(required = false)
    private IdentityService identityService;

    @Autowired
    private ConfigService configService;

    @Autowired
    private ExperimentAssignmentRepository experimentAssignmentRepository;

    @Autowired
    private ExperimentExposureRepository experimentExposureRepository;

    @Autowired
    private ExperimentEventRepository experimentEventRepository;
    
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    // Redis Key前缀
    private static final String EVENT_STORE_PREFIX = "pisces:event:store:";  // 事件存储
    private static final String EVENT_COUNTER_PREFIX = "pisces:event:counter:";  // 事件计数器
    private static final String VISITOR_SET_PREFIX = "pisces:visitor:set:";  // 访客集合
    private static final String EXPOSURE_STORE_PREFIX = "pisces:exposure:store:";
    private static final String EXPOSURE_SET_PREFIX = "pisces:exposure:set:";
    
    // 数据过期时间（天）
    private static final long DATA_EXPIRE_DAYS = 90;
    
    /**
     * 上报事件（使用visitorId，可以是userId、设备ID、会话ID等）
     * properties 中可携带 clientEventId 字段用于客户端幂等去重
     */
    @Override
    public void reportEvent(String experimentId, String visitorId, String eventType,
                           String eventName, Map<String, Object> properties) {
        String canonicalVisitorId = resolveCanonicalVisitorId(visitorId);
        String groupId = trafficService.getUserGroup(experimentId, canonicalVisitorId);
        if (groupId == null) {
            log.warn("访客 {} 不在实验 {} 中", canonicalVisitorId, experimentId);
            return;
        }
        String normalizedEventType = normalizeEventType(eventType);

        Event event = new Event();
        event.setEventId("evt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        event.setExperimentId(experimentId);
        event.setUserId(canonicalVisitorId);
        event.setGroupId(groupId);
        event.setEventType(normalizedEventType);
        event.setEventName(eventName);
        event.setProperties(properties);
        event.setTimestamp(resolveEventTimestamp(properties));

        experimentEventRepository.save(buildExperimentEventFact(event, properties));

        String eventStoreKey = EVENT_STORE_PREFIX + experimentId + ":" + groupId;
        redisTemplate.opsForList().rightPush(eventStoreKey, event);
        redisTemplate.expire(eventStoreKey, DATA_EXPIRE_DAYS, TimeUnit.DAYS);

        updateEventCounter(experimentId, groupId, normalizedEventType);

        String visitorSetKey = VISITOR_SET_PREFIX + experimentId + ":" + groupId;
        redisTemplate.opsForSet().add(visitorSetKey, canonicalVisitorId);
        redisTemplate.expire(visitorSetKey, DATA_EXPIRE_DAYS, TimeUnit.DAYS);

        if (mabService != null && shouldUpdateMabReward(experimentId, normalizedEventType)) {
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
                experimentId, canonicalVisitorId, groupId, eventName);
    }

    @Override
    public void reportExposure(String experimentId, String visitorId, Map<String, Object> properties) {
        String canonicalVisitorId = resolveCanonicalVisitorId(visitorId);
        String groupId = trafficService.getUserGroup(experimentId, canonicalVisitorId);
        if (groupId == null) {
            log.warn("访客 {} 不在实验 {} 中，无法记录曝光", canonicalVisitorId, experimentId);
            return;
        }

        ExperimentExposure exposure = new ExperimentExposure();
        exposure.setExposureId("expo_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        exposure.setExperimentId(experimentId);
        exposure.setVisitorId(canonicalVisitorId);
        exposure.setGroupId(groupId);
        exposure.setProperties(properties);
        exposure.setExposedAt(resolveEventTimestamp(properties));
        exposure.setScene(resolveExposureScene(properties));
        exposure.setIdempotencyKey(buildExposureIdempotencyKey(experimentId, canonicalVisitorId, groupId));

        experimentExposureRepository.save(exposure);

        String exposureStoreKey = EXPOSURE_STORE_PREFIX + experimentId + ":" + groupId;
        redisTemplate.opsForList().rightPush(exposureStoreKey, exposure);
        redisTemplate.expire(exposureStoreKey, DATA_EXPIRE_DAYS, TimeUnit.DAYS);

        String exposureSetKey = EXPOSURE_SET_PREFIX + experimentId + ":" + groupId;
        redisTemplate.opsForSet().add(exposureSetKey, canonicalVisitorId);
        redisTemplate.expire(exposureSetKey, DATA_EXPIRE_DAYS, TimeUnit.DAYS);
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
        return experimentEventRepository.countByExperimentIdAndGroupIdAndEventType(experimentId, groupId, eventType);
    }
    
    /**
     * 获取实验组的访客数（去重后的访客ID数量）
     */
    @Override
    public long getVisitorCount(String experimentId, String groupId) {
        return experimentEventRepository.countDistinctVisitorByExperimentIdAndGroupId(experimentId, groupId);
    }

    @Override
    public long getAssignmentCount(String experimentId, String groupId) {
        return experimentAssignmentRepository.countByExperimentIdAndGroupId(experimentId, groupId);
    }

    @Override
    public long getExposureCount(String experimentId, String groupId) {
        return experimentExposureRepository.countByExperimentIdAndGroupId(experimentId, groupId);
    }
    
    /**
     * 获取指定时间范围内的事件计数
     */
    @Override
    public long getEventCountInTimeRange(String experimentId, String groupId, String eventType,
                                          LocalDateTime startTime, LocalDateTime endTime) {
        List<Event> events = getEventsInTimeRange(experimentId, groupId, startTime, endTime);
        return events.stream()
                .filter(event -> Objects.equals(event.getEventType(), eventType))
                .count();
    }
    
    /**
     * 获取实验组的所有事件
     */
    @Override
    public List<Event> getEvents(String experimentId, String groupId) {
        return experimentEventRepository.listByExperimentIdAndGroupId(experimentId, groupId).stream()
                .map(this::buildEvent)
                .toList();
    }
    
    /**
     * 获取实验组在指定时间范围内的事件
     */
    @Override
    public List<Event> getEventsInTimeRange(String experimentId, String groupId,
                                            LocalDateTime startTime, LocalDateTime endTime) {
        return experimentEventRepository.listByExperimentIdAndGroupIdInTimeRange(experimentId, groupId, startTime, endTime)
                .stream()
                .map(this::buildEvent)
                .toList();
    }
    
    /**
     * 获取实验的总体统计摘要
     */
    @Override
    public Map<String, Object> getExperimentSummary(String experimentId) {
        Map<String, Object> summary = new HashMap<>();
        long totalVisitors = 0;
        long totalAssignments = 0;
        long totalExposures = 0;
        long totalViews = 0;
        long totalClicks = 0;
        long totalConversions = 0;

        var metadata = configService.getExperimentConfig(experimentId);
        if (metadata != null && metadata.getGroups() != null) {
            for (String groupId : metadata.getGroups().keySet()) {
                totalVisitors += getVisitorCount(experimentId, groupId);
                totalAssignments += getAssignmentCount(experimentId, groupId);
                totalExposures += getExposureCount(experimentId, groupId);
                totalViews += getEventCount(experimentId, groupId, Event.EVENT_TYPE_VIEW);
                totalClicks += getEventCount(experimentId, groupId, Event.EVENT_TYPE_CLICK);
                totalConversions += getEventCount(experimentId, groupId, Event.EVENT_TYPE_CONVERT);
            }
        }

        summary.put("experimentId", experimentId);
        summary.put("totalVisitors", totalVisitors);
        summary.put("totalAssignments", totalAssignments);
        summary.put("totalExposures", totalExposures);
        summary.put("totalViews", totalViews);
        summary.put("totalClicks", totalClicks);
        summary.put("totalConversions", totalConversions);
        summary.put("overallClickRate", totalViews > 0 ? (double) totalClicks / totalViews : 0.0);
        summary.put("overallConversionRate", totalViews > 0 ? (double) totalConversions / totalViews : 0.0);
        return summary;
    }

    @Override
    public List<ExperimentExposure> getExposures(String experimentId, String groupId) {
        return experimentExposureRepository.listByExperimentIdAndGroupId(experimentId, groupId);
    }

    private String resolveCanonicalVisitorId(String visitorId) {
        return identityService != null ? identityService.resolveCanonicalId(visitorId) : visitorId;
    }

    private ExperimentEventFact buildExperimentEventFact(Event event, Map<String, Object> properties) {
        ExperimentEventFact eventFact = new ExperimentEventFact();
        eventFact.setEventId(event.getEventId());
        eventFact.setExperimentId(event.getExperimentId());
        eventFact.setVisitorId(event.getUserId());
        eventFact.setGroupId(event.getGroupId());
        eventFact.setEventType(event.getEventType());
        eventFact.setEventName(event.getEventName());
        eventFact.setClientEventId(resolveClientEventId(properties));
        eventFact.setProperties(properties);
        eventFact.setEventTime(event.getTimestamp());
        return eventFact;
    }

    private Event buildEvent(ExperimentEventFact eventFact) {
        Event event = new Event();
        event.setEventId(eventFact.getEventId());
        event.setExperimentId(eventFact.getExperimentId());
        event.setUserId(eventFact.getVisitorId());
        event.setGroupId(eventFact.getGroupId());
        event.setEventType(eventFact.getEventType());
        event.setEventName(eventFact.getEventName());
        event.setProperties(eventFact.getProperties());
        event.setTimestamp(eventFact.getEventTime());
        return event;
    }

    private String normalizeEventType(String eventType) {
        return eventType == null ? null : eventType.trim().toUpperCase();
    }

    private boolean shouldUpdateMabReward(String experimentId, String eventType) {
        if (Event.EVENT_TYPE_CONVERT.equals(eventType)) {
            return true;
        }

        ExperimentMetadata metadata = configService.getExperimentConfig(experimentId);
        if (metadata == null || metadata.getMetricDefinitions() == null) {
            return false;
        }

        for (MetricDefinition metricDefinition : metadata.getMetricDefinitions()) {
            if (!Boolean.TRUE.equals(metricDefinition.getPrimaryMetric())) {
                continue;
            }
            return Objects.equals(metricDefinition.getNumeratorEventType(), eventType);
        }

        return false;
    }

    private String resolveClientEventId(Map<String, Object> properties) {
        if (properties == null || !properties.containsKey("clientEventId")) {
            return null;
        }
        return String.valueOf(properties.get("clientEventId"));
    }

    private String resolveExposureScene(Map<String, Object> properties) {
        if (properties == null || !properties.containsKey("scene")) {
            return null;
        }
        return String.valueOf(properties.get("scene"));
    }

    private String buildExposureIdempotencyKey(String experimentId, String visitorId, String groupId) {
        return experimentId + ":" + visitorId + ":" + groupId;
    }
}
