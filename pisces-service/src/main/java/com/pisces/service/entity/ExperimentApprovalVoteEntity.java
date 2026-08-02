package com.pisces.service.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 实验审批投票实体
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/17 15:35
 */
@Data
public class ExperimentApprovalVoteEntity {

    private Long id;

    private String experimentId;

    private String approvalType;

    private Long draftVersion;

    private String approvalStatus;

    private String approvalOperator;

    private String approvalComment;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
