package com.pisces.common.model;

import java.util.Arrays;
import java.util.Locale;

/**
 * 审批升级告警状态 (Approval escalation status)
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/17 14:45
 */
public enum ExperimentApprovalEscalationStatus {

    OPEN,
    ACKNOWLEDGED,
    RESOLVED;

    public static ExperimentApprovalEscalationStatus of(String code) {
        if (code == null) {
            return null;
        }
        String normalizedCode = code.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(status -> status.name().equals(normalizedCode))
                .findFirst()
                .orElse(null);
    }

    public static ExperimentApprovalEscalationStatus ofOrThrow(String code) {
        ExperimentApprovalEscalationStatus status = of(code);
        if (status == null) {
            throw new IllegalArgumentException("不支持的审批升级告警状态: " + code);
        }
        return status;
    }
}
