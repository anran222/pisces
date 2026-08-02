package com.pisces.service.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 实验审批升级告警通道投递回执实体
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/17 15:25
 */
@Data
public class ExperimentApprovalEscalationDeliveryEntity {

    private Long id;

    private String escalationId;

    private String channelName;

    private String targetKey;

    private String notificationStatus;

    private Integer notificationAttemptCount;

    private LocalDateTime notificationLastAttemptAt;

    private LocalDateTime notificationNextAttemptAt;

    private LocalDateTime notificationDeliveredAt;

    private String notificationLastError;

    private Boolean active;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
