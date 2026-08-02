package com.pisces.common.response;

import com.pisces.common.model.ExperimentMetadata;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 实验配置草稿响应
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/17 09:09
 */
@Data
public class ExperimentConfigDraftResponse {

    /**
     * 实验ID
     */
    private String experimentId;

    /**
     * 草稿版本
     */
    private Long draftVersion;

    /**
     * 当前运行配置版本
     */
    private Long currentConfigVersion;

    /**
     * 草稿基线配置版本
     */
    private Long baseConfigVersion;

    /**
     * 是否已落后于当前运行配置
     */
    private Boolean stale;

    /**
     * 更新人
     */
    private String updatedBy;

    /**
     * 草稿备注
     */
    private String draftComment;

    /**
     * 草稿审批状态
     */
    private ExperimentMetadata.ApprovalStatus approvalStatus;

    /**
     * 草稿审批操作人
     */
    private String approvalOperator;

    /**
     * 草稿审批备注
     */
    private String approvalComment;

    /**
     * 草稿审批更新时间
     */
    private LocalDateTime approvalUpdatedAt;

    /**
     * 草稿详情
     */
    private ExperimentResponse draftExperiment;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
