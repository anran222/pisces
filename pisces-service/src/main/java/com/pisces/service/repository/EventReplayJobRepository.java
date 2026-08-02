package com.pisces.service.repository;

import com.pisces.service.event.EventReplayJobRecord;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 事件管道重放任务仓库
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/30 10:18
 */
public interface EventReplayJobRepository {

    int expireStaleRunningJobs(String experimentId, LocalDateTime staleBefore, LocalDateTime finishedAt,
                               String errorMessage);

    boolean createRunningJob(EventReplayJobRecord record);

    List<EventReplayJobRecord> listRecentByExperimentId(String experimentId, int limit);

    EventReplayJobRecord findByExperimentIdAndReplayJobId(String experimentId, String replayJobId);

    boolean updateProgress(String replayJobId, long affectedCount, long eventCount, long exposureCount,
                           long groupCount, long mabRewardCount);

    boolean markSucceeded(String replayJobId, long affectedCount, long eventCount, long exposureCount,
                          long groupCount, long mabRewardCount, LocalDateTime finishedAt);

    boolean markFailed(String replayJobId, String errorMessage, LocalDateTime finishedAt);

    boolean requestCancellation(String replayJobId, String errorMessage);

    boolean markCancelled(String replayJobId, String errorMessage, LocalDateTime finishedAt);
}
