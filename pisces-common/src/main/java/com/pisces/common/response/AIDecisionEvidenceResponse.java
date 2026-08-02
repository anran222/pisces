package com.pisces.common.response;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI决策证据响应
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/3/20 13:34
 */
@Data
public class AIDecisionEvidenceResponse {

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
     * 当前分析是否可直接用于结论判断
     */
    private Boolean analysisReady;

    /**
     * 是否存在 SRM
     */
    private Boolean hasSrm;

    /**
     * SRM p 值
     */
    private Double srmPValue;

    /**
     * 是否达到建议样本量
     */
    private Boolean sampleSizeReached;

    /**
     * 每组建议样本量
     */
    private Long requiredSampleSizePerGroup;

    /**
     * 阻断问题
     */
    private List<String> blockingIssues;

    /**
     * 警告信息
     */
    private List<String> warnings;

    /**
     * 主指标编码
     */
    private String primaryMetricKey;

    /**
     * 最佳表现组ID
     */
    private String bestPerformingGroup;

    /**
     * 最佳表现组主指标值
     */
    private Double bestPrimaryMetricValue;

    /**
     * 总分流数
     */
    private Long totalAssignments;

    /**
     * 总曝光数
     */
    private Long totalExposures;

    /**
     * 总事件数
     */
    private Long totalEvents;

    /**
     * 总访客数
     */
    private Long totalVisitors;

    /**
     * 护栏指标异常列表
     */
    private List<String> breachedGuardrails;

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
     * 报告快照事实
     */
    private List<String> reportSnapshotFacts;
}
