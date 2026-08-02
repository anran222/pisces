package com.pisces.service.repository.impl;

import com.pisces.common.model.ExperimentApprovalTaskType;
import com.pisces.common.model.ExperimentApprovalVote;
import com.pisces.common.model.ExperimentMetadata;
import com.pisces.service.entity.ExperimentApprovalVoteEntity;
import com.pisces.service.mapper.ExperimentApprovalVoteMapper;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 数据库实验审批投票仓库
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/17 15:35
 */
@Repository
@AllArgsConstructor
public class ExperimentApprovalVoteRepository
        implements com.pisces.service.repository.ExperimentApprovalVoteRepository {

    private final ExperimentApprovalVoteMapper experimentApprovalVoteMapper;

    @Override
    public ExperimentApprovalVote save(ExperimentApprovalVote vote) {
        ExperimentApprovalVoteEntity entity = buildEntity(vote);
        experimentApprovalVoteMapper.upsert(entity);
        return vote;
    }

    @Override
    public List<ExperimentApprovalVote> listByApprovalTask(String experimentId, ExperimentApprovalTaskType approvalType,
                                                           long draftVersion) {
        return experimentApprovalVoteMapper.listByApprovalTask(experimentId, approvalType.name(), draftVersion).stream()
                .map(this::buildVote)
                .toList();
    }

    private ExperimentApprovalVoteEntity buildEntity(ExperimentApprovalVote vote) {
        ExperimentApprovalVoteEntity entity = new ExperimentApprovalVoteEntity();
        entity.setExperimentId(vote.getExperimentId());
        entity.setApprovalType(vote.getApprovalType().name());
        entity.setDraftVersion(vote.getDraftVersion());
        entity.setApprovalStatus(vote.getApprovalStatus().name());
        entity.setApprovalOperator(vote.getApprovalOperator());
        entity.setApprovalComment(vote.getApprovalComment());
        return entity;
    }

    private ExperimentApprovalVote buildVote(ExperimentApprovalVoteEntity entity) {
        ExperimentApprovalVote vote = new ExperimentApprovalVote();
        vote.setExperimentId(entity.getExperimentId());
        vote.setApprovalType(ExperimentApprovalTaskType.ofOrThrow(entity.getApprovalType()));
        vote.setDraftVersion(entity.getDraftVersion());
        vote.setApprovalStatus(ExperimentMetadata.ApprovalStatus.ofOrThrow(entity.getApprovalStatus()));
        vote.setApprovalOperator(entity.getApprovalOperator());
        vote.setApprovalComment(entity.getApprovalComment());
        vote.setCreatedAt(entity.getCreatedAt());
        vote.setUpdatedAt(entity.getUpdatedAt());
        return vote;
    }
}
