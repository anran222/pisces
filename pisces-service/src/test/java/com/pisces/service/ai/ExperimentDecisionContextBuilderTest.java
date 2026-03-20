package com.pisces.service.ai;

import com.pisces.common.model.ExperimentDecisionContext;
import com.pisces.common.model.Statistics;
import com.pisces.service.service.AnalysisService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

        when(analysisService.getStatistics("exp_001")).thenReturn(statistics);

        ExperimentDecisionContextBuilder builder = new ExperimentDecisionContextBuilder(analysisService);
        ExperimentDecisionContext context = builder.buildForExperiment("exp_001");

        assertThat(context).isNotNull();
        assertThat(context.getExperimentId()).isEqualTo("exp_001");
        assertThat(context.getExperimentName()).isEqualTo("新客首单优惠");
        assertThat(context.getExperimentStatus()).isEqualTo("RUNNING");
        assertThat(context.getStatistics()).isSameAs(statistics);

        verify(analysisService).getStatistics("exp_001");
    }
}
