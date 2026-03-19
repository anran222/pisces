package com.pisces.service.service.impl;

import com.pisces.common.model.Experiment;
import com.pisces.common.model.Statistics;
import com.pisces.common.request.ExperimentCreateRequest;
import com.pisces.service.service.AnalysisService;
import com.pisces.service.service.DataService;
import com.pisces.service.service.ExperimentDemoService;
import com.pisces.service.service.ExperimentService;
import com.pisces.service.service.TrafficService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExperimentDemoServiceImplTest {

    @Mock
    private ExperimentService experimentService;

    @Mock
    private TrafficService trafficService;

    @Mock
    private DataService dataService;

    @Mock
    private AnalysisService analysisService;

    @InjectMocks
    private ExperimentDemoServiceImpl experimentDemoService;

    @Test
    void generateUsedPhoneDemoShouldCleanupHistoricalTaggedExperimentsAndReturnLinks() {
        when(experimentService.listExperiments()).thenReturn(List.of(
                experiment("exp_old_pass", "二手手机售卖实验 [USED_PHONE_DEMO_PASS]"),
                experiment("exp_normal", "真实实验"),
                experiment("exp_old_fail", "二手手机售卖实验 [USED_PHONE_DEMO_FAIL]")
        ));
        when(experimentService.createExperiment(any(ExperimentCreateRequest.class)))
                .thenReturn(experiment("exp_pass", "二手手机售卖实验 [USED_PHONE_DEMO_PASS]"))
                .thenReturn(experiment("exp_fail", "二手手机售卖实验 [USED_PHONE_DEMO_FAIL]"));
        when(trafficService.assignGroup(anyString(), anyString(), anyMap()))
                .thenAnswer(invocation -> String.valueOf(invocation.<Map<String, Object>>getArgument(2)
                        .get("demoAssignedGroup")));
        when(analysisService.getStatistics("exp_pass")).thenReturn(statistics("exp_pass", "D", 0.70, 0.82));
        when(analysisService.getStatistics("exp_fail")).thenReturn(statistics("exp_fail", "D", 0.70, 0.705));
        when(analysisService.autoGraduateDecision("exp_pass")).thenReturn(Map.of(
                "canGraduate", true,
                "recommendedVariant", "D"
        ));
        when(analysisService.autoGraduateDecision("exp_fail")).thenReturn(Map.of(
                "canGraduate", false,
                "recommendedVariant", "D"
        ));
        when(analysisService.shouldEarlyStop("exp_pass", "D", "A", 0.95)).thenReturn(Map.of("canStop", true));
        when(analysisService.shouldEarlyStop("exp_fail", "D", "A", 0.95)).thenReturn(Map.of("canStop", false));

        ExperimentDemoService.ExperimentDemoResult result = experimentDemoService.generateUsedPhoneDemo();

        verify(experimentService).deleteExperiment("exp_old_pass");
        verify(experimentService).deleteExperiment("exp_old_fail");
        verify(experimentService, times(2)).createExperiment(any(ExperimentCreateRequest.class));
        verify(experimentService).startExperiment("exp_pass");
        verify(experimentService).startExperiment("exp_fail");
        verify(dataService, atLeastOnce()).reportExposure(eq("exp_pass"), anyString(), anyMap());
        verify(dataService, atLeastOnce()).reportEvent(eq("exp_pass"), anyString(), eq("VIEW"), anyString(), anyMap());

        ArgumentCaptor<ExperimentCreateRequest> requestCaptor = ArgumentCaptor.forClass(ExperimentCreateRequest.class);
        verify(experimentService, times(2)).createExperiment(requestCaptor.capture());
        assertThat(requestCaptor.getAllValues())
                .extracting(ExperimentCreateRequest::getName)
                .containsExactly("二手手机售卖页优化实验 [USED_PHONE_DEMO_PASS]",
                        "二手手机售卖页优化实验 [USED_PHONE_DEMO_FAIL]");
        assertThat(requestCaptor.getAllValues())
                .extracting(request -> request.getTraffic().getStrategy())
                .containsOnly("RULE");

        assertThat(result.getQualifiedExperiment().getExperimentId()).isEqualTo("exp_pass");
        assertThat(result.getQualifiedExperiment().getCanGraduate()).isTrue();
        assertThat(result.getQualifiedExperiment().getCanStop()).isTrue();
        assertThat(result.getQualifiedExperiment().getAutoGraduateUrl())
                .isEqualTo("/api/analysis/experiment/exp_pass/auto-graduate");
        assertThat(result.getUnqualifiedExperiment().getExperimentId()).isEqualTo("exp_fail");
        assertThat(result.getUnqualifiedExperiment().getCanGraduate()).isFalse();
        assertThat(result.getUnqualifiedExperiment().getCanStop()).isFalse();
    }

    @Test
    void generateUsedPhoneDemoShouldFailWhenQualifiedExperimentDoesNotMeetRequirements() {
        when(experimentService.listExperiments()).thenReturn(List.of());
        when(experimentService.createExperiment(any(ExperimentCreateRequest.class)))
                .thenReturn(experiment("exp_pass", "二手手机售卖实验 [USED_PHONE_DEMO_PASS]"))
                .thenReturn(experiment("exp_fail", "二手手机售卖实验 [USED_PHONE_DEMO_FAIL]"));
        when(trafficService.assignGroup(anyString(), anyString(), anyMap()))
                .thenAnswer(invocation -> String.valueOf(invocation.<Map<String, Object>>getArgument(2)
                        .get("demoAssignedGroup")));
        when(analysisService.getStatistics(anyString())).thenReturn(statistics("exp_pass", "D", 0.70, 0.82));
        when(analysisService.autoGraduateDecision("exp_pass")).thenReturn(Map.of(
                "canGraduate", false,
                "recommendedVariant", "D"
        ));
        when(analysisService.autoGraduateDecision("exp_fail")).thenReturn(Map.of(
                "canGraduate", false,
                "recommendedVariant", "D"
        ));
        when(analysisService.shouldEarlyStop("exp_pass", "D", "A", 0.95)).thenReturn(Map.of("canStop", true));
        when(analysisService.shouldEarlyStop("exp_fail", "D", "A", 0.95)).thenReturn(Map.of("canStop", false));

        assertThatThrownBy(() -> experimentDemoService.generateUsedPhoneDemo())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("达标实验");
    }

    private Experiment experiment(String experimentId, String experimentName) {
        Experiment experiment = new Experiment();
        experiment.setId(experimentId);
        experiment.setName(experimentName);
        return experiment;
    }

    private Statistics statistics(String experimentId, String bestGroupId, double baselineRate, double targetRate) {
        Statistics statistics = new Statistics();
        statistics.setExperimentId(experimentId);

        Statistics.ExperimentSummary summary = new Statistics.ExperimentSummary();
        summary.setBestPerformingGroup(bestGroupId);
        summary.setOverallConversionRate(targetRate);
        statistics.setSummary(summary);

        Statistics.GroupStatistics baseline = new Statistics.GroupStatistics();
        baseline.setGroupId("A");
        baseline.setConversionRate(baselineRate);

        Statistics.GroupStatistics target = new Statistics.GroupStatistics();
        target.setGroupId("D");
        target.setConversionRate(targetRate);

        Map<String, Statistics.GroupStatistics> groupStatistics = new LinkedHashMap<>();
        groupStatistics.put("A", baseline);
        groupStatistics.put("D", target);
        statistics.setGroupStatistics(groupStatistics);
        return statistics;
    }
}
