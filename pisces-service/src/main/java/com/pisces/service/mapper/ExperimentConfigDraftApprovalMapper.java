package com.pisces.service.mapper;

import com.pisces.service.entity.ExperimentConfigDraftApprovalEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 实验配置草稿审批Mapper
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/17 09:26
 */
@Mapper
public interface ExperimentConfigDraftApprovalMapper {

    /**
     * 保存或更新草稿审批记录
     *
     * @param entity 审批实体
     * @return 影响行数
     */
    int upsert(@Param("entity") ExperimentConfigDraftApprovalEntity entity);

    /**
     * 查询指定草稿审批记录
     *
     * @param experimentId 实验ID
     * @param draftVersion 草稿版本
     * @return 审批实体
     */
    ExperimentConfigDraftApprovalEntity selectByExperimentIdAndDraftVersion(
            @Param("experimentId") String experimentId,
            @Param("draftVersion") long draftVersion);

    /**
     * 查询最新草稿审批记录
     *
     * @param experimentId 实验ID
     * @return 审批实体
     */
    ExperimentConfigDraftApprovalEntity selectLatestByExperimentId(@Param("experimentId") String experimentId);

    /**
     * 查询实验全部草稿审批记录
     *
     * @param experimentId 实验ID
     * @return 审批实体列表
     */
    List<ExperimentConfigDraftApprovalEntity> listByExperimentId(@Param("experimentId") String experimentId);

    /**
     * 更新指定草稿审批状态
     *
     * @param experimentId 实验ID
     * @param draftVersion 草稿版本
     * @param approvalStatus 审批状态
     * @param approvalOperator 审批操作人
     * @param approvalComment 审批备注
     * @return 影响行数
     */
    int updateStatus(@Param("experimentId") String experimentId,
                     @Param("draftVersion") long draftVersion,
                     @Param("approvalStatus") String approvalStatus,
                     @Param("approvalOperator") String approvalOperator,
                     @Param("approvalComment") String approvalComment);
}
