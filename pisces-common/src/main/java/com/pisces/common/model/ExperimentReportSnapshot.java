package com.pisces.common.model;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 实验报告快照
 */
@Data
public class ExperimentReportSnapshot implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    private Long id;

    /**
     * 实验ID
     */
    private String experimentId;

    /**
     * 快照版本
     */
    private Integer snapshotVersion;

    /**
     * 结论状态
     */
    private ExperimentMetadata.ConclusionStatus conclusionStatus;

    /**
     * 主指标编码
     */
    private String primaryMetricKey;

    /**
     * 最优实验组
     */
    private String bestPerformingGroup;

    /**
     * 胜出变体
     */
    private String winningVariant;

    /**
     * 是否满足分析门禁
     */
    private Boolean analysisReady;

    /**
     * 是否存在SRM
     */
    private Boolean hasSrm;

    /**
     * 护栏异常列表
     */
    private List<String> breachedGuardrails;

    /**
     * 决策上下文
     */
    private Map<String, Object> decisionContext;

    /**
     * 报告快照
     */
    private Map<String, Object> report;

    /**
     * 生成人
     */
    private String generatedBy;

    /**
     * 生成时间
     */
    private LocalDateTime generatedAt;
}
