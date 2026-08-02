package com.pisces.service.mapper;

import com.pisces.service.entity.EventMaterializationEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 事件事实派生物化账本 Mapper
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/30 11:03
 */
@Mapper
public interface EventMaterializationMapper {

    int upsert(@Param("entity") EventMaterializationEntity entity);

    long countByFact(@Param("factKind") String factKind, @Param("factId") String factId);

    long countMaterializedEventsByReplayScope(@Param("experimentId") String experimentId,
                                              @Param("groupId") String groupId,
                                              @Param("startTime") LocalDateTime startTime,
                                              @Param("endTime") LocalDateTime endTime,
                                              @Param("eventTypes") List<String> eventTypes);

    long countMaterializedExposuresByReplayScope(@Param("experimentId") String experimentId,
                                                 @Param("groupId") String groupId,
                                                 @Param("startTime") LocalDateTime startTime,
                                                 @Param("endTime") LocalDateTime endTime);
}
