package com.pisces.common.model;

import java.util.Arrays;
import java.util.Locale;

/**
 * 实验审批任务类型
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/17 09:26
 */
public enum ExperimentApprovalTaskType {

    /**
     * 实验启动审批 (Experiment activation approval)
     */
    EXPERIMENT_START,

    /**
     * 配置草稿审批 (Configuration draft approval)
     */
    CONFIG_DRAFT;

    public static ExperimentApprovalTaskType of(String code) {
        if (code == null) {
            return null;
        }
        String normalizedCode = code.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(item -> item.name().equals(normalizedCode))
                .findFirst()
                .orElse(null);
    }

    public static ExperimentApprovalTaskType ofOrThrow(String code) {
        ExperimentApprovalTaskType approvalTaskType = of(code);
        if (approvalTaskType == null) {
            throw new IllegalArgumentException("不支持的审批任务类型: " + code);
        }
        return approvalTaskType;
    }
}
