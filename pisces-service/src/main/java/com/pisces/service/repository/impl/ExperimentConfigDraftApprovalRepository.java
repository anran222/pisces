package com.pisces.service.repository.impl;

import com.pisces.common.model.ExperimentConfigDraftApproval;
import com.pisces.common.model.ExperimentMetadata;
import com.pisces.service.entity.ExperimentConfigDraftApprovalEntity;
import com.pisces.service.mapper.ExperimentConfigDraftApprovalMapper;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * 数据库配置草稿审批仓库
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/17 09:26
 */
@Repository
@AllArgsConstructor
public class ExperimentConfigDraftApprovalRepository
        implements com.pisces.service.repository.ExperimentConfigDraftApprovalRepository {

    private final ExperimentConfigDraftApprovalMapper experimentConfigDraftApprovalMapper;

    @Override
    public ExperimentConfigDraftApproval save(ExperimentConfigDraftApproval approval) {
        ExperimentConfigDraftApprovalEntity entity = buildEntity(approval);
        experimentConfigDraftApprovalMapper.upsert(entity);
        return findByExperimentIdAndDraftVersion(approval.getExperimentId(), approval.getDraftVersion())
                .orElseGet(() -> buildApproval(entity));
    }

    @Override
    public Optional<ExperimentConfigDraftApproval> findByExperimentIdAndDraftVersion(String experimentId,
                                                                                     long draftVersion) {
        ExperimentConfigDraftApprovalEntity entity = experimentConfigDraftApprovalMapper
                .selectByExperimentIdAndDraftVersion(experimentId, draftVersion);
        return entity == null ? Optional.empty() : Optional.of(buildApproval(entity));
    }

    @Override
    public Optional<ExperimentConfigDraftApproval> findLatestByExperimentId(String experimentId) {
        ExperimentConfigDraftApprovalEntity entity =
                experimentConfigDraftApprovalMapper.selectLatestByExperimentId(experimentId);
        return entity == null ? Optional.empty() : Optional.of(buildApproval(entity));
    }

    @Override
    public List<ExperimentConfigDraftApproval> listByExperimentId(String experimentId) {
        return experimentConfigDraftApprovalMapper.listByExperimentId(experimentId).stream()
                .map(this::buildApproval)
                .toList();
    }

    @Override
    public Optional<ExperimentConfigDraftApproval> updateStatus(String experimentId, long draftVersion,
                                                                ExperimentMetadata.ApprovalStatus approvalStatus,
                                                                String approvalOperator, String approvalComment) {
        experimentConfigDraftApprovalMapper.updateStatus(experimentId, draftVersion, approvalStatus.name(),
                approvalOperator, approvalComment);
        return findByExperimentIdAndDraftVersion(experimentId, draftVersion);
    }

    private ExperimentConfigDraftApprovalEntity buildEntity(ExperimentConfigDraftApproval approval) {
        ExperimentConfigDraftApprovalEntity entity = new ExperimentConfigDraftApprovalEntity();
        entity.setExperimentId(approval.getExperimentId());
        entity.setDraftVersion(approval.getDraftVersion());
        entity.setBaseConfigVersion(approval.getBaseConfigVersion());
        entity.setApprovalStatus(approval.getApprovalStatus().name());
        entity.setRequestedBy(approval.getRequestedBy());
        entity.setDraftComment(approval.getDraftComment());
        entity.setApprovalOperator(approval.getApprovalOperator());
        entity.setApprovalComment(approval.getApprovalComment());
        entity.setApprovalOwnersSnapshot(serializeOwners(approval.getApprovalOwnersSnapshot()));
        entity.setApprovalRequiredCountSnapshot(approval.getApprovalRequiredCountSnapshot());
        entity.setApprovalPolicyVersion(approval.getApprovalPolicyVersion());
        return entity;
    }

    private ExperimentConfigDraftApproval buildApproval(ExperimentConfigDraftApprovalEntity entity) {
        ExperimentConfigDraftApproval approval = new ExperimentConfigDraftApproval();
        approval.setExperimentId(entity.getExperimentId());
        approval.setDraftVersion(entity.getDraftVersion());
        approval.setBaseConfigVersion(entity.getBaseConfigVersion());
        approval.setApprovalStatus(ExperimentMetadata.ApprovalStatus.ofOrThrow(entity.getApprovalStatus()));
        approval.setRequestedBy(entity.getRequestedBy());
        approval.setDraftComment(entity.getDraftComment());
        approval.setApprovalOperator(entity.getApprovalOperator());
        approval.setApprovalComment(entity.getApprovalComment());
        approval.setApprovalUpdatedAt(entity.getApprovalUpdatedAt());
        approval.setApprovalOwnersSnapshot(deserializeOwners(entity.getApprovalOwnersSnapshot()));
        approval.setApprovalRequiredCountSnapshot(entity.getApprovalRequiredCountSnapshot());
        approval.setApprovalPolicyVersion(entity.getApprovalPolicyVersion());
        approval.setCreatedAt(entity.getCreatedAt());
        approval.setUpdatedAt(entity.getUpdatedAt());
        return approval;
    }

    private String serializeOwners(List<String> owners) {
        if (owners == null || owners.isEmpty()) {
            return null;
        }
        LinkedHashSet<String> normalizedOwners = new LinkedHashSet<>();
        for (String owner : owners) {
            if (StringUtils.hasText(owner)) {
                normalizedOwners.add(owner.trim());
            }
        }
        return normalizedOwners.isEmpty() ? null : String.join(",", normalizedOwners);
    }

    private List<String> deserializeOwners(String owners) {
        if (!StringUtils.hasText(owners)) {
            return List.of();
        }
        LinkedHashSet<String> normalizedOwners = new LinkedHashSet<>();
        for (String owner : owners.split(",")) {
            if (StringUtils.hasText(owner)) {
                normalizedOwners.add(owner.trim());
            }
        }
        return normalizedOwners.stream().toList();
    }
}
