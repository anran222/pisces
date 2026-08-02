package com.pisces.service.ai;

import com.pisces.common.model.ExperimentDecisionContext;
import com.pisces.common.model.ExperimentReportSnapshot;
import com.pisces.common.model.Statistics;
import com.pisces.service.conclusion.ExperimentConclusionStatusPolicy;
import com.pisces.service.service.AnalysisService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 实验决策上下文构建器
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/3/20 13:54
 */
@Service
public class ExperimentDecisionContextBuilder {

    private static final String PRIMARY_METRIC_PREFIX = ": ";

    private final AnalysisService analysisService;

    public ExperimentDecisionContextBuilder(AnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    public ExperimentDecisionContext buildForExperiment(String experimentId) {
        Statistics statistics = analysisService.getStatistics(experimentId);
        ExperimentDecisionContext context = new ExperimentDecisionContext();
        context.setExperimentId(extractExperimentId(experimentId, statistics));
        if (statistics != null) {
            context.setExperimentName(statistics.getExperimentName());
            context.setExperimentStatus(statistics.getExperimentStatus());
            context.setStatistics(statistics);
        }
        context.setStatisticsFacts(buildStatisticsFacts(statistics));
        context.setGroupMetricSnapshots(buildGroupMetricSnapshots(statistics));
        context.setDataQualityFacts(buildDataQualityFacts(statistics));
        bindLatestReportSnapshot(context);
        return context;
    }

    private String extractExperimentId(String experimentId, Statistics statistics) {
        if (statistics == null || statistics.getExperimentId() == null || statistics.getExperimentId().isBlank()) {
            return experimentId;
        }
        return statistics.getExperimentId();
    }

    private List<String> buildStatisticsFacts(Statistics statistics) {
        if (statistics == null || statistics.getSummary() == null) {
            return Collections.emptyList();
        }
        Statistics.ExperimentSummary summary = statistics.getSummary();
        List<String> facts = new ArrayList<>();
        appendFact(facts, "bestPerformingGroup", summary.getBestPerformingGroup());
        appendFact(facts, "primaryMetricKey", summary.getPrimaryMetricKey());
        appendFact(facts, "bestPrimaryMetricValue", summary.getBestPrimaryMetricValue());
        appendFact(facts, "totalAssignments", summary.getTotalAssignments());
        appendFact(facts, "totalExposures", summary.getTotalExposures());
        return facts;
    }

    private List<String> buildGroupMetricSnapshots(Statistics statistics) {
        if (statistics == null
                || statistics.getGroupStatistics() == null
                || statistics.getGroupStatistics().isEmpty()) {
            return Collections.emptyList();
        }
        String primaryMetricKey = statistics.getSummary() != null ? statistics.getSummary().getPrimaryMetricKey() : null;
        List<String> snapshots = new ArrayList<>();
        for (Statistics.GroupStatistics groupStatistics : statistics.getGroupStatistics().values()) {
            if (groupStatistics == null || !StringUtils.hasText(groupStatistics.getGroupId())) {
                continue;
            }
            StringBuilder builder = new StringBuilder(groupStatistics.getGroupId().trim());
            if (StringUtils.hasText(groupStatistics.getGroupName())) {
                builder.append("(").append(groupStatistics.getGroupName().trim()).append(")");
            }
            appendMetricSnapshot(builder, primaryMetricKey, groupStatistics);
            snapshots.add(builder.toString());
        }
        return snapshots;
    }

    private void appendMetricSnapshot(StringBuilder builder, String primaryMetricKey,
                                      Statistics.GroupStatistics groupStatistics) {
        if (StringUtils.hasText(primaryMetricKey)
                && groupStatistics.getMetricValues() != null
                && groupStatistics.getMetricValues().containsKey(primaryMetricKey)) {
            builder.append(PRIMARY_METRIC_PREFIX)
                    .append(primaryMetricKey)
                    .append("=")
                    .append(groupStatistics.getMetricValues().get(primaryMetricKey));
            return;
        }
        if (groupStatistics.getConversionRate() != null) {
            builder.append(PRIMARY_METRIC_PREFIX)
                    .append("conversionRate=")
                    .append(groupStatistics.getConversionRate());
        }
    }

    private List<String> buildDataQualityFacts(Statistics statistics) {
        if (statistics == null || statistics.getDataQualityCheck() == null) {
            return Collections.emptyList();
        }
        Statistics.DataQualityCheck dataQualityCheck = statistics.getDataQualityCheck();
        List<String> facts = new ArrayList<>();
        appendFact(facts, "analysisReady", dataQualityCheck.getAnalysisReady());
        appendFact(facts, "hasSrm", dataQualityCheck.getHasSrm());
        appendFact(facts, "sampleSizeReached", dataQualityCheck.getSampleSizeReached());
        appendFact(facts, "blockingIssues", dataQualityCheck.getBlockingIssues());
        appendFact(facts, "warnings", dataQualityCheck.getWarnings());
        return facts;
    }

    private void bindLatestReportSnapshot(ExperimentDecisionContext context) {
        if (context == null || !StringUtils.hasText(context.getExperimentId())) {
            return;
        }
        ExperimentReportSnapshot latestSnapshot = ExperimentConclusionStatusPolicy.resolveLatestSnapshot(
                analysisService.listReportSnapshots(context.getExperimentId()));
        if (latestSnapshot == null) {
            context.setLatestReportBreachedGuardrails(Collections.emptyList());
            context.setReportSnapshotFacts(Collections.emptyList());
            return;
        }
        context.setLatestReportSnapshotVersion(latestSnapshot.getSnapshotVersion());
        context.setLatestReportGeneratedAt(latestSnapshot.getGeneratedAt());
        context.setLatestReportConclusionStatus(latestSnapshot.getConclusionStatus() == null
                ? null : latestSnapshot.getConclusionStatus().name());
        context.setLatestReportAnalysisReady(latestSnapshot.getAnalysisReady());
        context.setLatestReportHasSrm(latestSnapshot.getHasSrm());
        context.setLatestReportPrimaryMetricKey(latestSnapshot.getPrimaryMetricKey());
        context.setLatestReportBestPerformingGroup(latestSnapshot.getBestPerformingGroup());
        context.setLatestReportWinningVariant(latestSnapshot.getWinningVariant());
        context.setLatestReportBreachedGuardrails(latestSnapshot.getBreachedGuardrails() == null
                ? Collections.emptyList() : latestSnapshot.getBreachedGuardrails());
        context.setReportSnapshotFacts(buildReportSnapshotFacts(latestSnapshot));
    }

    private List<String> buildReportSnapshotFacts(ExperimentReportSnapshot snapshot) {
        if (snapshot == null) {
            return Collections.emptyList();
        }
        List<String> facts = new ArrayList<>();
        appendFact(facts, "latestReportSnapshotVersion", snapshot.getSnapshotVersion());
        appendFact(facts, "latestReportGeneratedAt", snapshot.getGeneratedAt());
        appendFact(facts, "latestReportConclusionStatus",
                snapshot.getConclusionStatus() == null ? null : snapshot.getConclusionStatus().name());
        appendFact(facts, "latestReportAnalysisReady", snapshot.getAnalysisReady());
        appendFact(facts, "latestReportHasSrm", snapshot.getHasSrm());
        appendFact(facts, "latestReportPrimaryMetricKey", snapshot.getPrimaryMetricKey());
        appendFact(facts, "latestReportBestPerformingGroup", snapshot.getBestPerformingGroup());
        appendFact(facts, "latestReportWinningVariant", snapshot.getWinningVariant());
        appendFact(facts, "latestReportBreachedGuardrails", snapshot.getBreachedGuardrails());
        return facts;
    }

    private void appendFact(List<String> facts, String key, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String stringValue && !StringUtils.hasText(stringValue)) {
            return;
        }
        facts.add(key + "=" + value);
    }
}
