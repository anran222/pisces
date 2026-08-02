package com.pisces.service.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 事件管道重放任务实体
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/30 10:18
 */
@Data
public class EventReplayJobEntity {

    private Long id;

    private String replayJobId;

    private String experimentId;

    private String operator;

    private String jobStatus;

    private String activeKey;

    private String replayMode;

    private LocalDateTime scopeStartTime;

    private LocalDateTime scopeEndTime;

    private String eventTypesJson;

    private Boolean includeEvents;

    private Boolean includeExposures;

    private Boolean fullDerivedReplay;

    private Long plannedAffectedCount;

    private Long plannedEventCount;

    private Long plannedExposureCount;

    private Long plannedGroupCount;

    private Long affectedCount;

    private Long eventCount;

    private Long exposureCount;

    private Long groupCount;

    private Long mabRewardCount;

    private String errorMessage;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
