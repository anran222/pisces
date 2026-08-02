package com.pisces.common.response;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 事件管道治理操作响应
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/1 10:42
 */
@Data
public class EventPipelineOperationResponse {

    /**
     * 实验ID
     */
    private String experimentId;

    /**
     * 操作类型
     */
    private String operation;

    /**
     * 操作人
     */
    private String operator;

    /**
     * 操作状态
     */
    private String status;

    /**
     * 事件重放任务ID
     */
    private String replayJobId;

    /**
     * 事件重放任务状态
     */
    private String replayJobStatus;

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
     * 影响的 inbox 记录数
     */
    private Long affectedCount;

    /**
     * 重建的事件事实数
     */
    private Long eventCount;

    /**
     * 重建的曝光事实数
     */
    private Long exposureCount;

    /**
     * 重建的实验组数
     */
    private Long groupCount;

    /**
     * 重建的 MAB 奖励数
     */
    private Long mabRewardCount;

    /**
     * 操作说明
     */
    private String message;

    /**
     * 操作时间
     */
    private LocalDateTime operatedAt;
}
