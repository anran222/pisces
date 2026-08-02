package com.pisces.service.service.impl;

import com.pisces.common.enums.ResponseCode;
import com.pisces.common.model.Event;
import com.pisces.common.model.ExperimentEventFact;
import com.pisces.common.model.ExperimentExposure;
import com.pisces.common.model.ExperimentMetadata;
import com.pisces.service.exception.BusinessException;
import com.pisces.service.event.EventInboxConstants;
import com.pisces.service.event.EventInboxRecord;
import com.pisces.service.repository.EventInboxRepository;
import com.pisces.service.repository.ExperimentAssignmentRepository;
import com.pisces.service.repository.ExperimentEventRepository;
import com.pisces.service.repository.ExperimentExposureRepository;
import com.pisces.service.security.ApiKeyContextHolder;
import com.pisces.service.service.ConfigService;
import com.pisces.service.service.DataService;
import com.pisces.service.service.IdentityService;
import com.pisces.service.service.TrafficService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 数据收集服务实现
 */
@Slf4j
@Service
public class DataServiceImpl implements DataService {
    
    @Autowired
    private TrafficService trafficService;

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
    private EventInboxRepository eventInboxRepository;
    
    /**
     * 上报事件（使用visitorId，可以是userId、设备ID、会话ID等）
     * properties 中可携带 clientEventId 字段用于客户端幂等去重
     */
    @Override
    public void reportEvent(String experimentId, String visitorId, String eventType,
                           String eventName, Map<String, Object> properties) {
        assertCanAccessExperiment(experimentId);
        String canonicalVisitorId = resolveCanonicalVisitorId(visitorId);
        String groupId = trafficService.getUserGroup(experimentId, canonicalVisitorId);
        String normalizedEventType = normalizeEventType(eventType);
        LocalDateTime eventTime = resolveEventTimestamp(properties);
        EventInboxRecord inboxRecord = buildEventInboxRecord(experimentId, canonicalVisitorId, groupId,
                normalizedEventType, eventName, properties, eventTime);
        if (groupId == null) {
            inboxRecord.setStatus(EventInboxConstants.STATUS_REJECTED);
            inboxRecord.setLastError("访客不在实验中");
            inboxRecord.setProcessedAt(eventTime);
            eventInboxRepository.saveIfAbsent(inboxRecord);
            log.warn("访客 {} 不在实验 {} 中", canonicalVisitorId, experimentId);
            return;
        }
        eventInboxRepository.saveIfAbsent(inboxRecord);
        log.debug("受理事件: 实验={}, 访客={}, 组={}, 事件={}",
                experimentId, canonicalVisitorId, groupId, normalizedEventType);
    }

    @Override
    public void reportExposure(String experimentId, String visitorId, Map<String, Object> properties) {
        assertCanAccessExperiment(experimentId);
        String canonicalVisitorId = resolveCanonicalVisitorId(visitorId);
        String groupId = trafficService.getUserGroup(experimentId, canonicalVisitorId);
        LocalDateTime exposedAt = resolveEventTimestamp(properties);
        EventInboxRecord inboxRecord = buildExposureInboxRecord(experimentId, canonicalVisitorId, groupId,
                properties, exposedAt);
        if (groupId == null) {
            inboxRecord.setStatus(EventInboxConstants.STATUS_REJECTED);
            inboxRecord.setLastError("访客不在实验中");
            inboxRecord.setProcessedAt(exposedAt);
            eventInboxRepository.saveIfAbsent(inboxRecord);
            log.warn("访客 {} 不在实验 {} 中，无法记录曝光", canonicalVisitorId, experimentId);
            return;
        }
        eventInboxRepository.saveIfAbsent(inboxRecord);
        log.debug("受理曝光: 实验={}, 访客={}, 组={}", experimentId, canonicalVisitorId, groupId);
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
        if (metadata != null) {
            ApiKeyContextHolder.assertCanAccess(metadata);
        }
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

    private void assertCanAccessExperiment(String experimentId) {
        ExperimentMetadata metadata = configService.getExperimentConfig(experimentId);
        if (metadata == null) {
            throw new BusinessException(ResponseCode.EXPERIMENT_NOT_FOUND);
        }
        ApiKeyContextHolder.assertCanAccess(metadata);
    }

    private EventInboxRecord buildEventInboxRecord(String experimentId, String visitorId, String groupId,
                                                   String eventType, String eventName,
                                                   Map<String, Object> properties, LocalDateTime eventTime) {
        String inboxId = buildInboxId();
        String clientEventId = resolveClientEventId(properties);
        EventInboxRecord record = buildBaseInboxRecord(experimentId, visitorId, groupId, properties, eventTime);
        record.setInboxId(inboxId);
        record.setEventKind(EventInboxConstants.KIND_EVENT);
        record.setEventType(eventType);
        record.setEventName(eventName);
        record.setClientEventId(clientEventId);
        record.setIdempotencyKey(buildEventIdempotencyKey(experimentId, clientEventId, inboxId));
        return record;
    }

    private EventInboxRecord buildExposureInboxRecord(String experimentId, String visitorId, String groupId,
                                                      Map<String, Object> properties, LocalDateTime exposedAt) {
        String inboxId = buildInboxId();
        EventInboxRecord record = buildBaseInboxRecord(experimentId, visitorId, groupId, properties, exposedAt);
        record.setInboxId(inboxId);
        record.setEventKind(EventInboxConstants.KIND_EXPOSURE);
        record.setScene(resolveExposureScene(properties));
        record.setIdempotencyKey(buildExposureInboxIdempotencyKey(experimentId, visitorId, groupId, inboxId));
        return record;
    }

    private EventInboxRecord buildBaseInboxRecord(String experimentId, String visitorId, String groupId,
                                                  Map<String, Object> properties, LocalDateTime eventTime) {
        EventInboxRecord record = new EventInboxRecord();
        record.setExperimentId(experimentId);
        record.setVisitorId(visitorId);
        record.setGroupId(groupId);
        record.setProperties(properties);
        record.setStatus(EventInboxConstants.STATUS_PENDING);
        record.setRetryCount(0);
        record.setNextRetryAt(LocalDateTime.now());
        record.setEventTime(eventTime);
        record.setAcceptedAt(LocalDateTime.now());
        return record;
    }

    private String buildInboxId() {
        return "inbox_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private String buildEventIdempotencyKey(String experimentId, String clientEventId, String inboxId) {
        if (StringUtils.hasText(clientEventId)) {
            return EventInboxConstants.KIND_EVENT + ":" + experimentId + ":" + clientEventId;
        }
        return EventInboxConstants.KIND_EVENT + ":" + experimentId + ":" + inboxId;
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

    private String resolveClientEventId(Map<String, Object> properties) {
        if (properties == null || !properties.containsKey("clientEventId")) {
            return null;
        }
        String clientEventId = String.valueOf(properties.get("clientEventId")).trim();
        return StringUtils.hasText(clientEventId) ? clientEventId : null;
    }

    private String resolveExposureScene(Map<String, Object> properties) {
        if (properties == null || !properties.containsKey("scene")) {
            return null;
        }
        return String.valueOf(properties.get("scene"));
    }

    private String buildExposureInboxIdempotencyKey(String experimentId, String visitorId, String groupId,
                                                    String inboxId) {
        if (StringUtils.hasText(groupId)) {
            return EventInboxConstants.KIND_EXPOSURE + ":" + experimentId + ":" + visitorId + ":" + groupId;
        }
        return EventInboxConstants.KIND_EXPOSURE + ":" + experimentId + ":" + visitorId + ":" + inboxId;
    }
}
