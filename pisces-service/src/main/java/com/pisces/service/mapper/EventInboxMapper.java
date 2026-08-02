package com.pisces.service.mapper;

import com.pisces.service.entity.EventInboxEntity;
import com.pisces.service.entity.EventInboxStatusCountEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 事件收件箱Mapper
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/1 10:26
 */
@Mapper
public interface EventInboxMapper {

    int insertIgnore(@Param("entity") EventInboxEntity entity);

    List<EventInboxEntity> selectDueRecords(@Param("now") LocalDateTime now, @Param("limit") int limit);

    List<EventInboxEntity> selectDueRecordsByExperimentId(@Param("experimentId") String experimentId,
                                                          @Param("now") LocalDateTime now,
                                                          @Param("limit") int limit);

    int markProcessing(@Param("inboxId") String inboxId, @Param("lockedBy") String lockedBy,
                       @Param("now") LocalDateTime now, @Param("lockedUntil") LocalDateTime lockedUntil);

    int markDone(@Param("inboxId") String inboxId, @Param("processedAt") LocalDateTime processedAt);

    int markRetry(@Param("inboxId") String inboxId, @Param("retryCount") int retryCount,
                  @Param("nextRetryAt") LocalDateTime nextRetryAt, @Param("lastError") String lastError);

    int markDead(@Param("inboxId") String inboxId, @Param("retryCount") int retryCount,
                 @Param("lastError") String lastError, @Param("processedAt") LocalDateTime processedAt);

    List<EventInboxStatusCountEntity> countByExperimentIdGroupByStatus(@Param("experimentId") String experimentId);

    LocalDateTime selectOldestUnfinishedAcceptedAt(@Param("experimentId") String experimentId);

    int retryDeadRecords(@Param("experimentId") String experimentId, @Param("nextRetryAt") LocalDateTime nextRetryAt);
}
