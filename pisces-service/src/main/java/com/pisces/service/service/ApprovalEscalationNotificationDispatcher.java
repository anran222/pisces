package com.pisces.service.service;

import com.pisces.common.model.ExperimentApprovalEscalation;

import java.util.List;

/**
 * 审批升级告警消息投递器
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/17 15:10
 */
public interface ApprovalEscalationNotificationDispatcher {

    /**
     * 当前投递器是否启用
     *
     * @return true 表示可投递
     */
    boolean isEnabled();

    /**
     * 当前启用的投递目标数量
     *
     * @return 投递目标数量
     */
    default int targetCount() {
        return targets().size();
    }

    /**
     * 当前启用的投递通道名称
     *
     * @return 投递通道名称列表
     */
    default List<String> channelNames() {
        return targets().stream()
                .map(ApprovalEscalationNotificationTarget::getChannelName)
                .toList();
    }

    /**
     * 当前启用的投递目标
     *
     * @return 投递目标列表
     */
    default List<ApprovalEscalationNotificationTarget> targets() {
        if (!isEnabled()) {
            return List.of();
        }
        return List.of(new ApprovalEscalationNotificationTarget("DEFAULT", "DEFAULT", null));
    }

    /**
     * 投递审批升级告警消息
     *
     * @param escalation 审批升级告警
     */
    void dispatch(ExperimentApprovalEscalation escalation);

    /**
     * 投递审批升级告警消息到指定目标
     *
     * @param escalation 审批升级告警
     * @param target 投递目标
     */
    default void dispatch(ExperimentApprovalEscalation escalation, ApprovalEscalationNotificationTarget target) {
        dispatch(escalation);
    }
}
