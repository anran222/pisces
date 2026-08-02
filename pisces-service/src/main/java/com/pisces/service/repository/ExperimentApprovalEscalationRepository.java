package com.pisces.service.repository;

import com.pisces.common.model.ExperimentApprovalEscalation;
import com.pisces.common.model.ExperimentApprovalEscalationDelivery;
import com.pisces.common.model.ExperimentApprovalTaskType;
import com.pisces.service.entity.ExperimentApprovalEscalationStatusCountEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 实验审批升级告警仓库
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/17 14:45
 */
public interface ExperimentApprovalEscalationRepository {

    ExperimentApprovalEscalation save(ExperimentApprovalEscalation escalation);

    Optional<ExperimentApprovalEscalation> findByEscalationId(String escalationId);

    Optional<ExperimentApprovalEscalation> findByTask(String experimentId, ExperimentApprovalTaskType approvalType,
                                                      long draftVersion, LocalDateTime approvalSubmittedAt);

    List<ExperimentApprovalEscalation> list(String appId, String owner, String escalationStatus, int limit);

    List<ExperimentApprovalEscalation> listDispatchable(LocalDateTime now, int limit);

    List<ExperimentApprovalEscalationDelivery> registerDeliveryTargets(
            String escalationId, List<ExperimentApprovalEscalationDelivery> deliveries);

    List<ExperimentApprovalEscalationDelivery> listDeliveries(String escalationId);

    List<ExperimentApprovalEscalationDelivery> listDeliveries(List<String> escalationIds);

    List<ExperimentApprovalEscalationStatusCountEntity> countByEscalationStatus(String appId, String owner);

    List<ExperimentApprovalEscalationStatusCountEntity> countByNotificationStatus(String appId, String owner);

    List<ExperimentApprovalEscalationStatusCountEntity> countDeliveryByNotificationStatus(String appId, String owner);

    int acknowledge(String escalationId, String operator, String comment, LocalDateTime acknowledgedAt);

    int resolveByTask(String experimentId, ExperimentApprovalTaskType approvalType, long draftVersion,
                      String operator, String reason, LocalDateTime resolvedAt);

    int markNotificationDispatching(String escalationId, LocalDateTime now, LocalDateTime lockedUntil);

    int markNotificationSent(String escalationId, int attemptCount, LocalDateTime deliveredAt);

    int markNotificationRetry(String escalationId, int attemptCount, LocalDateTime lastAttemptAt,
                              LocalDateTime nextAttemptAt, String lastError);

    int markNotificationDead(String escalationId, int attemptCount, LocalDateTime lastAttemptAt, String lastError);

    int markNotificationDeliveryDispatching(String escalationId, String targetKey,
                                            LocalDateTime now, LocalDateTime lockedUntil);

    int markNotificationDeliverySent(String escalationId, String targetKey,
                                     int attemptCount, LocalDateTime deliveredAt);

    int markNotificationDeliveryRetry(String escalationId, String targetKey, int attemptCount,
                                      LocalDateTime lastAttemptAt, LocalDateTime nextAttemptAt, String lastError);

    int markNotificationDeliveryDead(String escalationId, String targetKey, int attemptCount,
                                     LocalDateTime lastAttemptAt, String lastError);

    int refreshNotificationStatusFromDeliveries(String escalationId);

    int retryDeadNotification(String escalationId, LocalDateTime nextAttemptAt);

    int retryDeadNotifications(String appId, String owner, LocalDateTime nextAttemptAt);
}
