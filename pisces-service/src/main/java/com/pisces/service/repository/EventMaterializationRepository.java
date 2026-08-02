package com.pisces.service.repository;

import com.pisces.service.event.EventMaterializationRecord;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 事件事实派生物化账本仓库
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/30 11:03
 */
public interface EventMaterializationRepository {

    void saveOrRefresh(EventMaterializationRecord record);

    boolean exists(String factKind, String factId);

    long countMaterializedEventsByReplayScope(String experimentId, String groupId, LocalDateTime startTime,
                                              LocalDateTime endTime, List<String> eventTypes);

    long countMaterializedExposuresByReplayScope(String experimentId, String groupId, LocalDateTime startTime,
                                                 LocalDateTime endTime);
}
