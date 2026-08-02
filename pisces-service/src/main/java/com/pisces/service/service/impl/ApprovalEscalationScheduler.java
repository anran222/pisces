package com.pisces.service.service.impl;

import com.pisces.common.model.ExperimentApprovalEscalation;
import com.pisces.common.model.ExperimentApprovalEscalationDelivery;
import com.pisces.common.model.ExperimentApprovalEscalationNotificationStatus;
import com.pisces.service.repository.ExperimentApprovalEscalationRepository;
import com.pisces.service.service.ApprovalEscalationNotificationDispatcher;
import com.pisces.service.service.ApprovalEscalationNotificationTarget;
import com.pisces.service.service.ExperimentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审批升级告警调度任务
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/17 15:10
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApprovalEscalationScheduler {

    private static final long[] RETRY_DELAY_MINUTES = {1L, 5L, 15L, 60L, 360L};

    private static final int DEFAULT_ATTEMPT_COUNT = 0;

    private static final int ERROR_MESSAGE_MAX_LENGTH = 1024;

    private final ExperimentService experimentService;

    private final ExperimentApprovalEscalationRepository experimentApprovalEscalationRepository;

    private final ApprovalEscalationNotificationDispatcher notificationDispatcher;

    @Value("${pisces.approval-escalation.scan-enabled:true}")
    private boolean scanEnabled;

    @Value("${pisces.approval-escalation.dispatch-enabled:false}")
    private boolean dispatchEnabled;

    @Value("${pisces.approval-escalation.dispatch-batch-size:50}")
    private int dispatchBatchSize;

    @Value("${pisces.approval-escalation.max-retry-count:5}")
    private int maxRetryCount;

    @Value("${pisces.approval-escalation.dispatch-lock-minutes:5}")
    private long dispatchLockMinutes;

    @Scheduled(fixedDelayString = "${pisces.approval-escalation.scan-delay-ms:60000}",
            initialDelayString = "${pisces.approval-escalation.scan-initial-delay-ms:10000}")
    public void scanScheduled() {
        if (!scanEnabled) {
            return;
        }
        try {
            int escalationCount = experimentService.scanApprovalEscalations(null, null).size();
            if (escalationCount > 0) {
                log.info("审批升级告警扫描完成: count={}", escalationCount);
            }
        } catch (Exception exception) {
            log.warn("审批升级告警扫描失败", exception);
        }
    }

    @Scheduled(fixedDelayString = "${pisces.approval-escalation.dispatch-delay-ms:10000}",
            initialDelayString = "${pisces.approval-escalation.dispatch-initial-delay-ms:15000}")
    public void dispatchScheduled() {
        if (!dispatchEnabled || !notificationDispatcher.isEnabled()) {
            return;
        }
        try {
            dispatchDueNotifications();
        } catch (Exception exception) {
            log.warn("审批升级告警投递调度失败", exception);
        }
    }

    public int dispatchDueNotifications() {
        if (!notificationDispatcher.isEnabled()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now();
        List<ExperimentApprovalEscalation> escalations =
                experimentApprovalEscalationRepository.listDispatchable(now, dispatchBatchSize);
        int dispatchedCount = 0;
        for (ExperimentApprovalEscalation escalation : escalations) {
            LocalDateTime lockedUntil = now.plusMinutes(dispatchLockMinutes);
            int claimedCount = experimentApprovalEscalationRepository.markNotificationDispatching(
                    escalation.getEscalationId(), now, lockedUntil);
            if (claimedCount == 0) {
                continue;
            }
            if (dispatchEscalation(escalation, now)) {
                dispatchedCount++;
            }
        }
        return dispatchedCount;
    }

    private boolean dispatchEscalation(ExperimentApprovalEscalation escalation, LocalDateTime now) {
        List<ApprovalEscalationNotificationTarget> targets = notificationDispatcher.targets();
        if (targets.isEmpty()) {
            return false;
        }
        List<ExperimentApprovalEscalationDelivery> deliveries =
                experimentApprovalEscalationRepository.registerDeliveryTargets(
                        escalation.getEscalationId(), buildDeliveryTargets(escalation, targets));
        Map<String, ExperimentApprovalEscalationDelivery> deliveryMap = buildDeliveryMap(deliveries);
        for (ApprovalEscalationNotificationTarget target : targets) {
            dispatchTarget(escalation, target, deliveryMap.get(target.getTargetKey()), now);
        }
        experimentApprovalEscalationRepository.refreshNotificationStatusFromDeliveries(escalation.getEscalationId());
        return true;
    }

    private void dispatchTarget(ExperimentApprovalEscalation escalation, ApprovalEscalationNotificationTarget target,
                                ExperimentApprovalEscalationDelivery delivery, LocalDateTime now) {
        if (delivery != null
                && delivery.getNotificationStatus() == ExperimentApprovalEscalationNotificationStatus.SENT) {
            return;
        }
        LocalDateTime lockedUntil = now.plusMinutes(dispatchLockMinutes);
        int claimedCount = experimentApprovalEscalationRepository.markNotificationDeliveryDispatching(
                escalation.getEscalationId(), target.getTargetKey(), now, lockedUntil);
        if (claimedCount == 0) {
            return;
        }
        int nextAttemptCount = resolveAttemptCount(delivery) + 1;
        try {
            notificationDispatcher.dispatch(escalation, target);
            experimentApprovalEscalationRepository.markNotificationDeliverySent(
                    escalation.getEscalationId(), target.getTargetKey(), nextAttemptCount, LocalDateTime.now());
        } catch (Exception exception) {
            String errorMessage = normalizeErrorMessage(exception);
            if (nextAttemptCount >= maxRetryCount) {
                experimentApprovalEscalationRepository.markNotificationDeliveryDead(
                        escalation.getEscalationId(), target.getTargetKey(), nextAttemptCount,
                        LocalDateTime.now(), errorMessage);
                log.warn("审批升级告警通道投递进入死信: escalationId={}, channel={}, attemptCount={}",
                        escalation.getEscalationId(), target.getChannelName(), nextAttemptCount, exception);
                return;
            }
            LocalDateTime nextRetryAt = resolveNextRetryAt(now, nextAttemptCount);
            experimentApprovalEscalationRepository.markNotificationDeliveryRetry(
                    escalation.getEscalationId(), target.getTargetKey(), nextAttemptCount,
                    LocalDateTime.now(), nextRetryAt, errorMessage);
            log.warn("审批升级告警通道投递失败，将重试: escalationId={}, channel={}, attemptCount={}, nextRetryAt={}",
                    escalation.getEscalationId(), target.getChannelName(), nextAttemptCount, nextRetryAt, exception);
        }
    }

    private List<ExperimentApprovalEscalationDelivery> buildDeliveryTargets(
            ExperimentApprovalEscalation escalation, List<ApprovalEscalationNotificationTarget> targets) {
        return targets.stream()
                .map(target -> buildDeliveryTarget(escalation, target))
                .toList();
    }

    private ExperimentApprovalEscalationDelivery buildDeliveryTarget(
            ExperimentApprovalEscalation escalation, ApprovalEscalationNotificationTarget target) {
        ExperimentApprovalEscalationDelivery delivery = new ExperimentApprovalEscalationDelivery();
        delivery.setEscalationId(escalation.getEscalationId());
        delivery.setChannelName(target.getChannelName());
        delivery.setTargetKey(target.getTargetKey());
        delivery.setNotificationStatus(ExperimentApprovalEscalationNotificationStatus.PENDING);
        delivery.setNotificationAttemptCount(DEFAULT_ATTEMPT_COUNT);
        delivery.setActive(true);
        return delivery;
    }

    private Map<String, ExperimentApprovalEscalationDelivery> buildDeliveryMap(
            List<ExperimentApprovalEscalationDelivery> deliveries) {
        Map<String, ExperimentApprovalEscalationDelivery> deliveryMap = new LinkedHashMap<>();
        for (ExperimentApprovalEscalationDelivery delivery : deliveries) {
            deliveryMap.put(delivery.getTargetKey(), delivery);
        }
        return deliveryMap;
    }

    private int resolveAttemptCount(ExperimentApprovalEscalationDelivery delivery) {
        if (delivery == null) {
            return DEFAULT_ATTEMPT_COUNT;
        }
        Integer attemptCount = delivery.getNotificationAttemptCount();
        return attemptCount == null ? DEFAULT_ATTEMPT_COUNT : attemptCount;
    }

    private LocalDateTime resolveNextRetryAt(LocalDateTime now, int attemptCount) {
        int index = Math.min(Math.max(attemptCount - 1, 0), RETRY_DELAY_MINUTES.length - 1);
        return now.plusMinutes(RETRY_DELAY_MINUTES[index]);
    }

    private String normalizeErrorMessage(Exception exception) {
        String message = exception.getMessage();
        String normalizedMessage = message == null ? exception.getClass().getSimpleName() : message;
        if (normalizedMessage.length() <= ERROR_MESSAGE_MAX_LENGTH) {
            return normalizedMessage;
        }
        return normalizedMessage.substring(0, ERROR_MESSAGE_MAX_LENGTH);
    }
}
