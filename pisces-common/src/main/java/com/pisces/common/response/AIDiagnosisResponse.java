package com.pisces.common.response;

import lombok.Data;

import java.util.List;

/**
 * AI实验诊断响应
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/3/20 13:34
 */
@Data
public class AIDiagnosisResponse {

    /**
     * 决策类型
     */
    private String decisionType;

    /**
     * 结论摘要
     */
    private String summary;

    /**
     * 置信度
     */
    private Double confidence;

    /**
     * 风险标记
     */
    private List<String> riskFlags;

    /**
     * 护栏状态
     */
    private String guardrailStatus;

    /**
     * 推荐动作
     */
    private List<RecommendedAction> recommendedActions;

    @Data
    public static class RecommendedAction {

        /**
         * 动作标题
         */
        private String title;

        /**
         * 动作说明
         */
        private String action;

        /**
         * 执行模式
         */
        private String executionMode;
    }
}
