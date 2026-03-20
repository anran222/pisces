package com.pisces.common.request;

import lombok.Data;

import java.util.List;

/**
 * AI实验设计请求
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/3/20 13:34
 */
@Data
public class AIDesignRequest {

    /**
     * 业务场景
     */
    private String businessScenario;

    /**
     * 目标指标
     */
    private String targetMetric;

    /**
     * 约束条件
     */
    private List<String> constraints;
}
