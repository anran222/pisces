package com.pisces.service.repository.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.pisces.common.model.ExperimentApprovalEscalation;
import com.pisces.common.model.ExperimentApprovalEscalationDelivery;
import com.pisces.common.model.ExperimentApprovalEscalationNotificationStatus;
import com.pisces.common.model.ExperimentApprovalEscalationStatus;
import com.pisces.common.model.ExperimentApprovalTaskType;
import com.pisces.service.entity.ExperimentApprovalEscalationDeliveryEntity;
import com.pisces.service.entity.ExperimentApprovalEscalationEntity;
import com.pisces.service.entity.ExperimentApprovalEscalationStatusCountEntity;
import com.pisces.service.mapper.ExperimentApprovalEscalationMapper;
import com.pisces.service.util.JsonUtil;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 数据库实验审批升级告警仓库
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/17 14:45
 */
@Repository
@AllArgsConstructor
public class ExperimentApprovalEscalationRepository
        implements com.pisces.service.repository.ExperimentApprovalEscalationRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ExperimentApprovalEscalationMapper experimentApprovalEscalationMapper;

    private final JsonUtil jsonUtil;

    @Override
    public ExperimentApprovalEscalation save(ExperimentApprovalEscalation escalation) {
        ExperimentApprovalEscalationEntity entity = buildEntity(escalation);
        experimentApprovalEscalationMapper.upsert(entity);
        return findByTask(escalation.getExperimentId(), escalation.getApprovalType(),
                escalation.getDraftVersion(), escalation.getApprovalSubmittedAt()).orElse(escalation);
    }

    @Override
    public Optional<ExperimentApprovalEscalation> findByEscalationId(String escalationId) {
        return Optional.ofNullable(experimentApprovalEscalationMapper.selectByEscalationId(escalationId))
                .map(this::buildEscalation);
    }

    @Override
    public Optional<ExperimentApprovalEscalation> findByTask(String experimentId,
                                                             ExperimentApprovalTaskType approvalType,
                                                             long draftVersion,
                                                             LocalDateTime approvalSubmittedAt) {
        String approvalTypeCode = approvalType == null ? null : approvalType.name();
        return Optional.ofNullable(experimentApprovalEscalationMapper.selectByTask(
                        experimentId, approvalTypeCode, draftVersion, approvalSubmittedAt))
                .map(this::buildEscalation);
    }

    @Override
    public List<ExperimentApprovalEscalation> list(String appId, String owner, String escalationStatus, int limit) {
        return experimentApprovalEscalationMapper.list(appId, owner, escalationStatus, limit).stream()
                .map(this::buildEscalation)
                .toList();
    }

    @Override
    public List<ExperimentApprovalEscalation> listDispatchable(LocalDateTime now, int limit) {
        return experimentApprovalEscalationMapper.listDispatchable(now, limit).stream()
                .map(this::buildEscalation)
                .toList();
    }

    @Override
    public List<ExperimentApprovalEscalationDelivery> registerDeliveryTargets(
            String escalationId, List<ExperimentApprovalEscalationDelivery> deliveries) {
        if (deliveries == null || deliveries.isEmpty()) {
            return List.of();
        }
        List<ExperimentApprovalEscalationDeliveryEntity> entities = deliveries.stream()
                .map(this::buildDeliveryEntity)
                .toList();
        List<String> targetKeys = deliveries.stream()
                .map(ExperimentApprovalEscalationDelivery::getTargetKey)
                .toList();
        experimentApprovalEscalationMapper.upsertDeliveries(entities);
        experimentApprovalEscalationMapper.deactivateStaleDeliveries(escalationId, targetKeys);
        return listDeliveries(escalationId);
    }

    @Override
    public List<ExperimentApprovalEscalationDelivery> listDeliveries(String escalationId) {
        if (!StringUtils.hasText(escalationId)) {
            return List.of();
        }
        return experimentApprovalEscalationMapper.listDeliveriesByEscalationId(escalationId).stream()
                .map(this::buildDelivery)
                .toList();
    }

    @Override
    public List<ExperimentApprovalEscalationDelivery> listDeliveries(List<String> escalationIds) {
        if (escalationIds == null || escalationIds.isEmpty()) {
            return List.of();
        }
        List<String> normalizedEscalationIds = escalationIds.stream()
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        if (normalizedEscalationIds.isEmpty()) {
            return List.of();
        }
        return experimentApprovalEscalationMapper.listDeliveriesByEscalationIds(normalizedEscalationIds).stream()
                .map(this::buildDelivery)
                .toList();
    }

    @Override
    public List<ExperimentApprovalEscalationStatusCountEntity> countByEscalationStatus(String appId, String owner) {
        return experimentApprovalEscalationMapper.countByEscalationStatus(appId, owner);
    }

    @Override
    public List<ExperimentApprovalEscalationStatusCountEntity> countByNotificationStatus(String appId, String owner) {
        return experimentApprovalEscalationMapper.countByNotificationStatus(appId, owner);
    }

    @Override
    public List<ExperimentApprovalEscalationStatusCountEntity> countDeliveryByNotificationStatus(
            String appId, String owner) {
        return experimentApprovalEscalationMapper.countDeliveryByNotificationStatus(appId, owner);
    }

    @Override
    public int acknowledge(String escalationId, String operator, String comment, LocalDateTime acknowledgedAt) {
        return experimentApprovalEscalationMapper.acknowledge(escalationId, operator, comment, acknowledgedAt);
    }

    @Override
    public int resolveByTask(String experimentId, ExperimentApprovalTaskType approvalType, long draftVersion,
                             String operator, String reason, LocalDateTime resolvedAt) {
        String approvalTypeCode = approvalType == null ? null : approvalType.name();
        return experimentApprovalEscalationMapper.resolveByTask(experimentId, approvalTypeCode, draftVersion,
                operator, reason, resolvedAt);
    }

    @Override
    public int markNotificationDispatching(String escalationId, LocalDateTime now, LocalDateTime lockedUntil) {
        return experimentApprovalEscalationMapper.markNotificationDispatching(escalationId, now, lockedUntil);
    }

    @Override
    public int markNotificationSent(String escalationId, int attemptCount, LocalDateTime deliveredAt) {
        return experimentApprovalEscalationMapper.markNotificationSent(escalationId, attemptCount, deliveredAt);
    }

    @Override
    public int markNotificationRetry(String escalationId, int attemptCount, LocalDateTime lastAttemptAt,
                                     LocalDateTime nextAttemptAt, String lastError) {
        return experimentApprovalEscalationMapper.markNotificationRetry(escalationId, attemptCount, lastAttemptAt,
                nextAttemptAt, lastError);
    }

    @Override
    public int markNotificationDead(String escalationId, int attemptCount, LocalDateTime lastAttemptAt,
                                    String lastError) {
        return experimentApprovalEscalationMapper.markNotificationDead(escalationId, attemptCount, lastAttemptAt,
                lastError);
    }

    @Override
    public int markNotificationDeliveryDispatching(String escalationId, String targetKey,
                                                   LocalDateTime now, LocalDateTime lockedUntil) {
        return experimentApprovalEscalationMapper.markNotificationDeliveryDispatching(
                escalationId, targetKey, now, lockedUntil);
    }

    @Override
    public int markNotificationDeliverySent(String escalationId, String targetKey,
                                            int attemptCount, LocalDateTime deliveredAt) {
        return experimentApprovalEscalationMapper.markNotificationDeliverySent(
                escalationId, targetKey, attemptCount, deliveredAt);
    }

    @Override
    public int markNotificationDeliveryRetry(String escalationId, String targetKey, int attemptCount,
                                             LocalDateTime lastAttemptAt, LocalDateTime nextAttemptAt,
                                             String lastError) {
        return experimentApprovalEscalationMapper.markNotificationDeliveryRetry(
                escalationId, targetKey, attemptCount, lastAttemptAt, nextAttemptAt, lastError);
    }

    @Override
    public int markNotificationDeliveryDead(String escalationId, String targetKey, int attemptCount,
                                            LocalDateTime lastAttemptAt, String lastError) {
        return experimentApprovalEscalationMapper.markNotificationDeliveryDead(
                escalationId, targetKey, attemptCount, lastAttemptAt, lastError);
    }

    @Override
    public int refreshNotificationStatusFromDeliveries(String escalationId) {
        return experimentApprovalEscalationMapper.refreshNotificationStatusFromDeliveries(escalationId);
    }

    @Override
    public int retryDeadNotification(String escalationId, LocalDateTime nextAttemptAt) {
        int affectedCount = experimentApprovalEscalationMapper.retryDeadNotification(escalationId, nextAttemptAt);
        experimentApprovalEscalationMapper.retryDeadNotificationDeliveries(escalationId, nextAttemptAt);
        return affectedCount;
    }

    @Override
    public int retryDeadNotifications(String appId, String owner, LocalDateTime nextAttemptAt) {
        int affectedCount = experimentApprovalEscalationMapper.retryDeadNotifications(appId, owner, nextAttemptAt);
        experimentApprovalEscalationMapper.retryDeadNotificationDeliveriesByFilter(appId, owner, nextAttemptAt);
        return affectedCount;
    }

    private ExperimentApprovalEscalationEntity buildEntity(ExperimentApprovalEscalation escalation) {
        ExperimentApprovalEscalationEntity entity = new ExperimentApprovalEscalationEntity();
        entity.setEscalationId(escalation.getEscalationId());
        entity.setExperimentId(escalation.getExperimentId());
        entity.setApprovalType(escalation.getApprovalType().name());
        entity.setDraftVersion(escalation.getDraftVersion());
        entity.setAppId(escalation.getAppId());
        entity.setOwner(escalation.getOwner());
        entity.setExperimentName(escalation.getExperimentName());
        entity.setApprovalSubmittedAt(escalation.getApprovalSubmittedAt());
        entity.setApprovalElapsedHours(escalation.getApprovalElapsedHours());
        entity.setApprovalSlaHours(escalation.getApprovalSlaHours());
        entity.setApprovalSlaStatus(escalation.getApprovalSlaStatus());
        entity.setEscalationOwners(serializeOwners(escalation.getEscalationOwners()));
        entity.setEscalationReason(escalation.getEscalationReason());
        entity.setNotificationChannel(escalation.getNotificationChannel());
        entity.setNotificationPayloadJson(jsonUtil.toJson(escalation.getNotificationPayload()));
        entity.setNotificationStatus(ExperimentApprovalEscalationNotificationStatus.PENDING.name());
        entity.setNotificationAttemptCount(0);
        entity.setEscalationStatus(escalation.getEscalationStatus().name());
        return entity;
    }

    private ExperimentApprovalEscalationDeliveryEntity buildDeliveryEntity(
            ExperimentApprovalEscalationDelivery delivery) {
        ExperimentApprovalEscalationDeliveryEntity entity = new ExperimentApprovalEscalationDeliveryEntity();
        entity.setEscalationId(delivery.getEscalationId());
        entity.setChannelName(delivery.getChannelName());
        entity.setTargetKey(delivery.getTargetKey());
        ExperimentApprovalEscalationNotificationStatus notificationStatus = delivery.getNotificationStatus();
        entity.setNotificationStatus(notificationStatus == null
                ? ExperimentApprovalEscalationNotificationStatus.PENDING.name()
                : notificationStatus.name());
        Integer attemptCount = delivery.getNotificationAttemptCount();
        entity.setNotificationAttemptCount(attemptCount == null ? 0 : attemptCount);
        entity.setActive(delivery.getActive() == null || delivery.getActive());
        return entity;
    }

    private ExperimentApprovalEscalation buildEscalation(ExperimentApprovalEscalationEntity entity) {
        ExperimentApprovalEscalation escalation = new ExperimentApprovalEscalation();
        escalation.setId(entity.getId());
        escalation.setEscalationId(entity.getEscalationId());
        escalation.setExperimentId(entity.getExperimentId());
        escalation.setApprovalType(ExperimentApprovalTaskType.ofOrThrow(entity.getApprovalType()));
        escalation.setDraftVersion(entity.getDraftVersion());
        escalation.setAppId(entity.getAppId());
        escalation.setOwner(entity.getOwner());
        escalation.setExperimentName(entity.getExperimentName());
        escalation.setApprovalSubmittedAt(entity.getApprovalSubmittedAt());
        escalation.setApprovalElapsedHours(entity.getApprovalElapsedHours());
        escalation.setApprovalSlaHours(entity.getApprovalSlaHours());
        escalation.setApprovalSlaStatus(entity.getApprovalSlaStatus());
        escalation.setEscalationOwners(deserializeOwners(entity.getEscalationOwners()));
        escalation.setEscalationReason(entity.getEscalationReason());
        escalation.setNotificationChannel(entity.getNotificationChannel());
        escalation.setNotificationPayload(readMap(entity.getNotificationPayloadJson()));
        escalation.setNotificationStatus(
                ExperimentApprovalEscalationNotificationStatus.ofOrThrow(entity.getNotificationStatus()));
        escalation.setNotificationAttemptCount(entity.getNotificationAttemptCount());
        escalation.setNotificationLastAttemptAt(entity.getNotificationLastAttemptAt());
        escalation.setNotificationNextAttemptAt(entity.getNotificationNextAttemptAt());
        escalation.setNotificationDeliveredAt(entity.getNotificationDeliveredAt());
        escalation.setNotificationLastError(entity.getNotificationLastError());
        escalation.setEscalationStatus(ExperimentApprovalEscalationStatus.ofOrThrow(entity.getEscalationStatus()));
        escalation.setAcknowledgedBy(entity.getAcknowledgedBy());
        escalation.setAcknowledgedComment(entity.getAcknowledgedComment());
        escalation.setAcknowledgedAt(entity.getAcknowledgedAt());
        escalation.setResolvedBy(entity.getResolvedBy());
        escalation.setResolvedReason(entity.getResolvedReason());
        escalation.setResolvedAt(entity.getResolvedAt());
        escalation.setCreatedAt(entity.getCreatedAt());
        escalation.setUpdatedAt(entity.getUpdatedAt());
        return escalation;
    }

    private ExperimentApprovalEscalationDelivery buildDelivery(ExperimentApprovalEscalationDeliveryEntity entity) {
        ExperimentApprovalEscalationDelivery delivery = new ExperimentApprovalEscalationDelivery();
        delivery.setId(entity.getId());
        delivery.setEscalationId(entity.getEscalationId());
        delivery.setChannelName(entity.getChannelName());
        delivery.setTargetKey(entity.getTargetKey());
        delivery.setNotificationStatus(
                ExperimentApprovalEscalationNotificationStatus.ofOrThrow(entity.getNotificationStatus()));
        delivery.setNotificationAttemptCount(entity.getNotificationAttemptCount());
        delivery.setNotificationLastAttemptAt(entity.getNotificationLastAttemptAt());
        delivery.setNotificationNextAttemptAt(entity.getNotificationNextAttemptAt());
        delivery.setNotificationDeliveredAt(entity.getNotificationDeliveredAt());
        delivery.setNotificationLastError(entity.getNotificationLastError());
        delivery.setActive(entity.getActive());
        delivery.setCreatedAt(entity.getCreatedAt());
        delivery.setUpdatedAt(entity.getUpdatedAt());
        return delivery;
    }

    private String serializeOwners(List<String> owners) {
        if (owners == null || owners.isEmpty()) {
            return null;
        }
        LinkedHashSet<String> normalizedOwners = new LinkedHashSet<>();
        for (String owner : owners) {
            if (StringUtils.hasText(owner)) {
                normalizedOwners.add(owner.trim());
            }
        }
        return normalizedOwners.isEmpty() ? null : String.join(",", normalizedOwners);
    }

    private List<String> deserializeOwners(String owners) {
        if (!StringUtils.hasText(owners)) {
            return List.of();
        }
        LinkedHashSet<String> normalizedOwners = new LinkedHashSet<>();
        for (String owner : owners.split(",")) {
            if (StringUtils.hasText(owner)) {
                normalizedOwners.add(owner.trim());
            }
        }
        return normalizedOwners.stream().toList();
    }

    private Map<String, Object> readMap(String json) {
        if (!StringUtils.hasText(json)) {
            return Collections.emptyMap();
        }
        return jsonUtil.toObject(json, MAP_TYPE);
    }
}
