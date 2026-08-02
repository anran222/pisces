package com.pisces.service.service.impl;

import com.pisces.common.model.ExperimentApprovalEscalation;
import com.pisces.common.model.ExperimentApprovalEscalationDelivery;
import com.pisces.common.model.ExperimentApprovalEscalationNotificationStatus;
import com.pisces.common.model.ExperimentApprovalEscalationStatus;
import com.pisces.common.model.ExperimentApprovalTaskType;
import com.pisces.service.repository.ExperimentApprovalEscalationRepository;
import com.pisces.service.service.ApprovalEscalationNotificationDispatcher;
import com.pisces.service.service.ApprovalEscalationNotificationTarget;
import com.pisces.service.service.ExperimentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApprovalEscalationSchedulerTest {

    private static final int DISPATCH_BATCH_SIZE = 10;

    @Mock
    private ExperimentService experimentService;

    @Mock
    private ExperimentApprovalEscalationRepository experimentApprovalEscalationRepository;

    @Mock
    private ApprovalEscalationNotificationDispatcher notificationDispatcher;

    private ApprovalEscalationScheduler approvalEscalationScheduler;

    @BeforeEach
    void setUp() {
        approvalEscalationScheduler = new ApprovalEscalationScheduler(
                experimentService, experimentApprovalEscalationRepository, notificationDispatcher);
        ReflectionTestUtils.setField(approvalEscalationScheduler, "scanEnabled", true);
        ReflectionTestUtils.setField(approvalEscalationScheduler, "dispatchEnabled", true);
        ReflectionTestUtils.setField(approvalEscalationScheduler, "dispatchBatchSize", DISPATCH_BATCH_SIZE);
        ReflectionTestUtils.setField(approvalEscalationScheduler, "dispatchLockMinutes", 5L);
        ReflectionTestUtils.setField(approvalEscalationScheduler, "maxRetryCount", 5);
    }

    @Test
    void scanScheduledShouldDelegateToExperimentServiceWhenEnabled() {
        when(experimentService.scanApprovalEscalations(null, null)).thenReturn(List.of());

        approvalEscalationScheduler.scanScheduled();

        verify(experimentService).scanApprovalEscalations(null, null);
    }

    @Test
    void dispatchDueNotificationsShouldSkipWhenDispatcherDisabled() {
        when(notificationDispatcher.isEnabled()).thenReturn(false);

        int dispatchedCount = approvalEscalationScheduler.dispatchDueNotifications();

        assertThat(dispatchedCount).isZero();
        verify(experimentApprovalEscalationRepository, never()).listDispatchable(
                any(LocalDateTime.class), anyInt());
    }

    @Test
    void dispatchDueNotificationsShouldMarkSentWhenDispatcherSucceeds() {
        ExperimentApprovalEscalation escalation = escalation("esc-success", 0);
        ApprovalEscalationNotificationTarget target = target("target-a", "lark");
        ExperimentApprovalEscalationDelivery delivery = delivery("esc-success", "target-a", 0,
                ExperimentApprovalEscalationNotificationStatus.PENDING);
        when(notificationDispatcher.isEnabled()).thenReturn(true);
        when(notificationDispatcher.targets()).thenReturn(List.of(target));
        when(experimentApprovalEscalationRepository.listDispatchable(
                any(LocalDateTime.class), eq(DISPATCH_BATCH_SIZE))).thenReturn(List.of(escalation));
        when(experimentApprovalEscalationRepository.markNotificationDispatching(
                eq("esc-success"), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(1);
        when(experimentApprovalEscalationRepository.registerDeliveryTargets(eq("esc-success"), any()))
                .thenReturn(List.of(delivery));
        when(experimentApprovalEscalationRepository.markNotificationDeliveryDispatching(
                eq("esc-success"), eq("target-a"), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(1);

        int dispatchedCount = approvalEscalationScheduler.dispatchDueNotifications();

        assertThat(dispatchedCount).isEqualTo(1);
        verify(notificationDispatcher).dispatch(escalation, target);
        verify(experimentApprovalEscalationRepository).markNotificationDeliverySent(
                eq("esc-success"), eq("target-a"), eq(1), any(LocalDateTime.class));
        verify(experimentApprovalEscalationRepository).refreshNotificationStatusFromDeliveries("esc-success");
        verify(experimentApprovalEscalationRepository, never()).markNotificationDeliveryRetry(
                anyString(), anyString(), anyInt(), any(LocalDateTime.class), any(LocalDateTime.class), anyString());
        verify(experimentApprovalEscalationRepository, never()).markNotificationDeliveryDead(
                anyString(), anyString(), anyInt(), any(LocalDateTime.class), anyString());
    }

    @Test
    void dispatchDueNotificationsShouldMarkRetryWhenDispatcherFailsBelowMaxRetryCount() {
        ExperimentApprovalEscalation escalation = escalation("esc-retry", 1);
        ApprovalEscalationNotificationTarget target = target("target-a", "lark");
        ExperimentApprovalEscalationDelivery delivery = delivery("esc-retry", "target-a", 1,
                ExperimentApprovalEscalationNotificationStatus.RETRY);
        when(notificationDispatcher.isEnabled()).thenReturn(true);
        when(notificationDispatcher.targets()).thenReturn(List.of(target));
        when(experimentApprovalEscalationRepository.listDispatchable(
                any(LocalDateTime.class), eq(DISPATCH_BATCH_SIZE))).thenReturn(List.of(escalation));
        when(experimentApprovalEscalationRepository.markNotificationDispatching(
                eq("esc-retry"), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(1);
        when(experimentApprovalEscalationRepository.registerDeliveryTargets(eq("esc-retry"), any()))
                .thenReturn(List.of(delivery));
        when(experimentApprovalEscalationRepository.markNotificationDeliveryDispatching(
                eq("esc-retry"), eq("target-a"), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(1);
        doThrow(new IllegalStateException("webhook timeout")).when(notificationDispatcher).dispatch(escalation, target);
        ArgumentCaptor<LocalDateTime> lastAttemptAtCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> nextAttemptAtCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<String> lastErrorCaptor = ArgumentCaptor.forClass(String.class);

        int dispatchedCount = approvalEscalationScheduler.dispatchDueNotifications();

        assertThat(dispatchedCount).isEqualTo(1);
        verify(experimentApprovalEscalationRepository).markNotificationDeliveryRetry(
                eq("esc-retry"), eq("target-a"), eq(2), lastAttemptAtCaptor.capture(),
                nextAttemptAtCaptor.capture(), lastErrorCaptor.capture());
        assertThat(nextAttemptAtCaptor.getValue()).isAfter(lastAttemptAtCaptor.getValue());
        assertThat(lastErrorCaptor.getValue()).contains("webhook timeout");
        verify(experimentApprovalEscalationRepository).refreshNotificationStatusFromDeliveries("esc-retry");
        verify(experimentApprovalEscalationRepository, never()).markNotificationDeliverySent(
                anyString(), anyString(), anyInt(), any(LocalDateTime.class));
        verify(experimentApprovalEscalationRepository, never()).markNotificationDeliveryDead(
                anyString(), anyString(), anyInt(), any(LocalDateTime.class), anyString());
    }

    @Test
    void dispatchDueNotificationsShouldMarkDeadWhenDispatcherFailsAtMaxRetryCount() {
        ReflectionTestUtils.setField(approvalEscalationScheduler, "maxRetryCount", 2);
        ExperimentApprovalEscalation escalation = escalation("esc-dead", 1);
        ApprovalEscalationNotificationTarget target = target("target-a", "lark");
        ExperimentApprovalEscalationDelivery delivery = delivery("esc-dead", "target-a", 1,
                ExperimentApprovalEscalationNotificationStatus.RETRY);
        when(notificationDispatcher.isEnabled()).thenReturn(true);
        when(notificationDispatcher.targets()).thenReturn(List.of(target));
        when(experimentApprovalEscalationRepository.listDispatchable(
                any(LocalDateTime.class), eq(DISPATCH_BATCH_SIZE))).thenReturn(List.of(escalation));
        when(experimentApprovalEscalationRepository.markNotificationDispatching(
                eq("esc-dead"), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(1);
        when(experimentApprovalEscalationRepository.registerDeliveryTargets(eq("esc-dead"), any()))
                .thenReturn(List.of(delivery));
        when(experimentApprovalEscalationRepository.markNotificationDeliveryDispatching(
                eq("esc-dead"), eq("target-a"), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(1);
        doThrow(new IllegalStateException("webhook timeout")).when(notificationDispatcher).dispatch(escalation, target);
        ArgumentCaptor<String> lastErrorCaptor = ArgumentCaptor.forClass(String.class);

        int dispatchedCount = approvalEscalationScheduler.dispatchDueNotifications();

        assertThat(dispatchedCount).isEqualTo(1);
        verify(experimentApprovalEscalationRepository).markNotificationDeliveryDead(
                eq("esc-dead"), eq("target-a"), eq(2), any(LocalDateTime.class), lastErrorCaptor.capture());
        assertThat(lastErrorCaptor.getValue()).contains("webhook timeout");
        verify(experimentApprovalEscalationRepository).refreshNotificationStatusFromDeliveries("esc-dead");
        verify(experimentApprovalEscalationRepository, never()).markNotificationDeliverySent(
                anyString(), anyString(), anyInt(), any(LocalDateTime.class));
        verify(experimentApprovalEscalationRepository, never()).markNotificationDeliveryRetry(
                anyString(), anyString(), anyInt(), any(LocalDateTime.class), any(LocalDateTime.class), anyString());
    }

    @Test
    void dispatchDueNotificationsShouldSkipAlreadySentDeliveryTarget() {
        ExperimentApprovalEscalation escalation = escalation("esc-partial", 1);
        ApprovalEscalationNotificationTarget sentTarget = target("target-a", "lark");
        ApprovalEscalationNotificationTarget retryTarget = target("target-b", "slack");
        ExperimentApprovalEscalationDelivery sentDelivery = delivery("esc-partial", "target-a", 1,
                ExperimentApprovalEscalationNotificationStatus.SENT);
        ExperimentApprovalEscalationDelivery retryDelivery = delivery("esc-partial", "target-b", 1,
                ExperimentApprovalEscalationNotificationStatus.RETRY);
        when(notificationDispatcher.isEnabled()).thenReturn(true);
        when(notificationDispatcher.targets()).thenReturn(List.of(sentTarget, retryTarget));
        when(experimentApprovalEscalationRepository.listDispatchable(
                any(LocalDateTime.class), eq(DISPATCH_BATCH_SIZE))).thenReturn(List.of(escalation));
        when(experimentApprovalEscalationRepository.markNotificationDispatching(
                eq("esc-partial"), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(1);
        when(experimentApprovalEscalationRepository.registerDeliveryTargets(eq("esc-partial"), any()))
                .thenReturn(List.of(sentDelivery, retryDelivery));
        when(experimentApprovalEscalationRepository.markNotificationDeliveryDispatching(
                eq("esc-partial"), eq("target-b"), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(1);

        int dispatchedCount = approvalEscalationScheduler.dispatchDueNotifications();

        assertThat(dispatchedCount).isEqualTo(1);
        verify(notificationDispatcher, never()).dispatch(escalation, sentTarget);
        verify(notificationDispatcher).dispatch(escalation, retryTarget);
        verify(experimentApprovalEscalationRepository).markNotificationDeliverySent(
                eq("esc-partial"), eq("target-b"), eq(2), any(LocalDateTime.class));
        verify(experimentApprovalEscalationRepository).refreshNotificationStatusFromDeliveries("esc-partial");
    }

    private ExperimentApprovalEscalation escalation(String escalationId, int attemptCount) {
        ExperimentApprovalEscalation escalation = new ExperimentApprovalEscalation();
        escalation.setEscalationId(escalationId);
        escalation.setExperimentId("exp-a");
        escalation.setApprovalType(ExperimentApprovalTaskType.EXPERIMENT_START);
        escalation.setDraftVersion(0L);
        escalation.setAppId("app-a");
        escalation.setOwner("owner-a");
        escalation.setExperimentName("实验A");
        escalation.setApprovalSubmittedAt(LocalDateTime.now().minusHours(5));
        escalation.setApprovalElapsedHours(5L);
        escalation.setApprovalSlaHours(4);
        escalation.setApprovalSlaStatus("OVERDUE");
        escalation.setEscalationOwners(List.of("reviewer-a"));
        escalation.setEscalationReason("审批超时");
        escalation.setNotificationChannel("WEBHOOK");
        escalation.setNotificationPayload(Map.of("messageType", "APPROVAL_ESCALATION"));
        escalation.setNotificationStatus(ExperimentApprovalEscalationNotificationStatus.PENDING);
        escalation.setNotificationAttemptCount(attemptCount);
        escalation.setEscalationStatus(ExperimentApprovalEscalationStatus.OPEN);
        return escalation;
    }

    private ApprovalEscalationNotificationTarget target(String targetKey, String channelName) {
        return new ApprovalEscalationNotificationTarget(targetKey, channelName, "http://localhost/" + channelName);
    }

    private ExperimentApprovalEscalationDelivery delivery(String escalationId, String targetKey, int attemptCount,
                                                         ExperimentApprovalEscalationNotificationStatus status) {
        ExperimentApprovalEscalationDelivery delivery = new ExperimentApprovalEscalationDelivery();
        delivery.setEscalationId(escalationId);
        delivery.setTargetKey(targetKey);
        delivery.setChannelName(targetKey);
        delivery.setNotificationAttemptCount(attemptCount);
        delivery.setNotificationStatus(status);
        delivery.setActive(true);
        return delivery;
    }
}
