package com.pisces.service.repository;

import com.pisces.common.model.ExperimentAssignment;

import java.util.List;
import java.util.Optional;

/**
 * 实验分流事实仓库
 */
public interface ExperimentAssignmentRepository {

    /**
     * 保存分流事实
     *
     * @param assignment 分流事实
     */
    void save(ExperimentAssignment assignment);

    /**
     * 按实验ID和访客ID查询分流事实
     *
     * @param experimentId 实验ID
     * @param visitorId 访客ID
     * @return 分流事实
     */
    Optional<ExperimentAssignment> findByExperimentIdAndVisitorId(String experimentId, String visitorId);

    /**
     * 按实验ID和实验组ID统计分流数
     *
     * @param experimentId 实验ID
     * @param groupId 实验组ID
     * @return 分流数
     */
    long countByExperimentIdAndGroupId(String experimentId, String groupId);

    /**
     * 查询访客的全部分流事实
     *
     * @param visitorId 访客ID
     * @return 分流事实列表
     */
    List<ExperimentAssignment> listByVisitorId(String visitorId);
}
