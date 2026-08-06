package com.pisces.service.mapper;

import com.pisces.service.entity.ExperimentAssignmentEntity;
import com.pisces.service.entity.ExperimentFactAggregateEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 实验分流事实Mapper
 */
@Mapper
public interface ExperimentAssignmentMapper {

    /**
     * 保存或更新分流事实
     *
     * @param entity 分流事实实体
     * @return 影响行数
     */
    int upsert(@Param("entity") ExperimentAssignmentEntity entity);

    /**
     * 按实验ID和访客ID查询分流事实
     *
     * @param experimentId 实验ID
     * @param visitorId 访客ID
     * @return 分流事实实体
     */
    ExperimentAssignmentEntity selectByExperimentIdAndVisitorId(@Param("experimentId") String experimentId,
                                                                @Param("visitorId") String visitorId);

    /**
     * 按实验ID和实验组ID统计分流数量
     *
     * @param experimentId 实验ID
     * @param groupId 实验组ID
     * @return 分流数量
     */
    long countByExperimentIdAndGroupId(@Param("experimentId") String experimentId, @Param("groupId") String groupId);

    ExperimentFactAggregateEntity aggregateByExperimentIds(@Param("experimentIds") List<String> experimentIds);

    /**
     * 查询访客参与的实验分流事实
     *
     * @param visitorId 访客ID
     * @return 分流事实列表
     */
    List<ExperimentAssignmentEntity> selectByVisitorId(@Param("visitorId") String visitorId);
}
