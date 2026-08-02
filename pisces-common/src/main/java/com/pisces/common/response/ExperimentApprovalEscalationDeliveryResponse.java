package com.pisces.common.response;

import com.pisces.common.model.ExperimentApprovalEscalationNotificationStatus;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审批升级告警通道投递回执响应
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/17 15:25
 */
@Data
public class ExperimentApprovalEscalationDeliveryResponse {

    private String escalationId;

    private String channelName;

    private String targetKey;

    private ExperimentApprovalEscalationNotificationStatus notificationStatus;

    private Integer notificationAttemptCount;

    private LocalDateTime notificationLastAttemptAt;

    private LocalDateTime notificationNextAttemptAt;

    private LocalDateTime notificationDeliveredAt;

    private String notificationLastError;

    private Boolean active;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
