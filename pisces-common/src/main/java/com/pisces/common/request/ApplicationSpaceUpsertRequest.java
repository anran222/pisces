package com.pisces.common.request;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 应用空间保存请求
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/1 12:10
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ApplicationSpaceUpsertRequest extends BaseRequest {

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
     * 启动实验是否需要审批
     */
    private Boolean approvalRequired;

    /**
     * 审批人列表；为空时回退默认负责人
     */
    private List<String> approvalOwners;

    /**
     * 审批通过所需人数
     */
    private Integer approvalRequiredCount;

    /**
     * 审批 SLA 小时数；为空表示不启用 SLA 告警
     */
    private Integer approvalSlaHours;

    /**
     * 审批升级接收人列表；为空时待办侧回退审批人
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
}
