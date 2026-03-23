package com.pisces.common.response;

import com.pisces.common.request.ExperimentCreateRequest;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * AI实验设计响应
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/3/20 13:34
 */
@Data
public class AIDesignResponse {

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
     * 实验草案骨架
     */
    private ExperimentCreateRequest experimentDraft;

    /**
     * Schema规划结果
     */
    private Map<String, Object> schemaPlanning;

    /**
     * 草案生成结果
     */
    private Map<String, Object> draftGeneration;
}
