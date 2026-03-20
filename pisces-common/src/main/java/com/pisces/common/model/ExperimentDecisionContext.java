package com.pisces.common.model;

import lombok.Data;

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
     * 上下文扩展信息
     */
    private Map<String, Object> attributes;
}
