package com.pisces.common.response;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 事件管道重放任务响应
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/30 10:46
 */
@Data
public class EventReplayJobResponse {

    /**
     * 重放任务ID
     */
    private String replayJobId;

    /**
     * 实验ID
     */
    private String experimentId;

    /**
     * 操作人
     */
    private String operator;

    /**
     * 任务状态：RUNNING / CANCEL_REQUESTED / SUCCEEDED / FAILED / CANCELLED
     */
    private String jobStatus;

    /**
     * 运行中互斥键
     */
    private String activeKey;

    /**
     * 重放模式
     */
    private String replayMode;

    /**
     * 重放范围开始时间
     */
    private LocalDateTime scopeStartTime;

    /**
     * 重放范围结束时间
     */
    private LocalDateTime scopeEndTime;

    /**
     * 重放事件类型筛选
     */
    private List<String> eventTypes;

    /**
     * 是否包含事件事实
     */
    private Boolean includeEvents;

    /**
     * 是否包含曝光事实
     */
    private Boolean includeExposures;

    /**
     * 是否等价于全量派生重建
     */
    private Boolean fullDerivedReplay;

    /**
     * 计划影响记录数，用于运行中进度估算
     */
    private Long plannedAffectedCount;

    /**
     * 计划处理事件事实数
     */
    private Long plannedEventCount;

    /**
     * 计划处理曝光事实数
     */
    private Long plannedExposureCount;

    /**
     * 计划处理实验组数
     */
    private Long plannedGroupCount;

    /**
     * 事实处理进度百分比，范围 0-100
     */
    private Integer progressPercent;

    /**
     * 重建影响记录数；运行中为已处理累计值，终态为最终值
     */
    private Long affectedCount;

    /**
     * 重建事件事实数；运行中为已处理累计值，终态为最终值
     */
    private Long eventCount;

    /**
     * 重建曝光事实数；运行中为已处理累计值，终态为最终值
     */
    private Long exposureCount;

    /**
     * 重建实验组数；运行中为已处理累计值，终态为最终值
     */
    private Long groupCount;

    /**
     * 重建 MAB 奖励数；运行中为已处理累计值，终态为最终值
     */
    private Long mabRewardCount;

    /**
     * 失败原因
     */
    private String errorMessage;

    /**
     * 开始时间
     */
    private LocalDateTime startedAt;

    /**
     * 结束时间
     */
    private LocalDateTime finishedAt;
}
