package com.pisces.common.model;

import java.util.Arrays;
import java.util.Locale;

/**
 * 审批升级告警消息投递状态 (Approval escalation notification status)
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/17 15:10
 */
public enum ExperimentApprovalEscalationNotificationStatus {

    PENDING,
    DISPATCHING,
    SENT,
    RETRY,
    DEAD;

    public static ExperimentApprovalEscalationNotificationStatus of(String code) {
        if (code == null) {
            return null;
        }
        String normalizedCode = code.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(status -> status.name().equals(normalizedCode))
                .findFirst()
                .orElse(null);
    }

    public static ExperimentApprovalEscalationNotificationStatus ofOrThrow(String code) {
        ExperimentApprovalEscalationNotificationStatus status = of(code);
        if (status == null) {
            throw new IllegalArgumentException("不支持的审批升级告警消息投递状态: " + code);
        }
        return status;
    }
}
