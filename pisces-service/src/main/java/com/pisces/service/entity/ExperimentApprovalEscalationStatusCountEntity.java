package com.pisces.service.entity;

import lombok.Data;

/**
 * 实验审批升级告警状态计数实体
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/17 16:20
 */
@Data
public class ExperimentApprovalEscalationStatusCountEntity {

    private String status;

    private Long escalationCount;
}
