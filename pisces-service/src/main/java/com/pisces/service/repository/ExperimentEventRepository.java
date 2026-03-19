package com.pisces.service.repository;

import com.pisces.common.model.ExperimentEventFact;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 实验事件事实仓库
 */
public interface ExperimentEventRepository {

    /**
     * 保存事件事实
     *
     * @param eventFact 事件事实
     */
    void save(ExperimentEventFact eventFact);

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
}
