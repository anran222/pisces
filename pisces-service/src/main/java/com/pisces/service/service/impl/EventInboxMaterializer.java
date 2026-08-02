package com.pisces.service.service.impl;

import com.pisces.common.model.Event;
import com.pisces.common.model.ExperimentEventFact;
import com.pisces.common.model.ExperimentExposure;
import com.pisces.common.model.ExperimentMetadata;
import com.pisces.common.model.MetricDefinition;
import com.pisces.service.event.EventMaterializationRecord;
import com.pisces.service.event.EventInboxConstants;
import com.pisces.service.event.EventPipelineRebuildResult;
import com.pisces.service.event.EventInboxRecord;
import com.pisces.service.event.EventReplayProgressReporter;
import com.pisces.service.repository.ExperimentEventRepository;
import com.pisces.service.repository.ExperimentExposureRepository;
import com.pisces.service.repository.EventMaterializationRepository;
import com.pisces.service.service.ConfigService;
import com.pisces.service.service.MultiArmedBanditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 事件收件箱物化器
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/1 10:26
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventInboxMaterializer {

    private static final String EVENT_STORE_PREFIX = "pisces:event:store:";
    private static final String EVENT_COUNTER_PREFIX = "pisces:event:counter:";
    private static final String VISITOR_SET_PREFIX = "pisces:visitor:set:";
    private static final String EXPOSURE_STORE_PREFIX = "pisces:exposure:store:";
    private static final String EXPOSURE_SET_PREFIX = "pisces:exposure:set:";
    private static final String MAB_OBSERVATION_ID_PROPERTY = "mabObservationId";
    private static final long DATA_EXPIRE_DAYS = 90L;

    private final ExperimentEventRepository experimentEventRepository;

    private final ExperimentExposureRepository experimentExposureRepository;

    private final EventMaterializationRepository eventMaterializationRepository;

    private final ConfigService configService;

    private final RedisTemplate<String, Object> redisTemplate;

    @Value("${pisces.event-pipeline.replay.batch-size:1000}")
    private int eventReplayBatchSize;

    @Autowired(required = false)
    private MultiArmedBanditService mabService;

    public void materialize(EventInboxRecord record) {
        if (EventInboxConstants.KIND_EVENT.equals(record.getEventKind())) {
            materializeEvent(record);
            return;
        }
        if (EventInboxConstants.KIND_EXPOSURE.equals(record.getEventKind())) {
            materializeExposure(record);
            return;
        }
        throw new IllegalArgumentException("Unsupported event kind: " + record.getEventKind());
    }

    public EventPipelineRebuildResult rebuildDerivedData(String experimentId) {
        return rebuildDerivedData(experimentId, null);
    }

    public EventPipelineRebuildResult rebuildDerivedData(String experimentId, String replayJobId) {
        return rebuildDerivedData(experimentId, replayJobId, EventReplayProgressReporter.NOOP);
    }

    public EventPipelineRebuildResult rebuildDerivedData(String experimentId, String replayJobId,
                                                         EventReplayProgressReporter progressReporter) {
        EventPipelineRebuildResult result = new EventPipelineRebuildResult();
        ExperimentMetadata metadata = configService.getExperimentConfig(experimentId);
        if (metadata == null || metadata.getGroups() == null || metadata.getGroups().isEmpty()) {
            log.warn("事件管道派生数据重建跳过，实验配置不存在: experimentId={}", experimentId);
            return result;
        }

        if (mabService != null) {
            mabService.resetMABData(experimentId);
        }

        int batchSize = replayBatchSize();
        for (String groupId : metadata.getGroups().keySet()) {
            deleteDerivedData(experimentId, groupId);
            result.setGroupCount(result.getGroupCount() + 1);
            if (!reportProgress(progressReporter, result)) {
                return result;
            }
            if (!rebuildEvents(experimentId, groupId, replayJobId, batchSize, result, progressReporter)) {
                return result;
            }
            if (!rebuildExposures(experimentId, groupId, replayJobId, batchSize, result, progressReporter)) {
                return result;
            }
        }
        return result;
    }

    public EventPipelineRebuildResult repairUnmaterializedDerivedData(String experimentId,
                                                                      LocalDateTime startTime,
                                                                      LocalDateTime endTime,
                                                                      List<String> eventTypes,
                                                                      boolean includeEvents,
                                                                      boolean includeExposures,
                                                                      String replayJobId) {
        return repairUnmaterializedDerivedData(experimentId, startTime, endTime, eventTypes, includeEvents,
                includeExposures, replayJobId, EventReplayProgressReporter.NOOP);
    }

    public EventPipelineRebuildResult repairUnmaterializedDerivedData(String experimentId,
                                                                      LocalDateTime startTime,
                                                                      LocalDateTime endTime,
                                                                      List<String> eventTypes,
                                                                      boolean includeEvents,
                                                                      boolean includeExposures,
                                                                      String replayJobId,
                                                                      EventReplayProgressReporter progressReporter) {
        EventPipelineRebuildResult result = new EventPipelineRebuildResult();
        ExperimentMetadata metadata = configService.getExperimentConfig(experimentId);
        if (metadata == null || metadata.getGroups() == null || metadata.getGroups().isEmpty()) {
            log.warn("事件管道局部补物化跳过，实验配置不存在: experimentId={}", experimentId);
            return result;
        }

        List<String> normalizedEventTypes = eventTypes == null ? List.of() : eventTypes;
        int batchSize = replayBatchSize();
        for (String groupId : metadata.getGroups().keySet()) {
            GroupProcessingState groupState = new GroupProcessingState();
            if (includeEvents) {
                boolean shouldContinue = repairUnmaterializedEvents(experimentId, groupId, startTime, endTime,
                        normalizedEventTypes, replayJobId, batchSize, result, groupState, progressReporter);
                if (!shouldContinue) {
                    return result;
                }
            }
            if (includeExposures) {
                boolean shouldContinue = repairUnmaterializedExposures(experimentId, groupId, startTime, endTime,
                        replayJobId, batchSize, result, groupState, progressReporter);
                if (!shouldContinue) {
                    return result;
                }
            }
        }
        return result;
    }

    public EventPipelineRebuildResult copyReplayDerivedData(String experimentId,
                                                            LocalDateTime startTime,
                                                            LocalDateTime endTime,
                                                            List<String> eventTypes,
                                                            boolean includeEvents,
                                                            boolean includeExposures,
                                                            String replayJobId) {
        return copyReplayDerivedData(experimentId, startTime, endTime, eventTypes, includeEvents, includeExposures,
                replayJobId, EventReplayProgressReporter.NOOP);
    }

    public EventPipelineRebuildResult copyReplayDerivedData(String experimentId,
                                                            LocalDateTime startTime,
                                                            LocalDateTime endTime,
                                                            List<String> eventTypes,
                                                            boolean includeEvents,
                                                            boolean includeExposures,
                                                            String replayJobId,
                                                            EventReplayProgressReporter progressReporter) {
        EventPipelineRebuildResult result = new EventPipelineRebuildResult();
        ExperimentMetadata metadata = configService.getExperimentConfig(experimentId);
        if (metadata == null || metadata.getGroups() == null || metadata.getGroups().isEmpty()) {
            log.warn("事件管道复制型重放跳过，实验配置不存在: experimentId={}", experimentId);
            return result;
        }

        List<String> normalizedEventTypes = eventTypes == null ? List.of() : eventTypes;
        int batchSize = replayBatchSize();
        for (String groupId : metadata.getGroups().keySet()) {
            GroupProcessingState groupState = new GroupProcessingState();
            if (includeEvents) {
                boolean shouldContinue = copyReplayEvents(experimentId, groupId, startTime, endTime,
                        normalizedEventTypes, replayJobId, batchSize, result, groupState, progressReporter);
                if (!shouldContinue) {
                    return result;
                }
            }
            if (includeExposures) {
                boolean shouldContinue = copyReplayExposures(experimentId, groupId, startTime, endTime, replayJobId,
                        batchSize, result, groupState, progressReporter);
                if (!shouldContinue) {
                    return result;
                }
            }
        }
        return result;
    }

    private boolean rebuildEvents(String experimentId, String groupId, String replayJobId, int batchSize,
                                  EventPipelineRebuildResult result,
                                  EventReplayProgressReporter progressReporter) {
        long offset = 0L;
        while (true) {
            List<ExperimentEventFact> eventFacts = experimentEventRepository.listByExperimentIdAndGroupIdBatch(
                    experimentId, groupId, offset, batchSize);
            if (eventFacts.isEmpty()) {
                return true;
            }
            for (ExperimentEventFact eventFact : eventFacts) {
                boolean mabRewardUpdated = materializeEventDerivatives(eventFact);
                recordMaterialization(EventMaterializationRecord.FACT_KIND_EVENT, eventFact.getEventId(),
                        eventFact.getExperimentId(), eventFact.getGroupId(), eventFact.getEventType(),
                        EventMaterializationRecord.SOURCE_REPLAY_FULL, replayJobId);
                result.setEventCount(result.getEventCount() + 1);
                if (mabRewardUpdated) {
                    result.setMabRewardCount(result.getMabRewardCount() + 1);
                }
            }
            if (!reportProgress(progressReporter, result)) {
                return false;
            }
            if (eventFacts.size() < batchSize) {
                return true;
            }
            offset += eventFacts.size();
        }
    }

    private boolean rebuildExposures(String experimentId, String groupId, String replayJobId, int batchSize,
                                     EventPipelineRebuildResult result,
                                     EventReplayProgressReporter progressReporter) {
        long offset = 0L;
        while (true) {
            List<ExperimentExposure> exposures = experimentExposureRepository.listByExperimentIdAndGroupIdBatch(
                    experimentId, groupId, offset, batchSize);
            if (exposures.isEmpty()) {
                return true;
            }
            for (ExperimentExposure exposure : exposures) {
                materializeExposureDerivatives(exposure);
                recordMaterialization(EventMaterializationRecord.FACT_KIND_EXPOSURE, exposure.getExposureId(),
                        exposure.getExperimentId(), exposure.getGroupId(), null,
                        EventMaterializationRecord.SOURCE_REPLAY_FULL, replayJobId);
                result.setExposureCount(result.getExposureCount() + 1);
            }
            if (!reportProgress(progressReporter, result)) {
                return false;
            }
            if (exposures.size() < batchSize) {
                return true;
            }
            offset += exposures.size();
        }
    }

    private boolean copyReplayEvents(String experimentId, String groupId, LocalDateTime startTime,
                                     LocalDateTime endTime, List<String> eventTypes, String replayJobId,
                                     int batchSize, EventPipelineRebuildResult result,
                                     GroupProcessingState groupState,
                                     EventReplayProgressReporter progressReporter) {
        long offset = 0L;
        while (true) {
            List<ExperimentEventFact> eventFacts = experimentEventRepository.listByReplayScopeBatch(
                    experimentId, groupId, startTime, endTime, eventTypes, offset, batchSize);
            if (eventFacts.isEmpty()) {
                return true;
            }
            for (ExperimentEventFact eventFact : eventFacts) {
                boolean mabRewardUpdated = false;
                if (!isEventAlreadyInDerivedStore(eventFact)) {
                    mabRewardUpdated = materializeEventDerivatives(eventFact);
                }
                recordMaterialization(EventMaterializationRecord.FACT_KIND_EVENT, eventFact.getEventId(),
                        eventFact.getExperimentId(), eventFact.getGroupId(), eventFact.getEventType(),
                        EventMaterializationRecord.SOURCE_REPLAY_COPY, replayJobId);
                result.setEventCount(result.getEventCount() + 1);
                if (mabRewardUpdated) {
                    result.setMabRewardCount(result.getMabRewardCount() + 1);
                }
            }
            markGroupProcessed(result, groupState);
            if (!reportProgress(progressReporter, result)) {
                return false;
            }
            if (eventFacts.size() < batchSize) {
                return true;
            }
            offset += eventFacts.size();
        }
    }

    private boolean copyReplayExposures(String experimentId, String groupId, LocalDateTime startTime,
                                        LocalDateTime endTime, String replayJobId, int batchSize,
                                        EventPipelineRebuildResult result, GroupProcessingState groupState,
                                        EventReplayProgressReporter progressReporter) {
        long offset = 0L;
        while (true) {
            List<ExperimentExposure> exposures = experimentExposureRepository.listByReplayScopeBatch(
                    experimentId, groupId, startTime, endTime, offset, batchSize);
            if (exposures.isEmpty()) {
                return true;
            }
            for (ExperimentExposure exposure : exposures) {
                if (!isExposureAlreadyInDerivedStore(exposure)) {
                    materializeExposureDerivatives(exposure);
                }
                recordMaterialization(EventMaterializationRecord.FACT_KIND_EXPOSURE, exposure.getExposureId(),
                        exposure.getExperimentId(), exposure.getGroupId(), null,
                        EventMaterializationRecord.SOURCE_REPLAY_COPY, replayJobId);
                result.setExposureCount(result.getExposureCount() + 1);
            }
            markGroupProcessed(result, groupState);
            if (!reportProgress(progressReporter, result)) {
                return false;
            }
            if (exposures.size() < batchSize) {
                return true;
            }
            offset += exposures.size();
        }
    }

    private boolean repairUnmaterializedEvents(String experimentId, String groupId, LocalDateTime startTime,
                                               LocalDateTime endTime, List<String> eventTypes, String replayJobId,
                                               int batchSize, EventPipelineRebuildResult result,
                                               GroupProcessingState groupState,
                                               EventReplayProgressReporter progressReporter) {
        while (true) {
            List<ExperimentEventFact> eventFacts = experimentEventRepository.listUnmaterializedByReplayScopeBatch(
                    experimentId, groupId, startTime, endTime, eventTypes, 0L, batchSize);
            if (eventFacts.isEmpty()) {
                return true;
            }
            for (ExperimentEventFact eventFact : eventFacts) {
                boolean mabRewardUpdated = false;
                if (!isEventAlreadyInDerivedStore(eventFact)) {
                    mabRewardUpdated = materializeEventDerivatives(eventFact);
                }
                recordMaterialization(EventMaterializationRecord.FACT_KIND_EVENT, eventFact.getEventId(),
                        eventFact.getExperimentId(), eventFact.getGroupId(), eventFact.getEventType(),
                        EventMaterializationRecord.SOURCE_REPAIR_MATERIALIZATION, replayJobId);
                result.setEventCount(result.getEventCount() + 1);
                if (mabRewardUpdated) {
                    result.setMabRewardCount(result.getMabRewardCount() + 1);
                }
            }
            markGroupProcessed(result, groupState);
            if (!reportProgress(progressReporter, result)) {
                return false;
            }
            if (eventFacts.size() < batchSize) {
                return true;
            }
        }
    }

    private boolean repairUnmaterializedExposures(String experimentId, String groupId, LocalDateTime startTime,
                                                  LocalDateTime endTime, String replayJobId, int batchSize,
                                                  EventPipelineRebuildResult result,
                                                  GroupProcessingState groupState,
                                                  EventReplayProgressReporter progressReporter) {
        while (true) {
            List<ExperimentExposure> exposures = experimentExposureRepository.listUnmaterializedByReplayScopeBatch(
                    experimentId, groupId, startTime, endTime, 0L, batchSize);
            if (exposures.isEmpty()) {
                return true;
            }
            for (ExperimentExposure exposure : exposures) {
                if (!isExposureAlreadyInDerivedStore(exposure)) {
                    materializeExposureDerivatives(exposure);
                }
                recordMaterialization(EventMaterializationRecord.FACT_KIND_EXPOSURE, exposure.getExposureId(),
                        exposure.getExperimentId(), exposure.getGroupId(), null,
                        EventMaterializationRecord.SOURCE_REPAIR_MATERIALIZATION, replayJobId);
                result.setExposureCount(result.getExposureCount() + 1);
            }
            markGroupProcessed(result, groupState);
            if (!reportProgress(progressReporter, result)) {
                return false;
            }
            if (exposures.size() < batchSize) {
                return true;
            }
        }
    }

    private int replayBatchSize() {
        return Math.max(1, eventReplayBatchSize);
    }

    private boolean reportProgress(EventReplayProgressReporter progressReporter, EventPipelineRebuildResult result) {
        EventReplayProgressReporter reporter = progressReporter == null
                ? EventReplayProgressReporter.NOOP
                : progressReporter;
        return reporter.report(result);
    }

    private void markGroupProcessed(EventPipelineRebuildResult result, GroupProcessingState groupState) {
        if (groupState.groupCounted) {
            return;
        }
        result.setGroupCount(result.getGroupCount() + 1);
        groupState.groupCounted = true;
    }

    private void materializeEvent(EventInboxRecord record) {
        ExperimentEventFact eventFact = buildExperimentEventFact(record);
        boolean inserted = experimentEventRepository.saveIfAbsent(eventFact);
        ExperimentEventFact materializationFact = inserted ? eventFact : resolvePersistedEventFact(eventFact);
        if (!inserted && isFactAlreadyMaterialized(EventMaterializationRecord.FACT_KIND_EVENT,
                materializationFact.getEventId())) {
            log.debug("事件事实已存在且派生已物化，跳过派生更新: inboxId={}, experimentId={}, clientEventId={}",
                    record.getInboxId(), record.getExperimentId(), record.getIdempotencyKey());
            return;
        }

        materializeEventDerivatives(materializationFact);
        recordMaterialization(EventMaterializationRecord.FACT_KIND_EVENT, materializationFact.getEventId(),
                materializationFact.getExperimentId(), materializationFact.getGroupId(),
                materializationFact.getEventType(),
                EventMaterializationRecord.SOURCE_INBOX, null);
    }

    private boolean materializeEventDerivatives(ExperimentEventFact eventFact) {
        Event event = buildEvent(eventFact);
        String eventStoreKey = EVENT_STORE_PREFIX + eventFact.getExperimentId() + ":" + eventFact.getGroupId();
        redisTemplate.opsForList().rightPush(eventStoreKey, event);
        redisTemplate.expire(eventStoreKey, DATA_EXPIRE_DAYS, TimeUnit.DAYS);

        String counterKey = EVENT_COUNTER_PREFIX + eventFact.getExperimentId() + ":" + eventFact.getGroupId();
        redisTemplate.opsForHash().increment(counterKey, eventFact.getEventType(), 1);
        redisTemplate.expire(counterKey, DATA_EXPIRE_DAYS, TimeUnit.DAYS);

        String visitorSetKey = VISITOR_SET_PREFIX + eventFact.getExperimentId() + ":" + eventFact.getGroupId();
        redisTemplate.opsForSet().add(visitorSetKey, eventFact.getVisitorId());
        redisTemplate.expire(visitorSetKey, DATA_EXPIRE_DAYS, TimeUnit.DAYS);

        return updateMabRewardIfNecessary(eventFact);
    }

    private void materializeExposure(EventInboxRecord record) {
        ExperimentExposure exposure = buildExperimentExposure(record);
        boolean inserted = experimentExposureRepository.saveIfAbsent(exposure);
        ExperimentExposure materializationExposure = inserted ? exposure : resolvePersistedExposure(exposure);
        if (!inserted && isFactAlreadyMaterialized(EventMaterializationRecord.FACT_KIND_EXPOSURE,
                materializationExposure.getExposureId())) {
            log.debug("曝光事实已存在且派生已物化，跳过派生更新: inboxId={}, experimentId={}, visitorId={}",
                    record.getInboxId(), record.getExperimentId(), record.getVisitorId());
            return;
        }

        materializeExposureDerivatives(materializationExposure);
        recordMaterialization(EventMaterializationRecord.FACT_KIND_EXPOSURE, materializationExposure.getExposureId(),
                materializationExposure.getExperimentId(), materializationExposure.getGroupId(), null,
                EventMaterializationRecord.SOURCE_INBOX, null);
    }

    private ExperimentEventFact resolvePersistedEventFact(ExperimentEventFact fallbackFact) {
        if (!StringUtils.hasText(fallbackFact.getClientEventId())) {
            return fallbackFact;
        }
        ExperimentEventFact persistedFact = experimentEventRepository.findByExperimentIdAndClientEventId(
                fallbackFact.getExperimentId(), fallbackFact.getClientEventId());
        return persistedFact == null ? fallbackFact : persistedFact;
    }

    private ExperimentExposure resolvePersistedExposure(ExperimentExposure fallbackExposure) {
        if (!StringUtils.hasText(fallbackExposure.getIdempotencyKey())) {
            return fallbackExposure;
        }
        ExperimentExposure persistedExposure = experimentExposureRepository.findByIdempotencyKey(
                fallbackExposure.getIdempotencyKey());
        return persistedExposure == null ? fallbackExposure : persistedExposure;
    }

    private boolean isFactAlreadyMaterialized(String factKind, String factId) {
        return eventMaterializationRepository.exists(factKind, factId);
    }

    private boolean isEventAlreadyInDerivedStore(ExperimentEventFact eventFact) {
        String eventStoreKey = EVENT_STORE_PREFIX + eventFact.getExperimentId() + ":" + eventFact.getGroupId();
        List<Object> existingEvents = redisTemplate.opsForList().range(eventStoreKey, 0, -1);
        if (existingEvents == null || existingEvents.isEmpty()) {
            return false;
        }
        return existingEvents.stream().anyMatch(value -> hasFactId(value, "eventId", eventFact.getEventId()));
    }

    private boolean isExposureAlreadyInDerivedStore(ExperimentExposure exposure) {
        String exposureStoreKey = EXPOSURE_STORE_PREFIX + exposure.getExperimentId() + ":" + exposure.getGroupId();
        List<Object> existingExposures = redisTemplate.opsForList().range(exposureStoreKey, 0, -1);
        if (existingExposures == null || existingExposures.isEmpty()) {
            return false;
        }
        return existingExposures.stream()
                .anyMatch(value -> hasFactId(value, "exposureId", exposure.getExposureId()));
    }

    private boolean hasFactId(Object value, String factIdField, String factId) {
        if (!StringUtils.hasText(factId) || value == null) {
            return false;
        }
        if (value instanceof Event event) {
            return "eventId".equals(factIdField) && factId.equals(event.getEventId());
        }
        if (value instanceof ExperimentExposure exposure) {
            return "exposureId".equals(factIdField) && factId.equals(exposure.getExposureId());
        }
        if (value instanceof Map<?, ?> valueMap) {
            Object mappedFactId = valueMap.get(factIdField);
            return factId.equals(mappedFactId);
        }
        return false;
    }

    private void recordMaterialization(String factKind, String factId, String experimentId, String groupId,
                                       String eventType, String source, String replayJobId) {
        try {
            EventMaterializationRecord record = new EventMaterializationRecord();
            record.setFactKind(factKind);
            record.setFactId(factId);
            record.setExperimentId(experimentId);
            record.setGroupId(groupId);
            record.setEventType(eventType);
            record.setMaterializationSource(source);
            record.setReplayJobId(replayJobId);
            record.setMaterializedAt(LocalDateTime.now());
            eventMaterializationRepository.saveOrRefresh(record);
        } catch (RuntimeException exception) {
            log.warn("记录事件事实派生物化账本失败: factKind={}, factId={}, experimentId={}",
                    factKind, factId, experimentId, exception);
        }
    }

    private void materializeExposureDerivatives(ExperimentExposure exposure) {
        String exposureStoreKey = EXPOSURE_STORE_PREFIX + exposure.getExperimentId() + ":" + exposure.getGroupId();
        redisTemplate.opsForList().rightPush(exposureStoreKey, exposure);
        redisTemplate.expire(exposureStoreKey, DATA_EXPIRE_DAYS, TimeUnit.DAYS);

        String exposureSetKey = EXPOSURE_SET_PREFIX + exposure.getExperimentId() + ":" + exposure.getGroupId();
        redisTemplate.opsForSet().add(exposureSetKey, exposure.getVisitorId());
        redisTemplate.expire(exposureSetKey, DATA_EXPIRE_DAYS, TimeUnit.DAYS);
    }

    private ExperimentEventFact buildExperimentEventFact(EventInboxRecord record) {
        ExperimentEventFact eventFact = new ExperimentEventFact();
        eventFact.setEventId(buildFactId("evt_", record.getInboxId()));
        eventFact.setExperimentId(record.getExperimentId());
        eventFact.setVisitorId(record.getVisitorId());
        eventFact.setGroupId(record.getGroupId());
        eventFact.setEventType(record.getEventType());
        eventFact.setEventName(record.getEventName());
        eventFact.setClientEventId(record.getClientEventId());
        eventFact.setProperties(record.getProperties());
        eventFact.setEventTime(record.getEventTime());
        return eventFact;
    }

    private ExperimentExposure buildExperimentExposure(EventInboxRecord record) {
        ExperimentExposure exposure = new ExperimentExposure();
        exposure.setExposureId(buildFactId("expo_", record.getInboxId()));
        exposure.setExperimentId(record.getExperimentId());
        exposure.setVisitorId(record.getVisitorId());
        exposure.setGroupId(record.getGroupId());
        exposure.setScene(record.getScene());
        exposure.setProperties(record.getProperties());
        exposure.setExposedAt(record.getEventTime());
        exposure.setIdempotencyKey(buildExposureIdempotencyKey(record));
        return exposure;
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

    private boolean updateMabRewardIfNecessary(ExperimentEventFact eventFact) {
        if (mabService == null) {
            return false;
        }
        MetricDefinition rewardMetric = resolveMabRewardMetric(eventFact.getExperimentId());
        boolean success;
        if (isEventCountRateMetric(rewardMetric)) {
            boolean numeratorEvent = equalsEventType(eventFact.getEventType(), rewardMetric.getNumeratorEventType());
            boolean denominatorEvent = equalsEventType(eventFact.getEventType(), rewardMetric.getDenominatorEventType());
            if (!numeratorEvent && !denominatorEvent) {
                return false;
            }
            success = numeratorEvent ? resolveMabSuccess(eventFact.getProperties()) : false;
        } else if (Event.EVENT_TYPE_CONVERT.equals(eventFact.getEventType())
                || isMabRewardNumeratorEvent(rewardMetric, eventFact.getEventType())) {
            success = resolveMabSuccess(eventFact.getProperties());
        } else {
            return false;
        }

        String observationId = resolveMabObservationId(eventFact);
        boolean updated = mabService.recordRewardObservation(eventFact.getExperimentId(), eventFact.getGroupId(),
                observationId, success);
        log.debug("更新MAB奖励观测: 实验={}, 组={}, observationId={}, 成功={}, updated={}",
                eventFact.getExperimentId(), eventFact.getGroupId(), observationId, success, updated);
        return updated;
    }

    private MetricDefinition resolveMabRewardMetric(String experimentId) {
        ExperimentMetadata metadata = configService.getExperimentConfig(experimentId);
        if (metadata == null || metadata.getMetricDefinitions() == null) {
            return null;
        }
        MetricDefinition firstRateEventCountMetric = null;
        for (MetricDefinition metricDefinition : metadata.getMetricDefinitions()) {
            if (Boolean.TRUE.equals(metricDefinition.getPrimaryMetric())) {
                return metricDefinition;
            }
            if (firstRateEventCountMetric == null && isEventCountRateMetric(metricDefinition)) {
                firstRateEventCountMetric = metricDefinition;
            }
        }
        return firstRateEventCountMetric;
    }

    private boolean isEventCountRateMetric(MetricDefinition metricDefinition) {
        return metricDefinition != null
                && MetricDefinition.AggregationType.RATE.equals(metricDefinition.getAggregationType())
                && MetricDefinition.DenominatorType.EVENT_COUNT.equals(metricDefinition.getDenominatorType())
                && StringUtils.hasText(metricDefinition.getNumeratorEventType())
                && StringUtils.hasText(metricDefinition.getDenominatorEventType());
    }

    private boolean isMabRewardNumeratorEvent(MetricDefinition metricDefinition, String eventType) {
        return metricDefinition != null && equalsEventType(eventType, metricDefinition.getNumeratorEventType());
    }

    private boolean equalsEventType(String actualEventType, String expectedEventType) {
        return StringUtils.hasText(actualEventType)
                && StringUtils.hasText(expectedEventType)
                && actualEventType.trim().equalsIgnoreCase(expectedEventType.trim());
    }

    private boolean resolveMabSuccess(Map<String, Object> properties) {
        if (properties == null || !properties.containsKey("mabSuccess")) {
            return true;
        }
        Object flag = properties.get("mabSuccess");
        if (flag instanceof Boolean booleanFlag) {
            return booleanFlag;
        }
        return Boolean.parseBoolean(String.valueOf(flag));
    }

    private String resolveMabObservationId(ExperimentEventFact eventFact) {
        if (eventFact.getProperties() != null) {
            Object explicitObservationId = eventFact.getProperties().get(MAB_OBSERVATION_ID_PROPERTY);
            if (explicitObservationId != null && StringUtils.hasText(String.valueOf(explicitObservationId))) {
                return String.valueOf(explicitObservationId).trim();
            }
        }
        if (StringUtils.hasText(eventFact.getVisitorId())) {
            return eventFact.getVisitorId().trim();
        }
        if (StringUtils.hasText(eventFact.getClientEventId())) {
            return eventFact.getClientEventId().trim();
        }
        return eventFact.getEventId();
    }

    private String buildExposureIdempotencyKey(EventInboxRecord record) {
        return record.getExperimentId() + ":" + record.getVisitorId() + ":" + record.getGroupId();
    }

    private String buildFactId(String prefix, String inboxId) {
        String suffix = inboxId == null ? String.valueOf(System.currentTimeMillis()) : inboxId.replace("inbox_", "");
        String normalizedSuffix = suffix.length() > 56 ? suffix.substring(0, 56) : suffix;
        return prefix + normalizedSuffix;
    }

    private void deleteDerivedData(String experimentId, String groupId) {
        redisTemplate.delete(List.of(
                EVENT_STORE_PREFIX + experimentId + ":" + groupId,
                EVENT_COUNTER_PREFIX + experimentId + ":" + groupId,
                VISITOR_SET_PREFIX + experimentId + ":" + groupId,
                EXPOSURE_STORE_PREFIX + experimentId + ":" + groupId,
                EXPOSURE_SET_PREFIX + experimentId + ":" + groupId
        ));
    }

    private static class GroupProcessingState {
        private boolean groupCounted;
    }
}
