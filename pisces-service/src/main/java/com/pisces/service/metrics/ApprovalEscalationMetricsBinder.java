package com.pisces.service.metrics;

import com.pisces.common.model.ExperimentApprovalEscalationNotificationStatus;
import com.pisces.common.model.ExperimentApprovalEscalationStatus;
import com.pisces.service.entity.ExperimentApprovalEscalationStatusCountEntity;
import com.pisces.service.repository.ExperimentApprovalEscalationRepository;
import com.pisces.service.service.ApprovalEscalationNotificationDispatcher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 审批升级告警投递监控指标绑定器
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/17 15:40
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApprovalEscalationMetricsBinder implements MeterBinder {

    private static final String STATUS_TAG = "status";

    private static final String STATUS_TOTAL = "TOTAL";

    private static final String STATUS_UNDELIVERED = "UNDELIVERED";

    private static final String BUSINESS_COUNT_METRIC = "pisces.approval.escalation.business.count";

    private static final String NOTIFICATION_COUNT_METRIC = "pisces.approval.escalation.notification.count";

    private static final String DELIVERY_COUNT_METRIC = "pisces.approval.escalation.delivery.count";

    private static final String DISPATCHER_ENABLED_METRIC = "pisces.approval.escalation.dispatcher.enabled";

    private static final String DISPATCHER_TARGETS_METRIC = "pisces.approval.escalation.dispatcher.targets";

    private static final String REFRESH_HEALTHY_METRIC = "pisces.approval.escalation.metrics.refresh.healthy";

    private static final String LAST_REFRESH_EPOCH_SECONDS_METRIC =
            "pisces.approval.escalation.metrics.last.refresh.epoch.seconds";

    private static final String REFRESH_FAILURES_METRIC = "pisces.approval.escalation.metrics.refresh.failures";

    private final ExperimentApprovalEscalationRepository experimentApprovalEscalationRepository;

    private final ApprovalEscalationNotificationDispatcher approvalEscalationNotificationDispatcher;

    private final Map<String, AtomicLong> businessStatusCounts = buildBusinessStatusCounts();

    private final Map<String, AtomicLong> notificationStatusCounts = buildNotificationStatusCounts();

    private final Map<String, AtomicLong> deliveryStatusCounts = buildNotificationStatusCounts();

    private final AtomicLong dispatcherEnabled = new AtomicLong();

    private final AtomicLong dispatcherTargetCount = new AtomicLong();

    private final AtomicLong refreshHealthy = new AtomicLong();

    private final AtomicLong lastRefreshEpochSeconds = new AtomicLong();

    private Counter refreshFailures;

    @Override
    public void bindTo(MeterRegistry registry) {
        registerStatusGauges(registry, BUSINESS_COUNT_METRIC, businessStatusCounts);
        registerStatusGauges(registry, NOTIFICATION_COUNT_METRIC, notificationStatusCounts);
        registerStatusGauges(registry, DELIVERY_COUNT_METRIC, deliveryStatusCounts);
        Gauge.builder(DISPATCHER_ENABLED_METRIC, dispatcherEnabled, AtomicLong::get)
                .description("审批升级告警投递器是否启用")
                .register(registry);
        Gauge.builder(DISPATCHER_TARGETS_METRIC, dispatcherTargetCount, AtomicLong::get)
                .description("审批升级告警当前投递目标数")
                .register(registry);
        Gauge.builder(REFRESH_HEALTHY_METRIC, refreshHealthy, AtomicLong::get)
                .description("审批升级告警监控指标最近一次刷新是否成功")
                .register(registry);
        Gauge.builder(LAST_REFRESH_EPOCH_SECONDS_METRIC, lastRefreshEpochSeconds, AtomicLong::get)
                .description("审批升级告警监控指标最近一次成功刷新时间")
                .register(registry);
        refreshFailures = Counter.builder(REFRESH_FAILURES_METRIC)
                .description("审批升级告警监控指标刷新失败次数")
                .register(registry);
        refresh();
    }

    @Scheduled(fixedDelayString = "${pisces.approval-escalation.metrics-refresh-delay-ms:30000}",
            initialDelayString = "${pisces.approval-escalation.metrics-refresh-initial-delay-ms:5000}")
    public void refresh() {
        try {
            Map<String, Long> businessCounts = readStatusCounts(
                    experimentApprovalEscalationRepository.countByEscalationStatus(null, null));
            Map<String, Long> notificationCounts = readStatusCounts(
                    experimentApprovalEscalationRepository.countByNotificationStatus(null, null));
            Map<String, Long> deliveryCounts = readStatusCounts(
                    experimentApprovalEscalationRepository.countDeliveryByNotificationStatus(null, null));
            refreshStatusCounts(businessStatusCounts, businessCounts);
            refreshTotalCount(businessStatusCounts);
            refreshStatusCounts(notificationStatusCounts, notificationCounts);
            refreshUndeliveredCount(notificationStatusCounts);
            refreshStatusCounts(deliveryStatusCounts, deliveryCounts);
            refreshUndeliveredCount(deliveryStatusCounts);
            refreshDispatcherMetrics();
            refreshHealthy.set(1L);
            lastRefreshEpochSeconds.set(Instant.now().getEpochSecond());
        } catch (Exception exception) {
            refreshHealthy.set(0L);
            if (refreshFailures != null) {
                refreshFailures.increment();
            }
            log.warn("审批升级告警监控指标刷新失败", exception);
        }
    }

    private static Map<String, AtomicLong> buildBusinessStatusCounts() {
        Map<String, AtomicLong> statusCounts = new LinkedHashMap<>();
        for (ExperimentApprovalEscalationStatus status : ExperimentApprovalEscalationStatus.values()) {
            statusCounts.put(status.name(), new AtomicLong());
        }
        statusCounts.put(STATUS_TOTAL, new AtomicLong());
        return statusCounts;
    }

    private static Map<String, AtomicLong> buildNotificationStatusCounts() {
        Map<String, AtomicLong> statusCounts = new LinkedHashMap<>();
        for (ExperimentApprovalEscalationNotificationStatus status
                : ExperimentApprovalEscalationNotificationStatus.values()) {
            statusCounts.put(status.name(), new AtomicLong());
        }
        statusCounts.put(STATUS_UNDELIVERED, new AtomicLong());
        return statusCounts;
    }

    private void registerStatusGauges(MeterRegistry registry, String metricName,
                                      Map<String, AtomicLong> statusCounts) {
        statusCounts.forEach((status, value) -> Gauge.builder(metricName, value, AtomicLong::get)
                .tag(STATUS_TAG, status)
                .register(registry));
    }

    private Map<String, Long> readStatusCounts(List<ExperimentApprovalEscalationStatusCountEntity> countEntities) {
        Map<String, Long> statusCounts = new HashMap<>();
        if (countEntities == null) {
            return statusCounts;
        }
        for (ExperimentApprovalEscalationStatusCountEntity countEntity : countEntities) {
            if (countEntity == null || countEntity.getStatus() == null) {
                continue;
            }
            long count = countEntity.getEscalationCount() == null ? 0L : countEntity.getEscalationCount();
            statusCounts.put(countEntity.getStatus(), count);
        }
        return statusCounts;
    }

    private void refreshStatusCounts(Map<String, AtomicLong> gaugeValues, Map<String, Long> statusCounts) {
        gaugeValues.forEach((status, value) -> value.set(statusCounts.getOrDefault(status, 0L)));
    }

    private void refreshTotalCount(Map<String, AtomicLong> gaugeValues) {
        long totalCount = gaugeValues.entrySet().stream()
                .filter(entry -> !STATUS_TOTAL.equals(entry.getKey()))
                .mapToLong(entry -> entry.getValue().get())
                .sum();
        gaugeValues.get(STATUS_TOTAL).set(totalCount);
    }

    private void refreshUndeliveredCount(Map<String, AtomicLong> gaugeValues) {
        long undeliveredCount = gaugeValues.get(ExperimentApprovalEscalationNotificationStatus.PENDING.name()).get()
                + gaugeValues.get(ExperimentApprovalEscalationNotificationStatus.DISPATCHING.name()).get()
                + gaugeValues.get(ExperimentApprovalEscalationNotificationStatus.RETRY.name()).get()
                + gaugeValues.get(ExperimentApprovalEscalationNotificationStatus.DEAD.name()).get();
        gaugeValues.get(STATUS_UNDELIVERED).set(undeliveredCount);
    }

    private void refreshDispatcherMetrics() {
        if (!approvalEscalationNotificationDispatcher.isEnabled()) {
            dispatcherEnabled.set(0L);
            dispatcherTargetCount.set(0L);
            return;
        }
        dispatcherEnabled.set(1L);
        dispatcherTargetCount.set(approvalEscalationNotificationDispatcher.targetCount());
    }
}
