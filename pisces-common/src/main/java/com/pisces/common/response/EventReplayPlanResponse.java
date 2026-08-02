package com.pisces.common.response;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 事件重放计划响应
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/30 10:55
 */
@Data
public class EventReplayPlanResponse {

    /**
     * 实验ID
     */
    private String experimentId;

    /**
     * 计划开始时间，包含边界
     */
    private LocalDateTime startTime;

    /**
     * 计划结束时间，包含边界
     */
    private LocalDateTime endTime;

    /**
     * 事件类型筛选
     */
    private List<String> eventTypes;

    /**
     * 是否统计事件事实
     */
    private Boolean includeEvents;

    /**
     * 是否统计曝光事实
     */
    private Boolean includeExposures;

    /**
     * 是否为全量派生重建计划
     */
    private Boolean fullDerivedReplay;

    /**
     * 计划模式
     */
    private String replayMode;

    /**
     * 计划说明
     */
    private String message;

    /**
     * 影响的实验组数
     */
    private Long groupCount;

    /**
     * 匹配的事件事实数
     */
    private Long eventCount;

    /**
     * 已有派生物化账本的事件事实数
     */
    private Long materializedEventCount;

    /**
     * 缺少派生物化账本的事件事实数
     */
    private Long unmaterializedEventCount;

    /**
     * 匹配的曝光事实数
     */
    private Long exposureCount;

    /**
     * 已有派生物化账本的曝光事实数
     */
    private Long materializedExposureCount;

    /**
     * 缺少派生物化账本的曝光事实数
     */
    private Long unmaterializedExposureCount;

    /**
     * 匹配的总事实数
     */
    private Long affectedCount;

    /**
     * 已有派生物化账本的总事实数
     */
    private Long materializedCount;

    /**
     * 缺少派生物化账本的总事实数
     */
    private Long unmaterializedCount;

    /**
     * 分组计划明细
     */
    private List<GroupReplayPlan> groups;

    /**
     * 请求的分段数量
     */
    private Integer requestedSegmentCount;

    /**
     * 实际生成的分段数量
     */
    private Integer segmentCount;

    /**
     * 是否支持按分段修复缺账本
     */
    private Boolean segmentRecoverySupported;

    /**
     * 分段恢复说明
     */
    private String segmentRecoveryMessage;

    /**
     * 单段最大影响事实数
     */
    private Long maxSegmentAffectedCount;

    /**
     * 单段最大缺账本事实数
     */
    private Long maxSegmentUnmaterializedCount;

    /**
     * 分段巡检明细
     */
    private List<ReplayPlanSegment> segments;

    /**
     * 生成时间
     */
    private LocalDateTime generatedAt;

    @Data
    public static class GroupReplayPlan {

        private String groupId;

        private String groupName;

        private Long eventCount;

        private Long materializedEventCount;

        private Long unmaterializedEventCount;

        private Long exposureCount;

        private Long materializedExposureCount;

        private Long unmaterializedExposureCount;

        private Long affectedCount;

        private Long materializedCount;

        private Long unmaterializedCount;
    }

    @Data
    public static class ReplayPlanSegment {

        private Integer segmentIndex;

        private String segmentKey;

        private LocalDateTime startTime;

        private LocalDateTime endTime;

        private Boolean includeEvents;

        private Boolean includeExposures;

        private List<String> eventTypes;

        private Long groupCount;

        private Long eventCount;

        private Long materializedEventCount;

        private Long unmaterializedEventCount;

        private Long exposureCount;

        private Long materializedExposureCount;

        private Long unmaterializedExposureCount;

        private Long affectedCount;

        private Long materializedCount;

        private Long unmaterializedCount;

        private String recommendedAction;

        private String message;
    }
}
