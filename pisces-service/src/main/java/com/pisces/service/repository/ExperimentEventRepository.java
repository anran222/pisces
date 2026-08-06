package com.pisces.service.repository;

import com.pisces.common.model.ExperimentEventFact;
import com.pisces.service.entity.ExperimentFactAggregateEntity;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 实验事件事实仓库
 */
public interface ExperimentEventRepository {

    /**
     * 幂等保存事件事实
     *
     * @param eventFact 事件事实
     * @return true 表示实际写入，false 表示命中重复幂等事件
     */
    boolean saveIfAbsent(ExperimentEventFact eventFact);

    /**
     * 按服务端幂等键查询已持久化事件事实。
     *
     * @param experimentId 实验ID
     * @param clientEventId 客户端幂等事件ID
     * @return 已持久化事实，不存在时返回 null
     */
    ExperimentEventFact findByExperimentIdAndClientEventId(String experimentId, String clientEventId);

    /**
     * 统计事件数
     *
     * @param experimentId 实验ID
     * @param groupId 实验组ID
     * @param eventType 事件类型
     * @return 事件数
     */
    long countByExperimentIdAndGroupIdAndEventType(String experimentId, String groupId, String eventType);

    /**
     * 按实验ID集合聚合事件事实。
     *
     * @param experimentIds 实验ID集合
     * @return 事实数量和最近发生时间
     */
    default ExperimentFactAggregateEntity aggregateByExperimentIds(List<String> experimentIds) {
        ExperimentFactAggregateEntity aggregate = new ExperimentFactAggregateEntity();
        aggregate.setTotalCount(0L);
        return aggregate;
    }

    /**
     * 按重放计划范围统计事件数
     *
     * @param experimentId 实验ID
     * @param groupId 实验组ID
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @param eventTypes 事件类型列表
     * @return 事件数
     */
    long countByReplayScope(String experimentId, String groupId, LocalDateTime startTime, LocalDateTime endTime,
                            List<String> eventTypes);

    /**
     * 查询重放计划范围内的全部事件事实。
     *
     * @param experimentId 实验ID
     * @param groupId 实验组ID
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @param eventTypes 事件类型列表
     * @return 事件事实列表
     */
    List<ExperimentEventFact> listByReplayScope(String experimentId, String groupId,
                                                LocalDateTime startTime, LocalDateTime endTime,
                                                List<String> eventTypes);

    /**
     * 分批查询重放计划范围内的事件事实。
     *
     * @param experimentId 实验ID
     * @param groupId 实验组ID
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @param eventTypes 事件类型列表
     * @param offset 偏移量
     * @param limit 批大小
     * @return 事件事实列表
     */
    default List<ExperimentEventFact> listByReplayScopeBatch(String experimentId, String groupId,
                                                             LocalDateTime startTime, LocalDateTime endTime,
                                                             List<String> eventTypes, long offset, int limit) {
        return subList(listByReplayScope(experimentId, groupId, startTime, endTime, eventTypes), offset, limit);
    }

    /**
     * 查询重放计划范围内缺少派生物化账本的事件事实。
     *
     * @param experimentId 实验ID
     * @param groupId 实验组ID
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @param eventTypes 事件类型列表
     * @return 缺少派生物化账本的事件事实列表
     */
    List<ExperimentEventFact> listUnmaterializedByReplayScope(String experimentId, String groupId,
                                                              LocalDateTime startTime, LocalDateTime endTime,
                                                              List<String> eventTypes);

    /**
     * 分批查询重放计划范围内缺少派生物化账本的事件事实。
     *
     * @param experimentId 实验ID
     * @param groupId 实验组ID
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @param eventTypes 事件类型列表
     * @param offset 偏移量
     * @param limit 批大小
     * @return 缺少派生物化账本的事件事实列表
     */
    default List<ExperimentEventFact> listUnmaterializedByReplayScopeBatch(String experimentId, String groupId,
                                                                           LocalDateTime startTime,
                                                                           LocalDateTime endTime,
                                                                           List<String> eventTypes,
                                                                           long offset, int limit) {
        return subList(listUnmaterializedByReplayScope(experimentId, groupId, startTime, endTime, eventTypes),
                offset, limit);
    }

    /**
     * 统计去重访客数
     *
     * @param experimentId 实验ID
     * @param groupId 实验组ID
     * @return 去重访客数
     */
    long countDistinctVisitorByExperimentIdAndGroupId(String experimentId, String groupId);

    /**
     * 查询事件事实
     *
     * @param experimentId 实验ID
     * @param groupId 实验组ID
     * @return 事件事实列表
     */
    List<ExperimentEventFact> listByExperimentIdAndGroupId(String experimentId, String groupId);

    /**
     * 分批查询事件事实。
     *
     * @param experimentId 实验ID
     * @param groupId 实验组ID
     * @param offset 偏移量
     * @param limit 批大小
     * @return 事件事实列表
     */
    default List<ExperimentEventFact> listByExperimentIdAndGroupIdBatch(String experimentId, String groupId,
                                                                        long offset, int limit) {
        return subList(listByExperimentIdAndGroupId(experimentId, groupId), offset, limit);
    }

    /**
     * 查询时间范围内的事件事实
     *
     * @param experimentId 实验ID
     * @param groupId 实验组ID
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return 事件事实列表
     */
    List<ExperimentEventFact> listByExperimentIdAndGroupIdInTimeRange(String experimentId, String groupId,
                                                                      LocalDateTime startTime,
                                                                      LocalDateTime endTime);

    private static <T> List<T> subList(List<T> items, long offset, int limit) {
        if (items == null || items.isEmpty() || limit <= 0 || offset >= items.size()) {
            return List.of();
        }
        int fromIndex = (int) Math.max(0L, offset);
        int toIndex = Math.min(fromIndex + limit, items.size());
        return items.subList(fromIndex, toIndex);
    }
}
