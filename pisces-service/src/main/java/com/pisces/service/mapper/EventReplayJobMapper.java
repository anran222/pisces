package com.pisces.service.mapper;

import com.pisces.service.entity.EventReplayJobEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 事件管道重放任务 Mapper
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/30 10:18
 */
@Mapper
public interface EventReplayJobMapper {

    int expireStaleRunningJobs(@Param("experimentId") String experimentId,
                               @Param("staleBefore") LocalDateTime staleBefore,
                               @Param("finishedAt") LocalDateTime finishedAt,
                               @Param("errorMessage") String errorMessage);

    int insertRunningJob(@Param("entity") EventReplayJobEntity entity);

    List<EventReplayJobEntity> selectRecentByExperimentId(@Param("experimentId") String experimentId,
                                                          @Param("limit") int limit);

    EventReplayJobEntity selectByExperimentIdAndReplayJobId(@Param("experimentId") String experimentId,
                                                            @Param("replayJobId") String replayJobId);

    int updateProgress(@Param("replayJobId") String replayJobId,
                       @Param("affectedCount") long affectedCount,
                       @Param("eventCount") long eventCount,
                       @Param("exposureCount") long exposureCount,
                       @Param("groupCount") long groupCount,
                       @Param("mabRewardCount") long mabRewardCount);

    int markSucceeded(@Param("replayJobId") String replayJobId,
                      @Param("affectedCount") long affectedCount,
                      @Param("eventCount") long eventCount,
                      @Param("exposureCount") long exposureCount,
                      @Param("groupCount") long groupCount,
                      @Param("mabRewardCount") long mabRewardCount,
                      @Param("finishedAt") LocalDateTime finishedAt);

    int markFailed(@Param("replayJobId") String replayJobId,
                   @Param("errorMessage") String errorMessage,
                   @Param("finishedAt") LocalDateTime finishedAt);

    int requestCancellation(@Param("replayJobId") String replayJobId,
                            @Param("errorMessage") String errorMessage);

    int markCancelled(@Param("replayJobId") String replayJobId,
                      @Param("errorMessage") String errorMessage,
                      @Param("finishedAt") LocalDateTime finishedAt);
}
