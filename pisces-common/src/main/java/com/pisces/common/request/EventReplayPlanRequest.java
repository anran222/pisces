package com.pisces.common.request;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 事件重放计划请求
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/30 10:55
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class EventReplayPlanRequest extends BaseRequest {

    /**
     * 计划开始时间，包含边界
     */
    private LocalDateTime startTime;

    /**
     * 计划结束时间，包含边界
     */
    private LocalDateTime endTime;

    /**
     * 事件类型筛选，仅作用于事件事实
     */
    private List<String> eventTypes;

    /**
     * 是否统计事件事实，默认 true
     */
    private Boolean includeEvents;

    /**
     * 是否统计曝光事实，默认 true
     */
    private Boolean includeExposures;

    /**
     * 分段巡检数量，仅在同时指定 startTime 和 endTime 时生效
     */
    private Integer segmentCount;
}
