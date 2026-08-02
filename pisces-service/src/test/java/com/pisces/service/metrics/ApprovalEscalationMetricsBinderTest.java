package com.pisces.service.metrics;

import com.pisces.common.model.ExperimentApprovalEscalationNotificationStatus;
import com.pisces.common.model.ExperimentApprovalEscalationStatus;
import com.pisces.service.entity.ExperimentApprovalEscalationStatusCountEntity;
import com.pisces.service.repository.ExperimentApprovalEscalationRepository;
import com.pisces.service.service.ApprovalEscalationNotificationDispatcher;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApprovalEscalationMetricsBinderTest {

    private static final String BUSINESS_COUNT_METRIC = "pisces.approval.escalation.business.count";

    private static final String NOTIFICATION_COUNT_METRIC = "pisces.approval.escalation.notification.count";

    private static final String DELIVERY_COUNT_METRIC = "pisces.approval.escalation.delivery.count";

    private static final String DISPATCHER_ENABLED_METRIC = "pisces.approval.escalation.dispatcher.enabled";

    private static final String DISPATCHER_TARGETS_METRIC = "pisces.approval.escalation.dispatcher.targets";

    private static final String STATUS_TAG = "status";

    @Mock
    private ExperimentApprovalEscalationRepository experimentApprovalEscalationRepository;

    @Mock
    private ApprovalEscalationNotificationDispatcher approvalEscalationNotificationDispatcher;

    @Test
    void bindToShouldExposeApprovalEscalationMetrics() {
        when(experimentApprovalEscalationRepository.countByEscalationStatus(null, null))
                .thenReturn(List.of(
                        statusCount(ExperimentApprovalEscalationStatus.OPEN.name(), 2L),
                        statusCount(ExperimentApprovalEscalationStatus.ACKNOWLEDGED.name(), 1L)));
        when(experimentApprovalEscalationRepository.countByNotificationStatus(null, null))
                .thenReturn(List.of(
                        statusCount(ExperimentApprovalEscalationNotificationStatus.PENDING.name(), 1L),
                        statusCount(ExperimentApprovalEscalationNotificationStatus.RETRY.name(), 2L)));
        when(experimentApprovalEscalationRepository.countDeliveryByNotificationStatus(null, null))
                .thenReturn(List.of(
                        statusCount(ExperimentApprovalEscalationNotificationStatus.SENT.name(), 3L),
                        statusCount(ExperimentApprovalEscalationNotificationStatus.DEAD.name(), 1L)));
        when(approvalEscalationNotificationDispatcher.isEnabled()).thenReturn(true);
        when(approvalEscalationNotificationDispatcher.targetCount()).thenReturn(2);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ApprovalEscalationMetricsBinder metricsBinder = new ApprovalEscalationMetricsBinder(
                experimentApprovalEscalationRepository, approvalEscalationNotificationDispatcher);

        metricsBinder.bindTo(registry);

        assertGauge(registry, BUSINESS_COUNT_METRIC, ExperimentApprovalEscalationStatus.OPEN.name(), 2.0D);
        assertGauge(registry, BUSINESS_COUNT_METRIC, "TOTAL", 3.0D);
        assertGauge(registry, NOTIFICATION_COUNT_METRIC,
                ExperimentApprovalEscalationNotificationStatus.PENDING.name(), 1.0D);
        assertGauge(registry, NOTIFICATION_COUNT_METRIC, "UNDELIVERED", 3.0D);
        assertGauge(registry, DELIVERY_COUNT_METRIC,
                ExperimentApprovalEscalationNotificationStatus.SENT.name(), 3.0D);
        assertGauge(registry, DELIVERY_COUNT_METRIC, "UNDELIVERED", 1.0D);
        assertThat(registry.find(DISPATCHER_ENABLED_METRIC).gauge().value()).isEqualTo(1.0D);
        assertThat(registry.find(DISPATCHER_TARGETS_METRIC).gauge().value()).isEqualTo(2.0D);
    }

    private void assertGauge(SimpleMeterRegistry registry, String metricName, String status, double expectedValue) {
        Gauge gauge = registry.find(metricName)
                .tag(STATUS_TAG, status)
                .gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(expectedValue);
    }

    private ExperimentApprovalEscalationStatusCountEntity statusCount(String status, long count) {
        ExperimentApprovalEscalationStatusCountEntity entity = new ExperimentApprovalEscalationStatusCountEntity();
        entity.setStatus(status);
        entity.setEscalationCount(count);
        return entity;
    }
}
