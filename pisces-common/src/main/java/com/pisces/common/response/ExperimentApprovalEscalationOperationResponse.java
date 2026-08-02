package com.pisces.common.response;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审批升级告警治理操作响应
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/17 16:20
 */
@Data
public class ExperimentApprovalEscalationOperationResponse {

    private String escalationId;

    private String appId;

    private String owner;

    private String operation;

    private String operator;

    private String status;

    private Long affectedCount;

    private String message;

    private LocalDateTime operatedAt;
}
