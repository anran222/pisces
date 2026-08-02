package com.pisces.service.repository;

import com.pisces.common.model.ExperimentConfigDraftApproval;
import com.pisces.common.model.ExperimentMetadata;

import java.util.List;
import java.util.Optional;

/**
 * 实验配置草稿审批仓库
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/17 09:26
 */
public interface ExperimentConfigDraftApprovalRepository {

    /**
     * 保存草稿审批记录
     *
     * @param approval 草稿审批记录
     * @return 草稿审批记录
     */
    ExperimentConfigDraftApproval save(ExperimentConfigDraftApproval approval);

    /**
     * 查询指定草稿审批记录
     *
     * @param experimentId 实验ID
     * @param draftVersion 草稿版本
     * @return 草稿审批记录
     */
    Optional<ExperimentConfigDraftApproval> findByExperimentIdAndDraftVersion(String experimentId, long draftVersion);

    /**
     * 查询最新草稿审批记录
     *
     * @param experimentId 实验ID
     * @return 草稿审批记录
     */
    Optional<ExperimentConfigDraftApproval> findLatestByExperimentId(String experimentId);

    /**
     * 查询实验全部草稿审批记录
     *
     * @param experimentId 实验ID
     * @return 草稿审批记录列表
     */
    List<ExperimentConfigDraftApproval> listByExperimentId(String experimentId);

    /**
     * 更新指定草稿审批状态
     *
     * @param experimentId 实验ID
     * @param draftVersion 草稿版本
     * @param approvalStatus 审批状态
     * @param approvalOperator 审批操作人
     * @param approvalComment 审批备注
     * @return 草稿审批记录
     */
    Optional<ExperimentConfigDraftApproval> updateStatus(String experimentId, long draftVersion,
                                                         ExperimentMetadata.ApprovalStatus approvalStatus,
                                                         String approvalOperator, String approvalComment);
}
