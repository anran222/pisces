package com.pisces.service.event;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 事件管道重放任务记录
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/30 10:18
 */
@Data
public class EventReplayJobRecord {

    public static final String STATUS_RUNNING = "RUNNING";

    public static final String STATUS_CANCEL_REQUESTED = "CANCEL_REQUESTED";

    public static final String STATUS_SUCCEEDED = "SUCCEEDED";

    public static final String STATUS_FAILED = "FAILED";

    public static final String STATUS_CANCELLED = "CANCELLED";

    private String replayJobId;

    private String experimentId;

    private String operator;

    private String jobStatus;

    private String activeKey;

    private String replayMode;

    private LocalDateTime scopeStartTime;

    private LocalDateTime scopeEndTime;

    private List<String> eventTypes;

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
}
