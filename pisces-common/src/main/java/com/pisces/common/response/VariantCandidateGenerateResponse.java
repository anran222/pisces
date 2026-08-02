package com.pisces.common.response;

import lombok.Data;

import java.util.List;

/**
 * 候选变体生成响应
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/3/20 12:03
 */
@Data
public class VariantCandidateGenerateResponse {

    /**
     * 变体类型
     */
    private String variantType;

    /**
     * 候选变体列表
     */
    private List<String> variants;

    /**
     * 候选变体数量
     */
    private Integer count;

    /**
     * AI 供应商
     */
    private String aiProvider;

    /**
     * 实际命中的文本模型
     */
    private String aiModel;

    /**
     * 实际命中的文本模型调用协议
     */
    private String aiApiMode;

    /**
     * 是否发生模型回退
     */
    private Boolean aiFallbackUsed;

    /**
     * 配置的主文本模型
     */
    private String aiPrimaryModel;

    /**
     * 配置的回退文本模型
     */
    private String aiFallbackModel;

    /**
     * 本次调用尝试过的文本模型
     */
    private List<String> aiAttemptedModels;

    /**
     * 文本模型策略
     */
    private String aiModelStrategy;
}
