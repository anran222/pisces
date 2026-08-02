package com.pisces.service.mapper;

import com.pisces.service.entity.ExperimentApprovalVoteEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 实验审批投票Mapper
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/17 15:35
 */
@Mapper
public interface ExperimentApprovalVoteMapper {

    /**
     * 保存或更新审批投票
     *
     * @param entity 投票实体
     * @return 影响行数
     */
    int upsert(@Param("entity") ExperimentApprovalVoteEntity entity);

    /**
     * 查询审批任务的全部投票
     *
     * @param experimentId 实验ID
     * @param approvalType 审批类型
     * @param draftVersion 草稿版本
     * @return 投票实体列表
     */
    List<ExperimentApprovalVoteEntity> listByApprovalTask(@Param("experimentId") String experimentId,
                                                          @Param("approvalType") String approvalType,
                                                          @Param("draftVersion") long draftVersion);
}
