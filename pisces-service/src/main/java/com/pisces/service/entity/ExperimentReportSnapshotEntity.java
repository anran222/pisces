package com.pisces.service.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 实验报告快照实体
 */
@Data
public class ExperimentReportSnapshotEntity {

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
    private String conclusionStatus;

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
     * 护栏异常JSON
     */
    private String breachedGuardrailsJson;

    /**
     * 决策上下文JSON
     */
    private String decisionContextJson;

    /**
     * 报告JSON
     */
    private String reportJson;

    /**
     * 生成人
     */
    private String generatedBy;

    /**
     * 生成时间
     */
    private LocalDateTime generatedAt;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
}
