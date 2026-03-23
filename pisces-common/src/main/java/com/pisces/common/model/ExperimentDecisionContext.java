package com.pisces.common.model;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 实验决策上下文
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/3/20 13:34
 */
@Data
public class ExperimentDecisionContext {

    /**
     * 实验ID
     */
    private String experimentId;

    /**
     * 实验名称
     */
    private String experimentName;

    /**
     * 实验状态
     */
    private String experimentStatus;

    /**
     * 统计信息
     */
    private Statistics statistics;

    /**
     * 统计事实摘要
     */
    private List<String> statisticsFacts;

    /**
     * 分组指标快照
     */
    private List<String> groupMetricSnapshots;

    /**
     * 数据质量事实
     */
    private List<String> dataQualityFacts;

    /**
     * 决策提示
     */
    private List<String> decisionHints;

    /**
     * 上下文扩展信息
     */
    private Map<String, Object> attributes;
}
