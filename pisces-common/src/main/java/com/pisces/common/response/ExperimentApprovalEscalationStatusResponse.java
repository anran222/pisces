package com.pisces.common.response;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 审批升级告警投递状态响应
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/17 16:20
 */
@Data
public class ExperimentApprovalEscalationStatusResponse {

    private String appId;

    private String owner;

    private Long totalCount;

    private Long openCount;

    private Long acknowledgedCount;

    private Long resolvedCount;

    private Long pendingCount;

    private Long dispatchingCount;

    private Long sentCount;

    private Long retryCount;

    private Long deadCount;

    private Long undeliveredCount;

    private Long deliveryPendingCount;

    private Long deliveryDispatchingCount;

    private Long deliverySentCount;

    private Long deliveryRetryCount;

    private Long deliveryDeadCount;

    private Long deliveryUndeliveredCount;

    private Boolean healthy;

    private String status;

    private Boolean dispatcherEnabled;

    private Integer dispatcherTargetCount;

    private List<String> dispatcherChannels;

    private LocalDateTime generatedAt;
}
