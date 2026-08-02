package com.pisces.service.mapper;

import com.pisces.service.entity.ExperimentApprovalEscalationEntity;
import com.pisces.service.entity.ExperimentApprovalEscalationDeliveryEntity;
import com.pisces.service.entity.ExperimentApprovalEscalationStatusCountEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 实验审批升级告警Mapper
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/17 14:45
 */
@Mapper
public interface ExperimentApprovalEscalationMapper {

    int upsert(@Param("entity") ExperimentApprovalEscalationEntity entity);

    ExperimentApprovalEscalationEntity selectByEscalationId(@Param("escalationId") String escalationId);

    ExperimentApprovalEscalationEntity selectByTask(@Param("experimentId") String experimentId,
                                                    @Param("approvalType") String approvalType,
                                                    @Param("draftVersion") long draftVersion,
                                                    @Param("approvalSubmittedAt") LocalDateTime approvalSubmittedAt);

    List<ExperimentApprovalEscalationEntity> list(@Param("appId") String appId,
                                                  @Param("owner") String owner,
                                                  @Param("escalationStatus") String escalationStatus,
                                                  @Param("limit") int limit);

    List<ExperimentApprovalEscalationEntity> listDispatchable(@Param("now") LocalDateTime now,
                                                              @Param("limit") int limit);

    int upsertDeliveries(@Param("entities") List<ExperimentApprovalEscalationDeliveryEntity> entities);

    int deactivateStaleDeliveries(@Param("escalationId") String escalationId,
                                  @Param("targetKeys") List<String> targetKeys);

    List<ExperimentApprovalEscalationDeliveryEntity> listDeliveriesByEscalationId(
            @Param("escalationId") String escalationId);

    List<ExperimentApprovalEscalationDeliveryEntity> listDeliveriesByEscalationIds(
            @Param("escalationIds") List<String> escalationIds);

    List<ExperimentApprovalEscalationStatusCountEntity> countByEscalationStatus(
            @Param("appId") String appId, @Param("owner") String owner);

    List<ExperimentApprovalEscalationStatusCountEntity> countByNotificationStatus(
            @Param("appId") String appId, @Param("owner") String owner);

    List<ExperimentApprovalEscalationStatusCountEntity> countDeliveryByNotificationStatus(
            @Param("appId") String appId, @Param("owner") String owner);

    int acknowledge(@Param("escalationId") String escalationId,
                    @Param("operator") String operator,
                    @Param("comment") String comment,
                    @Param("acknowledgedAt") LocalDateTime acknowledgedAt);

    int resolveByTask(@Param("experimentId") String experimentId,
                      @Param("approvalType") String approvalType,
                      @Param("draftVersion") long draftVersion,
                      @Param("operator") String operator,
                      @Param("reason") String reason,
                      @Param("resolvedAt") LocalDateTime resolvedAt);

    int markNotificationDispatching(@Param("escalationId") String escalationId,
                                    @Param("now") LocalDateTime now,
                                    @Param("lockedUntil") LocalDateTime lockedUntil);

    int markNotificationSent(@Param("escalationId") String escalationId,
                             @Param("attemptCount") int attemptCount,
                             @Param("deliveredAt") LocalDateTime deliveredAt);

    int markNotificationRetry(@Param("escalationId") String escalationId,
                              @Param("attemptCount") int attemptCount,
                              @Param("lastAttemptAt") LocalDateTime lastAttemptAt,
                              @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
                              @Param("lastError") String lastError);

    int markNotificationDead(@Param("escalationId") String escalationId,
                             @Param("attemptCount") int attemptCount,
                             @Param("lastAttemptAt") LocalDateTime lastAttemptAt,
                             @Param("lastError") String lastError);

    int markNotificationDeliveryDispatching(@Param("escalationId") String escalationId,
                                            @Param("targetKey") String targetKey,
                                            @Param("now") LocalDateTime now,
                                            @Param("lockedUntil") LocalDateTime lockedUntil);

    int markNotificationDeliverySent(@Param("escalationId") String escalationId,
                                     @Param("targetKey") String targetKey,
                                     @Param("attemptCount") int attemptCount,
                                     @Param("deliveredAt") LocalDateTime deliveredAt);

    int markNotificationDeliveryRetry(@Param("escalationId") String escalationId,
                                      @Param("targetKey") String targetKey,
                                      @Param("attemptCount") int attemptCount,
                                      @Param("lastAttemptAt") LocalDateTime lastAttemptAt,
                                      @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
                                      @Param("lastError") String lastError);

    int markNotificationDeliveryDead(@Param("escalationId") String escalationId,
                                     @Param("targetKey") String targetKey,
                                     @Param("attemptCount") int attemptCount,
                                     @Param("lastAttemptAt") LocalDateTime lastAttemptAt,
                                     @Param("lastError") String lastError);

    int refreshNotificationStatusFromDeliveries(@Param("escalationId") String escalationId);

    int retryDeadNotification(@Param("escalationId") String escalationId,
                              @Param("nextAttemptAt") LocalDateTime nextAttemptAt);

    int retryDeadNotifications(@Param("appId") String appId,
                               @Param("owner") String owner,
                               @Param("nextAttemptAt") LocalDateTime nextAttemptAt);

    int retryDeadNotificationDeliveries(@Param("escalationId") String escalationId,
                                        @Param("nextAttemptAt") LocalDateTime nextAttemptAt);

    int retryDeadNotificationDeliveriesByFilter(@Param("appId") String appId,
                                                @Param("owner") String owner,
                                                @Param("nextAttemptAt") LocalDateTime nextAttemptAt);
}
