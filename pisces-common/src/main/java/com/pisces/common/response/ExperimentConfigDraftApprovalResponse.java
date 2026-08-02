package com.pisces.common.response;

import com.pisces.common.model.ExperimentMetadata;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 实验配置草稿审批记录响应
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/17 09:26
 */
@Data
public class ExperimentConfigDraftApprovalResponse {

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
    private ExperimentMetadata.ApprovalStatus approvalStatus;

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
    private List<String> approvalOwnersSnapshot;

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
