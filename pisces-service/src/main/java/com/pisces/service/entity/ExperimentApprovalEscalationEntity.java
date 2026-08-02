package com.pisces.service.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 实验审批升级告警实体
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/17 14:45
 */
@Data
public class ExperimentApprovalEscalationEntity {

    private Long id;

    private String escalationId;

    private String experimentId;

    private String approvalType;

    private Long draftVersion;

    private String appId;

    private String owner;

    private String experimentName;

    private LocalDateTime approvalSubmittedAt;

    private Long approvalElapsedHours;

    private Integer approvalSlaHours;

    private String approvalSlaStatus;

    private String escalationOwners;

    private String escalationReason;

    private String notificationChannel;

    private String notificationPayloadJson;

    private String notificationStatus;

    private Integer notificationAttemptCount;

    private LocalDateTime notificationLastAttemptAt;

    private LocalDateTime notificationNextAttemptAt;

    private LocalDateTime notificationDeliveredAt;

    private String notificationLastError;

    private String escalationStatus;

    private String acknowledgedBy;

    private String acknowledgedComment;

    private LocalDateTime acknowledgedAt;

    private String resolvedBy;

    private String resolvedReason;

    private LocalDateTime resolvedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
