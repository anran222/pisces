package com.pisces.service.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 应用空间实体
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/1 12:10
 */
@Data
public class ApplicationSpaceEntity {

    private Long id;

    private String appId;

    private String displayName;

    private String defaultOwner;

    private Integer experimentQuota;

    private Boolean approvalRequired;

    private String approvalOwners;

    private Integer approvalRequiredCount;

    private Long approvalPolicyVersion;

    private Integer approvalSlaHours;

    private String approvalEscalationOwners;

    private Boolean releaseWindowEnabled;

    private String releaseWindowTimezone;

    private String releaseWindowDays;

    private String releaseWindowStartTime;

    private String releaseWindowEndTime;

    private String createdBy;

    private String updatedBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
