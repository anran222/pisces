package com.pisces.common.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 实验审批升级告警通道投递回执
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/17 15:25
 */
@Data
public class ExperimentApprovalEscalationDelivery {

    private Long id;

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
