package com.pisces.service.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 实验配置草稿审批实体
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/17 09:26
 */
@Data
public class ExperimentConfigDraftApprovalEntity {

    /**
     * 主键ID
     */
    private Long id;

    /**
     * 实验ID
     */
    private String experimentId;

    /**
     * 草稿版本
     */
    private Long draftVersion;

    /**
     * 草稿基线配置版本
     */
    private Long baseConfigVersion;

    /**
     * 审批状态
     */
    private String approvalStatus;

    /**
     * 提交人
     */
    private String requestedBy;

    /**
     * 草稿备注
     */
    private String draftComment;

    /**
     * 审批操作人
     */
    private String approvalOperator;

    /**
     * 审批备注
     */
    private String approvalComment;

    /**
     * 审批更新时间
     */
    private LocalDateTime approvalUpdatedAt;

    /**
     * 审批人快照
     */
    private String approvalOwnersSnapshot;

    /**
     * 审批通过人数快照
     */
    private Integer approvalRequiredCountSnapshot;

    /**
     * 审批策略版本快照
     */
    private Long approvalPolicyVersion;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
