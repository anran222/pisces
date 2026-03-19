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
     * 统计曝光数
     *
     * @param experimentId 实验ID
     * @param groupId 实验组ID
     * @return 曝光数
     */
    long countByExperimentIdAndGroupId(String experimentId, String groupId);

    /**
     * 查询曝光事实
     *
     * @param experimentId 实验ID
     * @param groupId 实验组ID
     * @return 曝光事实列表
     */
    List<ExperimentExposure> listByExperimentIdAndGroupId(String experimentId, String groupId);

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
}
