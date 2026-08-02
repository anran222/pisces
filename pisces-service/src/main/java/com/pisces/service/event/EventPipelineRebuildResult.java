package com.pisces.service.event;

import lombok.Data;

/**
 * 事件管道派生数据重建结果
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/1 10:42
 */
@Data
public class EventPipelineRebuildResult {

    private long groupCount;

    private long eventCount;

    private long exposureCount;

    private long mabRewardCount;
}
