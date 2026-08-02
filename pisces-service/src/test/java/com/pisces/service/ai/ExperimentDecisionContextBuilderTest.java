package com.pisces.service.ai;

import com.pisces.common.model.ExperimentDecisionContext;
import com.pisces.common.model.ExperimentMetadata;
import com.pisces.common.model.ExperimentReportSnapshot;
import com.pisces.common.model.Statistics;
import com.pisces.service.service.AnalysisService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 实验决策上下文构建器测试
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/3/20 13:54
 */
@ExtendWith(MockitoExtension.class)
class ExperimentDecisionContextBuilderTest {

    @Mock
    private AnalysisService analysisService;

    @Test
    void buildForExperimentShouldMapStatisticsToDecisionContext() {
        Statistics statistics = new Statistics();
        statistics.setExperimentId("exp_001");
        statistics.setExperimentName("新客首单优惠");
        statistics.setExperimentStatus("RUNNING");
        statistics.setSummary(summary());
        statistics.setDataQualityCheck(dataQualityCheck());
        statistics.setGroupStatistics(groupStatistics());

        when(analysisService.getStatistics("exp_001")).thenReturn(statistics);

        ExperimentDecisionContextBuilder builder = new ExperimentDecisionContextBuilder(analysisService);
        ExperimentDecisionContext context = builder.buildForExperiment("exp_001");

        assertThat(context).isNotNull();
        assertThat(context.getExperimentId()).isEqualTo("exp_001");
        assertThat(context.getExperimentName()).isEqualTo("新客首单优惠");
        assertThat(context.getExperimentStatus()).isEqualTo("RUNNING");
        assertThat(context.getStatistics()).isSameAs(statistics);
        assertThat(context.getStatisticsFacts()).contains("bestPerformingGroup=D", "primaryMetricKey=PAYMENT_RATE");
        assertThat(context.getGroupMetricSnapshots()).contains("D(实验组): PAYMENT_RATE=0.76");
        assertThat(context.getDataQualityFacts()).contains("analysisReady=true", "sampleSizeReached=true");

        verify(analysisService).getStatistics("exp_001");
    }

    @Test
    void buildForExperimentShouldBindLatestReportSnapshotFacts() {
        Statistics statistics = new Statistics();
        statistics.setExperimentId("exp_001");
        statistics.setExperimentName("新客首单优惠");
        statistics.setExperimentStatus("RUNNING");
        ExperimentReportSnapshot oldSnapshot = reportSnapshot(1, LocalDateTime.of(2026, 3, 20, 10, 0));
        ExperimentReportSnapshot latestSnapshot = reportSnapshot(3, LocalDateTime.of(2026, 3, 21, 10, 0));
        latestSnapshot.setConclusionStatus(ExperimentMetadata.ConclusionStatus.GRADUATED);
        latestSnapshot.setAnalysisReady(true);
        latestSnapshot.setHasSrm(false);
        latestSnapshot.setPrimaryMetricKey("PAYMENT_RATE");
        latestSnapshot.setBestPerformingGroup("variant_a");
        latestSnapshot.setWinningVariant("variant_a");
        latestSnapshot.setBreachedGuardrails(List.of("MARGIN_DROP"));

        when(analysisService.getStatistics("exp_001")).thenReturn(statistics);
        when(analysisService.listReportSnapshots("exp_001")).thenReturn(List.of(oldSnapshot, latestSnapshot));

        ExperimentDecisionContextBuilder builder = new ExperimentDecisionContextBuilder(analysisService);
        ExperimentDecisionContext context = builder.buildForExperiment("exp_001");

        assertThat(context.getLatestReportSnapshotVersion()).isEqualTo(3);
        assertThat(context.getLatestReportGeneratedAt()).isEqualTo(LocalDateTime.of(2026, 3, 21, 10, 0));
        assertThat(context.getLatestReportConclusionStatus()).isEqualTo("GRADUATED");
        assertThat(context.getLatestReportAnalysisReady()).isTrue();
        assertThat(context.getLatestReportHasSrm()).isFalse();
        assertThat(context.getLatestReportPrimaryMetricKey()).isEqualTo("PAYMENT_RATE");
        assertThat(context.getLatestReportBestPerformingGroup()).isEqualTo("variant_a");
        assertThat(context.getLatestReportWinningVariant()).isEqualTo("variant_a");
        assertThat(context.getLatestReportBreachedGuardrails()).containsExactly("MARGIN_DROP");
        assertThat(context.getReportSnapshotFacts())
                .contains("latestReportSnapshotVersion=3", "latestReportConclusionStatus=GRADUATED");
        verify(analysisService).listReportSnapshots("exp_001");
    }

    @Test
    void buildForExperimentShouldNotInjectDemoHintsByExperimentName() {
        Statistics statistics = new Statistics();
        statistics.setExperimentId("exp_demo_fail");
        statistics.setExperimentName("二手手机售卖页优化实验 [USED_PHONE_DEMO_FAIL]");
        statistics.setExperimentStatus("RUNNING");

        when(analysisService.getStatistics("exp_demo_fail")).thenReturn(statistics);

        ExperimentDecisionContextBuilder builder = new ExperimentDecisionContextBuilder(analysisService);
        ExperimentDecisionContext context = builder.buildForExperiment("exp_demo_fail");

        assertThat(context.getDecisionHints()).isNull();
    }

    private Statistics.ExperimentSummary summary() {
        Statistics.ExperimentSummary summary = new Statistics.ExperimentSummary();
        summary.setBestPerformingGroup("D");
        summary.setPrimaryMetricKey("PAYMENT_RATE");
        summary.setBestPrimaryMetricValue(0.76D);
        return summary;
    }

    private Statistics.DataQualityCheck dataQualityCheck() {
        Statistics.DataQualityCheck dataQualityCheck = new Statistics.DataQualityCheck();
        dataQualityCheck.setAnalysisReady(true);
        dataQualityCheck.setSampleSizeReached(true);
        return dataQualityCheck;
    }

    private java.util.Map<String, Statistics.GroupStatistics> groupStatistics() {
        Statistics.GroupStatistics winningGroup = new Statistics.GroupStatistics();
        winningGroup.setGroupId("D");
        winningGroup.setGroupName("实验组");
        winningGroup.setMetricValues(Map.of("PAYMENT_RATE", 0.76D));
        return Map.of("D", winningGroup);
    }

    private ExperimentReportSnapshot reportSnapshot(Integer snapshotVersion, LocalDateTime generatedAt) {
        ExperimentReportSnapshot snapshot = new ExperimentReportSnapshot();
        snapshot.setExperimentId("exp_001");
        snapshot.setSnapshotVersion(snapshotVersion);
        snapshot.setGeneratedAt(generatedAt);
        return snapshot;
    }
}
