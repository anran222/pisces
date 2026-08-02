package com.pisces.service.repository;

import com.pisces.common.model.ExperimentExposure;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 实验曝光事实仓库
 */
public interface ExperimentExposureRepository {

    /**
     * 保存曝光事实
     *
     * @param exposure 曝光事实
     */
    void save(ExperimentExposure exposure);

    /**
     * 幂等保存曝光事实
     *
     * @param exposure 曝光事实
     * @return true 表示实际写入，false 表示命中重复幂等曝光
     */
    boolean saveIfAbsent(ExperimentExposure exposure);

    /**
     * 按幂等键查询已持久化曝光事实。
     *
     * @param idempotencyKey 幂等键
     * @return 已持久化事实，不存在时返回 null
     */
    ExperimentExposure findByIdempotencyKey(String idempotencyKey);

    /**
     * 统计曝光数
     *
     * @param experimentId 实验ID
     * @param groupId 实验组ID
     * @return 曝光数
     */
    long countByExperimentIdAndGroupId(String experimentId, String groupId);

    /**
     * 按重放计划范围统计曝光数
     *
     * @param experimentId 实验ID
     * @param groupId 实验组ID
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return 曝光数
     */
    long countByReplayScope(String experimentId, String groupId, LocalDateTime startTime, LocalDateTime endTime);

    /**
     * 查询重放计划范围内的全部曝光事实。
     *
     * @param experimentId 实验ID
     * @param groupId 实验组ID
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return 曝光事实列表
     */
    List<ExperimentExposure> listByReplayScope(String experimentId, String groupId,
                                               LocalDateTime startTime, LocalDateTime endTime);

    /**
     * 分批查询重放计划范围内的曝光事实。
     *
     * @param experimentId 实验ID
     * @param groupId 实验组ID
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @param offset 偏移量
     * @param limit 批大小
     * @return 曝光事实列表
     */
    default List<ExperimentExposure> listByReplayScopeBatch(String experimentId, String groupId,
                                                            LocalDateTime startTime, LocalDateTime endTime,
                                                            long offset, int limit) {
        return subList(listByReplayScope(experimentId, groupId, startTime, endTime), offset, limit);
    }

    /**
     * 查询重放计划范围内缺少派生物化账本的曝光事实。
     *
     * @param experimentId 实验ID
     * @param groupId 实验组ID
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return 缺少派生物化账本的曝光事实列表
     */
    List<ExperimentExposure> listUnmaterializedByReplayScope(String experimentId, String groupId,
                                                             LocalDateTime startTime, LocalDateTime endTime);

    /**
     * 分批查询重放计划范围内缺少派生物化账本的曝光事实。
     *
     * @param experimentId 实验ID
     * @param groupId 实验组ID
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @param offset 偏移量
     * @param limit 批大小
     * @return 缺少派生物化账本的曝光事实列表
     */
    default List<ExperimentExposure> listUnmaterializedByReplayScopeBatch(String experimentId, String groupId,
                                                                          LocalDateTime startTime,
                                                                          LocalDateTime endTime,
                                                                          long offset, int limit) {
        return subList(listUnmaterializedByReplayScope(experimentId, groupId, startTime, endTime), offset, limit);
    }

    /**
     * 查询曝光事实
     *
     * @param experimentId 实验ID
     * @param groupId 实验组ID
     * @return 曝光事实列表
     */
    List<ExperimentExposure> listByExperimentIdAndGroupId(String experimentId, String groupId);

    /**
     * 分批查询曝光事实。
     *
     * @param experimentId 实验ID
     * @param groupId 实验组ID
     * @param offset 偏移量
     * @param limit 批大小
     * @return 曝光事实列表
     */
    default List<ExperimentExposure> listByExperimentIdAndGroupIdBatch(String experimentId, String groupId,
                                                                       long offset, int limit) {
        return subList(listByExperimentIdAndGroupId(experimentId, groupId), offset, limit);
    }

    /**
     * 查询时间范围内的曝光事实
     *
     * @param experimentId 实验ID
     * @param groupId 实验组ID
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return 曝光事实列表
     */
    List<ExperimentExposure> listByExperimentIdAndGroupIdInTimeRange(String experimentId, String groupId,
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
