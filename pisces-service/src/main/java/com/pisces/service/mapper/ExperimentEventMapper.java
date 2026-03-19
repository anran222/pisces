package com.pisces.service.mapper;

import com.pisces.service.entity.ExperimentEventEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 实验事件事实Mapper
 */
@Mapper
public interface ExperimentEventMapper {

    /**
     * 保存事件事实
     *
     * @param entity 事件事实实体
     * @return 影响行数
     */
    int insert(@Param("entity") ExperimentEventEntity entity);

    /**
     * 按实验ID、实验组ID和事件类型统计事件数
     *
     * @param experimentId 实验ID
     * @param groupId 实验组ID
     * @param eventType 事件类型
     * @return 事件数
     */
    long countByExperimentIdAndGroupIdAndEventType(@Param("experimentId") String experimentId,
                                                   @Param("groupId") String groupId,
                                                   @Param("eventType") String eventType);

    /**
     * 按实验ID和实验组ID统计去重访客数
     *
     * @param experimentId 实验ID
     * @param groupId 实验组ID
     * @return 去重访客数
     */
    long countDistinctVisitorByExperimentIdAndGroupId(@Param("experimentId") String experimentId,
                                                      @Param("groupId") String groupId);

    /**
     * 按实验ID和实验组ID查询事件事实
     *
     * @param experimentId 实验ID
     * @param groupId 实验组ID
     * @return 事件事实列表
     */
    List<ExperimentEventEntity> selectByExperimentIdAndGroupId(@Param("experimentId") String experimentId,
                                                               @Param("groupId") String groupId);

    /**
     * 按实验ID、实验组ID和时间范围查询事件事实
     *
     * @param experimentId 实验ID
     * @param groupId 实验组ID
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return 事件事实列表
     */
    List<ExperimentEventEntity> selectByExperimentIdAndGroupIdInTimeRange(@Param("experimentId") String experimentId,
                                                                          @Param("groupId") String groupId,
                                                                          @Param("startTime") LocalDateTime startTime,
                                                                          @Param("endTime") LocalDateTime endTime);
}
