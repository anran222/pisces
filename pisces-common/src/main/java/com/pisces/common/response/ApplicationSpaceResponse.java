package com.pisces.common.response;

import lombok.Data;

import java.util.List;

/**
 * 应用空间响应
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/1 11:39
 */
@Data
public class ApplicationSpaceResponse {

    /**
     * 应用ID
     */
    private String appId;

    /**
     * 展示名称
     */
    private String displayName;

    /**
     * 默认负责人
     */
    private String defaultOwner;

    /**
     * 实验配额；为空表示不限制
     */
    private Integer experimentQuota;

    /**
     * 已使用实验配额
     */
    private Integer quotaUsed;

    /**
     * 剩余实验配额；为空表示不限制
     */
    private Integer quotaRemaining;

    /**
     * 启动实验是否需要审批
     */
    private Boolean approvalRequired;

    /**
     * 审批人列表
     */
    private List<String> approvalOwners;

    /**
     * 审批通过所需人数
     */
    private Integer approvalRequiredCount;

    /**
     * 审批策略版本
     */
    private Long approvalPolicyVersion;

    /**
     * 审批 SLA 小时数；为空表示不启用 SLA 告警
     */
    private Integer approvalSlaHours;

    /**
     * 审批升级接收人列表
     */
    private List<String> approvalEscalationOwners;

    /**
     * 是否启用发布窗口
     */
    private Boolean releaseWindowEnabled;

    /**
     * 发布窗口时区
     */
    private String releaseWindowTimezone;

    /**
     * 发布窗口星期列表，1=周一，7=周日
     */
    private List<Integer> releaseWindowDays;

    /**
     * 发布窗口开始时间，格式 HH:mm
     */
    private String releaseWindowStartTime;

    /**
     * 发布窗口结束时间，格式 HH:mm
     */
    private String releaseWindowEndTime;

    /**
     * 负责人列表
     */
    private List<String> owners;

    /**
     * 权限域列表
     */
    private List<String> scopes;

    /**
     * 是否来自 API Key 配置
     */
    private Boolean configured;

    /**
     * 是否来自数据库注册表
     */
    private Boolean registered;

    /**
     * 绑定的 API Key 数量
     */
    private Integer apiKeyCount;

    /**
     * 实验总数
     */
    private Integer experimentCount;

    /**
     * 运行中实验数
     */
    private Integer runningExperimentCount;
}
