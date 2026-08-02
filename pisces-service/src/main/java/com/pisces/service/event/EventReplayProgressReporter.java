package com.pisces.service.event;

/**
 * 事件 replay 批处理进度回调。
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/30 15:10
 */
@FunctionalInterface
public interface EventReplayProgressReporter {

    EventReplayProgressReporter NOOP = result -> true;

    /**
     * 上报当前累计进度。
     *
     * @param result 当前累计重建结果
     * @return true 表示继续执行，false 表示调用方要求在批次边界停止
     */
    boolean report(EventPipelineRebuildResult result);
}
