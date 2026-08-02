package com.pisces.common.model;

import lombok.Data;

import java.time.LocalDateTime;
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
     * 最新报告快照版本
     */
    private Integer latestReportSnapshotVersion;

    /**
     * 最新报告生成时间
     */
    private LocalDateTime latestReportGeneratedAt;

    /**
     * 最新报告结论状态
     */
    private String latestReportConclusionStatus;

    /**
     * 最新报告分析门禁状态
     */
    private Boolean latestReportAnalysisReady;

    /**
     * 最新报告是否存在 SRM
     */
    private Boolean latestReportHasSrm;

    /**
     * 最新报告主指标编码
     */
    private String latestReportPrimaryMetricKey;

    /**
     * 最新报告最佳表现组
     */
    private String latestReportBestPerformingGroup;

    /**
     * 最新报告胜出变体
     */
    private String latestReportWinningVariant;

    /**
     * 最新报告护栏异常列表
     */
    private List<String> latestReportBreachedGuardrails;

    /**
     * 报告快照事实
     */
    private List<String> reportSnapshotFacts;

    /**
     * 决策提示
     */
    private List<String> decisionHints;

    /**
     * 上下文扩展信息
     */
    private Map<String, Object> attributes;
}
