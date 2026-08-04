package com.pisces.service.repository.impl;

import com.pisces.common.model.ApplicationSpace;
import com.pisces.service.entity.ApplicationSpaceEntity;
import com.pisces.service.mapper.ApplicationSpaceMapper;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * 应用空间数据访问实现
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/1 12:10
 */
@Repository
@AllArgsConstructor
public class ApplicationSpaceRepository implements com.pisces.service.repository.ApplicationSpaceRepository {

    private final ApplicationSpaceMapper applicationSpaceMapper;

    @Override
    public ApplicationSpace create(ApplicationSpace applicationSpace) {
        applicationSpaceMapper.insert(buildApplicationSpaceEntity(applicationSpace));
        return findByAppId(applicationSpace.getAppId()).orElse(applicationSpace);
    }

    @Override
    public ApplicationSpace save(ApplicationSpace applicationSpace) {
        applicationSpaceMapper.upsert(buildApplicationSpaceEntity(applicationSpace));
        return findByAppId(applicationSpace.getAppId()).orElse(applicationSpace);
    }

    @Override
    public Optional<ApplicationSpace> findByAppId(String appId) {
        return Optional.ofNullable(applicationSpaceMapper.selectByAppId(appId))
                .map(this::buildApplicationSpace);
    }

    @Override
    public List<ApplicationSpace> findAll() {
        return applicationSpaceMapper.selectAll().stream()
                .map(this::buildApplicationSpace)
                .toList();
    }

    private ApplicationSpaceEntity buildApplicationSpaceEntity(ApplicationSpace applicationSpace) {
        ApplicationSpaceEntity entity = new ApplicationSpaceEntity();
        entity.setAppId(applicationSpace.getAppId());
        entity.setDisplayName(applicationSpace.getDisplayName());
        entity.setDefaultOwner(applicationSpace.getDefaultOwner());
        entity.setExperimentQuota(applicationSpace.getExperimentQuota());
        entity.setApprovalRequired(applicationSpace.getApprovalRequired());
        entity.setApprovalOwners(serializeOwners(applicationSpace.getApprovalOwners()));
        entity.setApprovalRequiredCount(applicationSpace.getApprovalRequiredCount());
        entity.setApprovalPolicyVersion(applicationSpace.getApprovalPolicyVersion());
        entity.setApprovalSlaHours(applicationSpace.getApprovalSlaHours());
        entity.setApprovalEscalationOwners(serializeOwners(applicationSpace.getApprovalEscalationOwners()));
        entity.setReleaseWindowEnabled(applicationSpace.getReleaseWindowEnabled());
        entity.setReleaseWindowTimezone(applicationSpace.getReleaseWindowTimezone());
        entity.setReleaseWindowDays(serializeReleaseWindowDays(applicationSpace.getReleaseWindowDays()));
        entity.setReleaseWindowStartTime(applicationSpace.getReleaseWindowStartTime());
        entity.setReleaseWindowEndTime(applicationSpace.getReleaseWindowEndTime());
        entity.setCreatedBy(applicationSpace.getCreatedBy());
        entity.setUpdatedBy(applicationSpace.getUpdatedBy());
        entity.setCreatedAt(applicationSpace.getCreatedAt());
        entity.setUpdatedAt(applicationSpace.getUpdatedAt());
        return entity;
    }

    private ApplicationSpace buildApplicationSpace(ApplicationSpaceEntity entity) {
        ApplicationSpace applicationSpace = new ApplicationSpace();
        applicationSpace.setAppId(entity.getAppId());
        applicationSpace.setDisplayName(entity.getDisplayName());
        applicationSpace.setDefaultOwner(entity.getDefaultOwner());
        applicationSpace.setExperimentQuota(entity.getExperimentQuota());
        applicationSpace.setApprovalRequired(entity.getApprovalRequired());
        applicationSpace.setApprovalOwners(deserializeOwners(entity.getApprovalOwners()));
        applicationSpace.setApprovalRequiredCount(entity.getApprovalRequiredCount());
        applicationSpace.setApprovalPolicyVersion(entity.getApprovalPolicyVersion());
        applicationSpace.setApprovalSlaHours(entity.getApprovalSlaHours());
        applicationSpace.setApprovalEscalationOwners(deserializeOwners(entity.getApprovalEscalationOwners()));
        applicationSpace.setReleaseWindowEnabled(entity.getReleaseWindowEnabled());
        applicationSpace.setReleaseWindowTimezone(entity.getReleaseWindowTimezone());
        applicationSpace.setReleaseWindowDays(deserializeReleaseWindowDays(entity.getReleaseWindowDays()));
        applicationSpace.setReleaseWindowStartTime(entity.getReleaseWindowStartTime());
        applicationSpace.setReleaseWindowEndTime(entity.getReleaseWindowEndTime());
        applicationSpace.setCreatedBy(entity.getCreatedBy());
        applicationSpace.setUpdatedBy(entity.getUpdatedBy());
        applicationSpace.setCreatedAt(entity.getCreatedAt());
        applicationSpace.setUpdatedAt(entity.getUpdatedAt());
        return applicationSpace;
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

    private String serializeReleaseWindowDays(List<Integer> releaseWindowDays) {
        if (releaseWindowDays == null || releaseWindowDays.isEmpty()) {
            return null;
        }
        LinkedHashSet<Integer> normalizedDays = new LinkedHashSet<>();
        for (Integer releaseWindowDay : releaseWindowDays) {
            if (releaseWindowDay != null) {
                normalizedDays.add(releaseWindowDay);
            }
        }
        return normalizedDays.isEmpty() ? null : normalizedDays.stream()
                .map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
    }

    private List<Integer> deserializeReleaseWindowDays(String releaseWindowDays) {
        if (!StringUtils.hasText(releaseWindowDays)) {
            return List.of();
        }
        LinkedHashSet<Integer> normalizedDays = new LinkedHashSet<>();
        for (String releaseWindowDay : releaseWindowDays.split(",")) {
            if (StringUtils.hasText(releaseWindowDay)) {
                normalizedDays.add(Integer.valueOf(releaseWindowDay.trim()));
            }
        }
        return normalizedDays.stream().toList();
    }
}
