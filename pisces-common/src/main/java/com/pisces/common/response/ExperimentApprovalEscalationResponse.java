package com.pisces.common.response;

import com.pisces.common.model.ExperimentApprovalEscalationNotificationStatus;
import com.pisces.common.model.ExperimentApprovalEscalationStatus;
import com.pisces.common.model.ExperimentApprovalTaskType;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 审批升级告警响应
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/17 14:45
 */
@Data
public class ExperimentApprovalEscalationResponse {

    private String escalationId;

    private String experimentId;

    private ExperimentApprovalTaskType approvalType;

    private Long draftVersion;

    private String appId;

    private String owner;

    private String experimentName;

    private LocalDateTime approvalSubmittedAt;

    private Long approvalElapsedHours;

    private Integer approvalSlaHours;

    private String approvalSlaStatus;

    private List<String> escalationOwners;

    private String escalationReason;

    private String notificationChannel;

    private Map<String, Object> notificationPayload;

    private ExperimentApprovalEscalationNotificationStatus notificationStatus;

    private List<ExperimentApprovalEscalationDeliveryResponse> notificationDeliveries;

    private Integer notificationAttemptCount;

    private LocalDateTime notificationLastAttemptAt;

    private LocalDateTime notificationNextAttemptAt;

    private LocalDateTime notificationDeliveredAt;

    private String notificationLastError;

    private ExperimentApprovalEscalationStatus escalationStatus;

    private String acknowledgedBy;

    private String acknowledgedComment;

    private LocalDateTime acknowledgedAt;

    private String resolvedBy;

    private String resolvedReason;

    private LocalDateTime resolvedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
