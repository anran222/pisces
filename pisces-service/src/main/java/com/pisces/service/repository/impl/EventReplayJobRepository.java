package com.pisces.service.repository.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.pisces.service.entity.EventReplayJobEntity;
import com.pisces.service.event.EventReplayJobRecord;
import com.pisces.service.mapper.EventReplayJobMapper;
import com.pisces.service.util.JsonUtil;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * 事件管道重放任务仓库实现
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/30 10:18
 */
@Repository
@AllArgsConstructor
public class EventReplayJobRepository implements com.pisces.service.repository.EventReplayJobRepository {

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    private final EventReplayJobMapper eventReplayJobMapper;

    private final JsonUtil jsonUtil;

    @Override
    public int expireStaleRunningJobs(String experimentId, LocalDateTime staleBefore, LocalDateTime finishedAt,
                                      String errorMessage) {
        return eventReplayJobMapper.expireStaleRunningJobs(experimentId, staleBefore, finishedAt, errorMessage);
    }

    @Override
    public boolean createRunningJob(EventReplayJobRecord record) {
        return eventReplayJobMapper.insertRunningJob(buildEntity(record)) > 0;
    }

    @Override
    public List<EventReplayJobRecord> listRecentByExperimentId(String experimentId, int limit) {
        return eventReplayJobMapper.selectRecentByExperimentId(experimentId, limit).stream()
                .map(this::buildRecord)
                .toList();
    }

    @Override
    public EventReplayJobRecord findByExperimentIdAndReplayJobId(String experimentId, String replayJobId) {
        EventReplayJobEntity entity =
                eventReplayJobMapper.selectByExperimentIdAndReplayJobId(experimentId, replayJobId);
        return entity == null ? null : buildRecord(entity);
    }

    @Override
    public boolean updateProgress(String replayJobId, long affectedCount, long eventCount, long exposureCount,
                                  long groupCount, long mabRewardCount) {
        return eventReplayJobMapper.updateProgress(replayJobId, affectedCount, eventCount, exposureCount, groupCount,
                mabRewardCount) > 0;
    }

    @Override
    public boolean markSucceeded(String replayJobId, long affectedCount, long eventCount, long exposureCount,
                                 long groupCount, long mabRewardCount, LocalDateTime finishedAt) {
        return eventReplayJobMapper.markSucceeded(replayJobId, affectedCount, eventCount, exposureCount, groupCount,
                mabRewardCount, finishedAt) > 0;
    }

    @Override
    public boolean markFailed(String replayJobId, String errorMessage, LocalDateTime finishedAt) {
        return eventReplayJobMapper.markFailed(replayJobId, errorMessage, finishedAt) > 0;
    }

    @Override
    public boolean requestCancellation(String replayJobId, String errorMessage) {
        return eventReplayJobMapper.requestCancellation(replayJobId, errorMessage) > 0;
    }

    @Override
    public boolean markCancelled(String replayJobId, String errorMessage, LocalDateTime finishedAt) {
        return eventReplayJobMapper.markCancelled(replayJobId, errorMessage, finishedAt) > 0;
    }

    private EventReplayJobEntity buildEntity(EventReplayJobRecord record) {
        EventReplayJobEntity entity = new EventReplayJobEntity();
        entity.setReplayJobId(record.getReplayJobId());
        entity.setExperimentId(record.getExperimentId());
        entity.setOperator(record.getOperator());
        entity.setJobStatus(record.getJobStatus());
        entity.setActiveKey(record.getActiveKey());
        entity.setReplayMode(record.getReplayMode());
        entity.setScopeStartTime(record.getScopeStartTime());
        entity.setScopeEndTime(record.getScopeEndTime());
        entity.setEventTypesJson(writeEventTypes(record.getEventTypes()));
        entity.setIncludeEvents(record.getIncludeEvents());
        entity.setIncludeExposures(record.getIncludeExposures());
        entity.setFullDerivedReplay(record.getFullDerivedReplay());
        entity.setPlannedAffectedCount(record.getPlannedAffectedCount());
        entity.setPlannedEventCount(record.getPlannedEventCount());
        entity.setPlannedExposureCount(record.getPlannedExposureCount());
        entity.setPlannedGroupCount(record.getPlannedGroupCount());
        entity.setAffectedCount(record.getAffectedCount());
        entity.setEventCount(record.getEventCount());
        entity.setExposureCount(record.getExposureCount());
        entity.setGroupCount(record.getGroupCount());
        entity.setMabRewardCount(record.getMabRewardCount());
        entity.setErrorMessage(record.getErrorMessage());
        entity.setStartedAt(record.getStartedAt());
        entity.setFinishedAt(record.getFinishedAt());
        return entity;
    }

    private EventReplayJobRecord buildRecord(EventReplayJobEntity entity) {
        EventReplayJobRecord record = new EventReplayJobRecord();
        record.setReplayJobId(entity.getReplayJobId());
        record.setExperimentId(entity.getExperimentId());
        record.setOperator(entity.getOperator());
        record.setJobStatus(entity.getJobStatus());
        record.setActiveKey(entity.getActiveKey());
        record.setReplayMode(entity.getReplayMode());
        record.setScopeStartTime(entity.getScopeStartTime());
        record.setScopeEndTime(entity.getScopeEndTime());
        record.setEventTypes(readEventTypes(entity.getEventTypesJson()));
        record.setIncludeEvents(entity.getIncludeEvents());
        record.setIncludeExposures(entity.getIncludeExposures());
        record.setFullDerivedReplay(entity.getFullDerivedReplay());
        record.setPlannedAffectedCount(entity.getPlannedAffectedCount());
        record.setPlannedEventCount(entity.getPlannedEventCount());
        record.setPlannedExposureCount(entity.getPlannedExposureCount());
        record.setPlannedGroupCount(entity.getPlannedGroupCount());
        record.setAffectedCount(entity.getAffectedCount());
        record.setEventCount(entity.getEventCount());
        record.setExposureCount(entity.getExposureCount());
        record.setGroupCount(entity.getGroupCount());
        record.setMabRewardCount(entity.getMabRewardCount());
        record.setErrorMessage(entity.getErrorMessage());
        record.setStartedAt(entity.getStartedAt());
        record.setFinishedAt(entity.getFinishedAt());
        return record;
    }

    private String writeEventTypes(List<String> eventTypes) {
        if (eventTypes == null || eventTypes.isEmpty()) {
            return null;
        }
        return jsonUtil.toJson(eventTypes);
    }

    private List<String> readEventTypes(String json) {
        if (!StringUtils.hasText(json)) {
            return Collections.emptyList();
        }
        return jsonUtil.toObject(json, STRING_LIST_TYPE);
    }
}
