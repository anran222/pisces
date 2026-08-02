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
     * 幂等插入事件事实
     *
     * @param entity 事件事实实体
     * @return 影响行数
     */
    int insertIgnore(@Param("entity") ExperimentEventEntity entity);

    /**
     * 按实验ID和客户端幂等事件ID查询事件事实。
     *
     * @param experimentId 实验ID
     * @param clientEventId 客户端幂等事件ID
     * @return 事件事实实体
     */
    ExperimentEventEntity selectByExperimentIdAndClientEventId(@Param("experimentId") String experimentId,
                                                               @Param("clientEventId") String clientEventId);

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
     * 按重放计划范围统计事件事实
     *
     * @param experimentId 实验ID
     * @param groupId 实验组ID
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @param eventTypes 事件类型列表
     * @return 事件数
     */
    long countByReplayScope(@Param("experimentId") String experimentId,
                            @Param("groupId") String groupId,
                            @Param("startTime") LocalDateTime startTime,
                            @Param("endTime") LocalDateTime endTime,
                            @Param("eventTypes") List<String> eventTypes);

    /**
     * 按重放计划范围查询事件事实。
     *
     * @param experimentId 实验ID
     * @param groupId 实验组ID
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @param eventTypes 事件类型列表
     * @return 事件事实列表
     */
    List<ExperimentEventEntity> selectByReplayScope(@Param("experimentId") String experimentId,
                                                    @Param("groupId") String groupId,
                                                    @Param("startTime") LocalDateTime startTime,
                                                    @Param("endTime") LocalDateTime endTime,
                                                    @Param("eventTypes") List<String> eventTypes);

    /**
     * 按重放计划范围分批查询事件事实。
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
    List<ExperimentEventEntity> selectByReplayScopeBatch(@Param("experimentId") String experimentId,
                                                         @Param("groupId") String groupId,
                                                         @Param("startTime") LocalDateTime startTime,
                                                         @Param("endTime") LocalDateTime endTime,
                                                         @Param("eventTypes") List<String> eventTypes,
                                                         @Param("offset") long offset,
                                                         @Param("limit") int limit);

    /**
     * 按重放计划范围查询缺少派生物化账本的事件事实。
     *
     * @param experimentId 实验ID
     * @param groupId 实验组ID
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @param eventTypes 事件类型列表
     * @return 事件事实列表
     */
    List<ExperimentEventEntity> selectUnmaterializedByReplayScope(@Param("experimentId") String experimentId,
                                                                  @Param("groupId") String groupId,
                                                                  @Param("startTime") LocalDateTime startTime,
                                                                  @Param("endTime") LocalDateTime endTime,
                                                                  @Param("eventTypes") List<String> eventTypes);

    /**
     * 按重放计划范围分批查询缺少派生物化账本的事件事实。
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
    List<ExperimentEventEntity> selectUnmaterializedByReplayScopeBatch(@Param("experimentId") String experimentId,
                                                                       @Param("groupId") String groupId,
                                                                       @Param("startTime") LocalDateTime startTime,
                                                                       @Param("endTime") LocalDateTime endTime,
                                                                       @Param("eventTypes") List<String> eventTypes,
                                                                       @Param("offset") long offset,
                                                                       @Param("limit") int limit);

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
     * 按实验ID和实验组ID分批查询事件事实。
     *
     * @param experimentId 实验ID
     * @param groupId 实验组ID
     * @param offset 偏移量
     * @param limit 批大小
     * @return 事件事实列表
     */
    List<ExperimentEventEntity> selectByExperimentIdAndGroupIdBatch(@Param("experimentId") String experimentId,
                                                                    @Param("groupId") String groupId,
                                                                    @Param("offset") long offset,
                                                                    @Param("limit") int limit);

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
