package com.pisces.common.response;

import com.pisces.common.model.Experiment;
import com.pisces.common.model.ExperimentApprovalTaskType;
import com.pisces.common.model.ExperimentMetadata;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 实验审批任务响应
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/16 19:14
 */
@Data
public class ExperimentApprovalTaskResponse {

    /**
     * 实验ID
     */
    private String experimentId;

    /**
     * 审批任务类型
     */
    private ExperimentApprovalTaskType approvalType;

    /**
     * 实验名称
     */
    private String experimentName;

    /**
     * 应用ID
     */
    private String appId;

    /**
     * 负责人
     */
    private String owner;

    /**
     * 实验状态
     */
    private Experiment.ExperimentStatus experimentStatus;

    /**
     * 审批状态
     */
    private ExperimentMetadata.ApprovalStatus approvalStatus;

    /**
     * 审批操作人
     */
    private String approvalOperator;

    /**
     * 审批提交人
     */
    private String approvalRequestedBy;

    /**
     * 审批负责人
     */
    private String approvalOwner;

    /**
     * 审批负责人列表
     */
    private List<String> approvalOwners;

    /**
     * 审批通过所需人数
     */
    private Integer approvalRequiredCount;

    /**
     * 已通过人数
     */
    private Integer approvalApprovedCount;

    /**
     * 已拒绝人数
     */
    private Integer approvalRejectedCount;

    /**
     * 审批进度文案
     */
    private String approvalProgressText;

    /**
     * 审批提交时间
     */
    private LocalDateTime approvalSubmittedAt;

    /**
     * 审批已等待小时数
     */
    private Long approvalElapsedHours;

    /**
     * 审批 SLA 小时数；为空表示未启用
     */
    private Integer approvalSlaHours;

    /**
     * 审批 SLA 状态：ON_TRACK/DUE_SOON/OVERDUE
     */
    private String approvalSlaStatus;

    /**
     * 审批升级接收人列表
     */
    private List<String> approvalEscalationOwners;

    /**
     * 审批升级原因
     */
    private String approvalEscalationReason;

    /**
     * 审批风险等级：UNKNOWN/CLEAR/WARNING/BLOCKED
     */
    private String approvalRiskLevel;

    /**
     * 审批风险标记
     */
    private List<String> approvalRiskFlags;

    /**
     * 最新报告护栏状态
     */
    private String guardrailStatus;

    /**
     * 最新报告是否满足分析门禁
     */
    private Boolean analysisReady;

    /**
     * 最新报告是否存在SRM
     */
    private Boolean hasSrm;

    /**
     * 最新报告护栏异常列表
     */
    private List<String> breachedGuardrails;

    /**
     * 最新报告快照版本
     */
    private Integer latestReportSnapshotVersion;

    /**
     * 最新报告生成时间
     */
    private LocalDateTime latestReportGeneratedAt;

    /**
     * 风险导致的不可通过原因
     */
    private String approvalRiskDisabledReason;

    /**
     * 当前审批是否需要风险豁免
     */
    private Boolean riskOverrideRequired;

    /**
     * 当前身份是否允许风险豁免
     */
    private Boolean riskOverrideAllowed;

    /**
     * 当前身份是否可审批
     */
    private Boolean approvable;

    /**
     * 不可审批原因
     */
    private String approvalDisabledReason;

    /**
     * 审批备注
     */
    private String approvalComment;

    /**
     * 草稿备注
     */
    private String draftComment;

    /**
     * 审批更新时间
     */
    private LocalDateTime approvalUpdatedAt;

    /**
     * 配置版本
     */
    private Long configVersion;

    /**
     * 草稿版本
     */
    private Long draftVersion;

    /**
     * 草稿基线配置版本
     */
    private Long baseConfigVersion;

    /**
     * 实验层ID
     */
    private String layerId;

    /**
     * 开始时间
     */
    private LocalDateTime startTime;

    /**
     * 结束时间
     */
    private LocalDateTime endTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}
