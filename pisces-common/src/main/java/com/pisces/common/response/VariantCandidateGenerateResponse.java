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
}
