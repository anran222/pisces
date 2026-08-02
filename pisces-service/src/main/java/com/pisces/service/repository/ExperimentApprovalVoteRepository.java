package com.pisces.service.repository;

import com.pisces.common.model.ExperimentApprovalTaskType;
import com.pisces.common.model.ExperimentApprovalVote;

import java.util.List;

/**
 * 实验审批投票仓库
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/17 15:35
 */
public interface ExperimentApprovalVoteRepository {

    /**
     * 保存审批投票
     *
     * @param vote 审批投票
     * @return 审批投票
     */
    ExperimentApprovalVote save(ExperimentApprovalVote vote);

    /**
     * 查询审批任务的全部投票
     *
     * @param experimentId 实验ID
     * @param approvalType 审批类型
     * @param draftVersion 草稿版本
     * @return 审批投票列表
     */
    List<ExperimentApprovalVote> listByApprovalTask(String experimentId, ExperimentApprovalTaskType approvalType,
                                                    long draftVersion);
}
