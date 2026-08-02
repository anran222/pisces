package com.pisces.service.mapper;

import com.pisces.service.entity.ExperimentExposureEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 实验曝光事实Mapper
 */
@Mapper
public interface ExperimentExposureMapper {

    /**
     * 保存曝光事实
     *
     * @param entity 曝光事实实体
     * @return 影响行数
     */
    int insertIgnore(@Param("entity") ExperimentExposureEntity entity);

    /**
     * 按幂等键查询曝光事实。
     *
     * @param idempotencyKey 幂等键
     * @return 曝光事实实体
     */
    ExperimentExposureEntity selectByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);

    /**
     * 按实验ID和实验组ID统计曝光数
     *
     * @param experimentId 实验ID
     * @param groupId 实验组ID
     * @return 曝光数
     */
    long countByExperimentIdAndGroupId(@Param("experimentId") String experimentId, @Param("groupId") String groupId);

    /**
     * 按重放计划范围统计曝光事实
     *
     * @param experimentId 实验ID
     * @param groupId 实验组ID
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return 曝光数
     */
    long countByReplayScope(@Param("experimentId") String experimentId,
                            @Param("groupId") String groupId,
                            @Param("startTime") LocalDateTime startTime,
                            @Param("endTime") LocalDateTime endTime);

    /**
     * 按重放计划范围查询曝光事实。
     *
     * @param experimentId 实验ID
     * @param groupId 实验组ID
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return 曝光事实列表
     */
    List<ExperimentExposureEntity> selectByReplayScope(@Param("experimentId") String experimentId,
                                                       @Param("groupId") String groupId,
                                                       @Param("startTime") LocalDateTime startTime,
                                                       @Param("endTime") LocalDateTime endTime);

    /**
     * 按重放计划范围分批查询曝光事实。
     *
     * @param experimentId 实验ID
     * @param groupId 实验组ID
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @param offset 偏移量
     * @param limit 批大小
     * @return 曝光事实列表
     */
    List<ExperimentExposureEntity> selectByReplayScopeBatch(@Param("experimentId") String experimentId,
                                                            @Param("groupId") String groupId,
                                                            @Param("startTime") LocalDateTime startTime,
                                                            @Param("endTime") LocalDateTime endTime,
                                                            @Param("offset") long offset,
                                                            @Param("limit") int limit);

    /**
     * 按重放计划范围查询缺少派生物化账本的曝光事实。
     *
     * @param experimentId 实验ID
     * @param groupId 实验组ID
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return 曝光事实列表
     */
    List<ExperimentExposureEntity> selectUnmaterializedByReplayScope(@Param("experimentId") String experimentId,
                                                                     @Param("groupId") String groupId,
                                                                     @Param("startTime") LocalDateTime startTime,
                                                                     @Param("endTime") LocalDateTime endTime);

    /**
     * 按重放计划范围分批查询缺少派生物化账本的曝光事实。
     *
     * @param experimentId 实验ID
     * @param groupId 实验组ID
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @param offset 偏移量
     * @param limit 批大小
     * @return 曝光事实列表
     */
    List<ExperimentExposureEntity> selectUnmaterializedByReplayScopeBatch(@Param("experimentId") String experimentId,
                                                                          @Param("groupId") String groupId,
                                                                          @Param("startTime") LocalDateTime startTime,
                                                                          @Param("endTime") LocalDateTime endTime,
                                                                          @Param("offset") long offset,
                                                                          @Param("limit") int limit);

    /**
     * 按实验ID和实验组ID查询曝光事实
     *
     * @param experimentId 实验ID
     * @param groupId 实验组ID
     * @return 曝光事实列表
     */
    List<ExperimentExposureEntity> selectByExperimentIdAndGroupId(@Param("experimentId") String experimentId,
                                                                  @Param("groupId") String groupId);

    /**
     * 按实验ID和实验组ID分批查询曝光事实。
     *
     * @param experimentId 实验ID
     * @param groupId 实验组ID
     * @param offset 偏移量
     * @param limit 批大小
     * @return 曝光事实列表
     */
    List<ExperimentExposureEntity> selectByExperimentIdAndGroupIdBatch(@Param("experimentId") String experimentId,
                                                                       @Param("groupId") String groupId,
                                                                       @Param("offset") long offset,
                                                                       @Param("limit") int limit);

    /**
     * 按实验ID、实验组ID和时间范围查询曝光事实
     *
     * @param experimentId 实验ID
     * @param groupId 实验组ID
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return 曝光事实列表
     */
    List<ExperimentExposureEntity> selectByExperimentIdAndGroupIdInTimeRange(@Param("experimentId") String experimentId,
                                                                              @Param("groupId") String groupId,
                                                                              @Param("startTime") LocalDateTime startTime,
                                                                              @Param("endTime") LocalDateTime endTime);
}
