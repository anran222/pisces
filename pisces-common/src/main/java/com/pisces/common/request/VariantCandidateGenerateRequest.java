package com.pisces.common.request;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 候选变体生成请求
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/3/20 12:03
 */
@Data
public class VariantCandidateGenerateRequest {

    /**
     * 变体类型
     */
    private String variantType;

    /**
     * 生成目标
     */
    private String goal;

    /**
     * 目标受众
     */
    private String audience;

    /**
     * 约束条件
     */
    private List<String> constraints;

    /**
     * 生成数量
     */
    private Integer count;

    /**
     * 上下文信息
     */
    private Map<String, Object> sourceContext;
}
