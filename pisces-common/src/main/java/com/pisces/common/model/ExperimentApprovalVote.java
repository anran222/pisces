package com.pisces.common.model;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 实验审批投票记录
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/17 15:35
 */
@Data
public class ExperimentApprovalVote implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 实验ID
     */
    private String experimentId;

    /**
     * 审批任务类型
     */
    private ExperimentApprovalTaskType approvalType;

    /**
     * 草稿版本；启动审批固定为0
     */
    private Long draftVersion;

    /**
     * 审批状态
     */
    private ExperimentMetadata.ApprovalStatus approvalStatus;

    /**
     * 审批操作人
     */
    private String approvalOperator;

    /**
     * 审批备注
     */
    private String approvalComment;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
