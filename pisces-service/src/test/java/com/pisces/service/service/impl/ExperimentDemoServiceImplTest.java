package com.pisces.service.service.impl;

import com.pisces.common.model.Experiment;
import com.pisces.common.model.ExperimentDecisionContext;
import com.pisces.common.model.Statistics;
import com.pisces.common.request.ExperimentCreateRequest;
import com.pisces.common.response.AIGraduationDecisionResponse;
import com.pisces.service.ai.ExperimentDecisionContextBuilder;
import com.pisces.service.service.AnalysisService;
import com.pisces.service.service.AIDecisionService;
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

    @Mock
    private AIDecisionService aiDecisionService;

    @Mock
    private ExperimentDecisionContextBuilder experimentDecisionContextBuilder;

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
        when(analysisService.shouldEarlyStop("exp_pass", "D", "A", 0.95)).thenReturn(Map.of("canStop", true));
        when(analysisService.shouldEarlyStop("exp_fail", "D", "A", 0.95)).thenReturn(Map.of("canStop", false));
        mockDemoGraduation("exp_pass", "GRADUATE", "PASS", "AI判断当前实验可以毕业");
        mockDemoGraduation("exp_fail", "CONTINUE", "PASS", "AI判断当前实验暂不毕业");

        ExperimentDemoService.ExperimentDemoResult result = experimentDemoService.generateUsedPhoneDemo();

        verify(experimentService).deleteExperiment("exp_old_pass");
        verify(experimentService).deleteExperiment("exp_old_fail");
        verify(experimentService, times(2)).createExperiment(any(ExperimentCreateRequest.class));
        verify(experimentService).startExperiment("exp_pass");
        verify(experimentService).startExperiment("exp_fail");
        verify(analysisService).drainEventPipeline("exp_pass", "demo-generator");
        verify(analysisService).drainEventPipeline("exp_fail", "demo-generator");
        verify(analysisService).replayEventPipeline("exp_pass", "demo-generator");
        verify(analysisService).replayEventPipeline("exp_fail", "demo-generator");
        verify(trafficService, times(400)).assignGroup(anyString(), anyString(), anyMap());
        verify(dataService, atLeastOnce()).reportExposure(eq("exp_pass"), anyString(), anyMap());
        verify(dataService, atLeastOnce()).reportEvent(eq("exp_pass"), anyString(), eq("PRODUCT_VIEW"), anyString(), anyMap());

        ArgumentCaptor<ExperimentCreateRequest> requestCaptor = ArgumentCaptor.forClass(ExperimentCreateRequest.class);
        verify(experimentService, times(2)).createExperiment(requestCaptor.capture());
        assertThat(requestCaptor.getAllValues())
                .extracting(ExperimentCreateRequest::getName)
                .containsExactly("二手手机售卖页优化实验 [USED_PHONE_DEMO_PASS]",
                        "二手手机售卖页优化实验 [USED_PHONE_DEMO_FAIL]");
        assertThat(requestCaptor.getAllValues())
                .extracting(request -> request.getTraffic().getStrategy())
                .containsOnly("RULE");
        assertThat(requestCaptor.getAllValues())
                .allSatisfy(request -> {
                    assertThat(request.getEventDefinitions()).isNotEmpty();
                    assertThat(request.getEventDefinitions())
                            .extracting(definition -> definition.getKey())
                            .containsExactly("PRODUCT_VIEW", "CONSULT_CLICK", "PAY_SUCCESS");
                    assertThat(request.getMetricDefinitions()).isNotEmpty();
                    assertThat(request.getMetricDefinitions())
                            .extracting(definition -> definition.getKey())
                            .containsExactly("PAYMENT_RATE", "CONSULT_RATE");
                    assertThat(request.getGroupConfigSchema()).isNotEmpty();
                    assertThat(request.getGroupConfigSchema())
                            .extracting(field -> field.getKey())
                            .containsExactly("titlePrimaryText", "titleToneStyle", "highlightedFeature",
                                    "brandConsistencyCheck", "misleadingContentFlag", "productCategory");
                    assertThat(request.getGroups()).allSatisfy(group ->
                            assertThat(group.getConfig()).containsKeys("titlePrimaryText", "titleToneStyle",
                                    "highlightedFeature", "brandConsistencyCheck", "misleadingContentFlag",
                                    "productCategory"));
                });

        assertThat(result.getQualifiedExperiment().getExperimentId()).isEqualTo("exp_pass");
        assertThat(result.getQualifiedExperiment().getCanGraduate()).isTrue();
        assertThat(result.getQualifiedExperiment().getCanStop()).isTrue();
        assertThat(result.getQualifiedExperiment().getAiDecision()).isEqualTo("GRADUATE");
        assertThat(result.getQualifiedExperiment().getAiGuardrailStatus()).isEqualTo("PASS");
        assertThat(result.getQualifiedExperiment().getAiSummary()).isEqualTo("AI判断当前实验可以毕业");
        assertThat(result.getQualifiedExperiment().getPrimaryMetricKey()).isEqualTo("PAYMENT_RATE");
        assertThat(result.getQualifiedExperiment().getGroupCount()).isEqualTo(4);
        assertThat(result.getQualifiedExperiment().getSchemaFieldCount()).isEqualTo(6);
        assertThat(result.getQualifiedExperiment().getAutoGraduateUrl())
                .isEqualTo("/api/analysis/experiment/exp_pass/ai-graduation-decision");
        assertThat(result.getUnqualifiedExperiment().getExperimentId()).isEqualTo("exp_fail");
        assertThat(result.getUnqualifiedExperiment().getCanGraduate()).isFalse();
        assertThat(result.getUnqualifiedExperiment().getCanStop()).isFalse();
        assertThat(result.getUnqualifiedExperiment().getAiDecision()).isEqualTo("CONTINUE");
        assertThat(result.getUnqualifiedExperiment().getAiSummary()).isEqualTo("AI判断当前实验暂不毕业");
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
        when(analysisService.shouldEarlyStop("exp_pass", "D", "A", 0.95)).thenReturn(Map.of("canStop", true));
        mockDemoGraduation("exp_pass", "CONTINUE", "PASS", null);

        assertThatThrownBy(() -> experimentDemoService.generateUsedPhoneDemo())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("达标实验");
        verify(experimentService).deleteExperiment("exp_pass");
    }

    @Test
    void generateUsedPhoneDemoShouldInvokeAiGraduationDecision() {
        when(experimentService.listExperiments()).thenReturn(List.of());
        when(experimentService.createExperiment(any(ExperimentCreateRequest.class)))
                .thenReturn(experiment("exp_pass", "二手手机售卖实验 [USED_PHONE_DEMO_PASS]"))
                .thenReturn(experiment("exp_fail", "二手手机售卖实验 [USED_PHONE_DEMO_FAIL]"));
        when(trafficService.assignGroup(anyString(), anyString(), anyMap()))
                .thenAnswer(invocation -> String.valueOf(invocation.<Map<String, Object>>getArgument(2)
                        .get("demoAssignedGroup")));
        when(analysisService.getStatistics("exp_pass")).thenReturn(statistics("exp_pass", "D", 0.70, 0.82));
        when(analysisService.getStatistics("exp_fail")).thenReturn(statistics("exp_fail", "D", 0.70, 0.705));
        when(analysisService.shouldEarlyStop("exp_pass", "D", "A", 0.95)).thenReturn(Map.of("canStop", true));
        when(analysisService.shouldEarlyStop("exp_fail", "D", "A", 0.95)).thenReturn(Map.of("canStop", false));
        mockDemoGraduation("exp_pass", "GRADUATE", "PASS", null);
        mockDemoGraduation("exp_fail", "CONTINUE", "PASS", null);

        ExperimentDemoService.ExperimentDemoResult result = experimentDemoService.generateUsedPhoneDemo();

        verify(aiDecisionService, times(2)).decideGraduation(any(ExperimentDecisionContext.class));
        assertThat(result.getQualifiedExperiment().getCanGraduate()).isTrue();
        assertThat(result.getQualifiedExperiment().getCanStop()).isTrue();
        assertThat(result.getUnqualifiedExperiment().getCanGraduate()).isFalse();
        assertThat(result.getUnqualifiedExperiment().getCanStop()).isFalse();
    }

    @Test
    void generateUsedPhoneDemoShouldFallbackStopDecisionWhenDemoLiftIsClear() {
        when(experimentService.listExperiments()).thenReturn(List.of());
        when(experimentService.createExperiment(any(ExperimentCreateRequest.class)))
                .thenReturn(experiment("exp_pass", "二手手机售卖实验 [USED_PHONE_DEMO_PASS]"))
                .thenReturn(experiment("exp_fail", "二手手机售卖实验 [USED_PHONE_DEMO_FAIL]"));
        when(trafficService.assignGroup(anyString(), anyString(), anyMap()))
                .thenAnswer(invocation -> String.valueOf(invocation.<Map<String, Object>>getArgument(2)
                        .get("demoAssignedGroup")));
        when(analysisService.getStatistics("exp_pass")).thenReturn(statistics("exp_pass", "D", 0.60, 0.76));
        when(analysisService.getStatistics("exp_fail")).thenReturn(statistics("exp_fail", "D", 0.60, 0.62));
        when(analysisService.shouldEarlyStop("exp_pass", "D", "A", 0.95)).thenReturn(Map.of("canStop", false));
        when(analysisService.shouldEarlyStop("exp_fail", "D", "A", 0.95)).thenReturn(Map.of("canStop", false));
        mockDemoGraduation("exp_pass", "GRADUATE", "PASS", null);
        mockDemoGraduation("exp_fail", "CONTINUE", "PASS", null);

        ExperimentDemoService.ExperimentDemoResult result = experimentDemoService.generateUsedPhoneDemo();

        assertThat(result.getQualifiedExperiment().getCanGraduate()).isTrue();
        assertThat(result.getQualifiedExperiment().getCanStop()).isFalse();
        assertThat(result.getUnqualifiedExperiment().getCanGraduate()).isFalse();
        assertThat(result.getUnqualifiedExperiment().getCanStop()).isFalse();
    }

    @Test
    void generateUsedPhoneDemoShouldUsePrimaryMetricWhenLegacyConversionRateIsEmpty() {
        when(experimentService.listExperiments()).thenReturn(List.of());
        when(experimentService.createExperiment(any(ExperimentCreateRequest.class)))
                .thenReturn(experiment("exp_pass", "二手手机售卖实验 [USED_PHONE_DEMO_PASS]"))
                .thenReturn(experiment("exp_fail", "二手手机售卖实验 [USED_PHONE_DEMO_FAIL]"));
        when(trafficService.assignGroup(anyString(), anyString(), anyMap()))
                .thenAnswer(invocation -> String.valueOf(invocation.<Map<String, Object>>getArgument(2)
                        .get("demoAssignedGroup")));
        when(analysisService.getStatistics("exp_pass")).thenReturn(statisticsWithPrimaryMetric("exp_pass", "D", 0.60, 0.76));
        when(analysisService.getStatistics("exp_fail")).thenReturn(statisticsWithPrimaryMetric("exp_fail", "D", 0.60, 0.62));
        when(analysisService.shouldEarlyStop("exp_pass", "D", "A", 0.95)).thenReturn(Map.of("canStop", false));
        when(analysisService.shouldEarlyStop("exp_fail", "D", "A", 0.95)).thenReturn(Map.of("canStop", false));
        mockDemoGraduation("exp_pass", "GRADUATE", "PASS", null);
        mockDemoGraduation("exp_fail", "CONTINUE", "PASS", null);

        ExperimentDemoService.ExperimentDemoResult result = experimentDemoService.generateUsedPhoneDemo();

        assertThat(result.getQualifiedExperiment().getCanGraduate()).isTrue();
        assertThat(result.getQualifiedExperiment().getCanStop()).isFalse();
        assertThat(result.getQualifiedExperiment().getBaselineConversionRate()).isEqualTo(0.60);
        assertThat(result.getQualifiedExperiment().getWinningConversionRate()).isEqualTo(0.76);
        assertThat(result.getQualifiedExperiment().getPrimaryMetricKey()).isEqualTo("PAYMENT_RATE");
        assertThat(result.getUnqualifiedExperiment().getCanGraduate()).isFalse();
        assertThat(result.getUnqualifiedExperiment().getCanStop()).isFalse();
    }

    @Test
    void generateUsedPhoneDemoShouldFailWhenAiGraduationApprovesUnqualifiedExperiment() {
        when(experimentService.listExperiments()).thenReturn(List.of());
        when(experimentService.createExperiment(any(ExperimentCreateRequest.class)))
                .thenReturn(experiment("exp_pass", "二手手机售卖实验 [USED_PHONE_DEMO_PASS]"))
                .thenReturn(experiment("exp_fail", "二手手机售卖实验 [USED_PHONE_DEMO_FAIL]"));
        when(trafficService.assignGroup(anyString(), anyString(), anyMap()))
                .thenAnswer(invocation -> String.valueOf(invocation.<Map<String, Object>>getArgument(2)
                        .get("demoAssignedGroup")));
        when(analysisService.getStatistics("exp_pass")).thenReturn(statistics("exp_pass", "D", 0.70, 0.82));
        when(analysisService.getStatistics("exp_fail")).thenReturn(statistics("exp_fail", "D", 0.70, 0.705));
        when(analysisService.shouldEarlyStop("exp_pass", "D", "A", 0.95)).thenReturn(Map.of("canStop", true));
        when(analysisService.shouldEarlyStop("exp_fail", "D", "A", 0.95)).thenReturn(Map.of("canStop", false));
        mockDemoGraduation("exp_pass", "GRADUATE", "PASS", null);
        mockDemoGraduation("exp_fail", "GRADUATE", "PASS", null);

        assertThatThrownBy(() -> experimentDemoService.generateUsedPhoneDemo())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未达标实验");
        verify(experimentService).deleteExperiment("exp_pass");
        verify(experimentService).deleteExperiment("exp_fail");
    }

    @Test
    void generateUsedPhoneDemoShouldUseLocalDecisionWhenAiUnavailableForQualifiedDemo() {
        when(experimentService.listExperiments()).thenReturn(List.of());
        when(experimentService.createExperiment(any(ExperimentCreateRequest.class)))
                .thenReturn(experiment("exp_pass", "二手手机售卖实验 [USED_PHONE_DEMO_PASS]"))
                .thenReturn(experiment("exp_fail", "二手手机售卖实验 [USED_PHONE_DEMO_FAIL]"));
        when(trafficService.assignGroup(anyString(), anyString(), anyMap()))
                .thenAnswer(invocation -> String.valueOf(invocation.<Map<String, Object>>getArgument(2)
                        .get("demoAssignedGroup")));
        when(analysisService.getStatistics("exp_pass")).thenReturn(statistics("exp_pass", "D", 0.70, 0.82));
        when(analysisService.getStatistics("exp_fail")).thenReturn(statistics("exp_fail", "D", 0.70, 0.705));
        when(analysisService.shouldEarlyStop("exp_pass", "D", "A", 0.95)).thenReturn(Map.of("canStop", true));
        when(analysisService.shouldEarlyStop("exp_fail", "D", "A", 0.95)).thenReturn(Map.of("canStop", false));
        mockDemoGraduation("exp_pass", "CONTINUE", "PASS", null, List.of("AI_UNAVAILABLE"));
        mockDemoGraduation("exp_fail", "CONTINUE", "PASS", null, List.of("AI_UNAVAILABLE"));

        ExperimentDemoService.ExperimentDemoResult result = experimentDemoService.generateUsedPhoneDemo();

        assertThat(result.getQualifiedExperiment().getCanGraduate()).isTrue();
        assertThat(result.getQualifiedExperiment().getAiDecision()).isEqualTo("GRADUATE");
        assertThat(result.getQualifiedExperiment().getAiSummary()).contains("本地确定性结论");
        assertThat(result.getUnqualifiedExperiment().getCanGraduate()).isFalse();
    }

    @Test
    void generateUsedPhoneDemoShouldPassDifferentHintsToPassAndFailCases() {
        when(experimentService.listExperiments()).thenReturn(List.of());
        when(experimentService.createExperiment(any(ExperimentCreateRequest.class)))
                .thenReturn(experiment("exp_pass", "二手手机售卖实验 [USED_PHONE_DEMO_PASS]"))
                .thenReturn(experiment("exp_fail", "二手手机售卖实验 [USED_PHONE_DEMO_FAIL]"));
        when(trafficService.assignGroup(anyString(), anyString(), anyMap()))
                .thenAnswer(invocation -> String.valueOf(invocation.<Map<String, Object>>getArgument(2)
                        .get("demoAssignedGroup")));
        when(analysisService.getStatistics("exp_pass")).thenReturn(statistics("exp_pass", "D", 0.70, 0.82));
        when(analysisService.getStatistics("exp_fail")).thenReturn(statistics("exp_fail", "D", 0.70, 0.705));
        when(analysisService.shouldEarlyStop("exp_pass", "D", "A", 0.95)).thenReturn(Map.of("canStop", true));
        when(analysisService.shouldEarlyStop("exp_fail", "D", "A", 0.95)).thenReturn(Map.of("canStop", false));
        mockDemoGraduation("exp_pass", "GRADUATE", "PASS", null);
        mockDemoGraduation("exp_fail", "CONTINUE", "PASS", null);

        experimentDemoService.generateUsedPhoneDemo();

        ArgumentCaptor<ExperimentDecisionContext> contextCaptor = ArgumentCaptor.forClass(ExperimentDecisionContext.class);
        verify(aiDecisionService, times(2)).decideGraduation(contextCaptor.capture());
        assertThat(contextCaptor.getAllValues())
                .extracting(ExperimentDecisionContext::getDecisionHints)
                .containsExactly(
                        List.of("这是固定达标演示实验。请优先依据当前主指标和最佳组表现给出演示性毕业建议，不要因为样本量门槛而保守返回 CONTINUE。"),
                        List.of("这是固定未达标演示实验。请优先基于当前主指标、护栏和风险信号给出继续观察或不毕业建议，不要为了演示效果直接返回 GRADUATE。"));
    }

    private void mockDemoGraduation(String experimentId, String decision, String guardrailStatus, String summary) {
        mockDemoGraduation(experimentId, decision, guardrailStatus, summary, List.of());
    }

    private void mockDemoGraduation(String experimentId, String decision, String guardrailStatus, String summary,
                                    List<String> riskFlags) {
        ExperimentDecisionContext context = new ExperimentDecisionContext();
        context.setExperimentId(experimentId);
        context.setExperimentName("demo-" + experimentId);
        when(experimentDecisionContextBuilder.buildForExperiment(experimentId)).thenReturn(context);

        AIGraduationDecisionResponse response = new AIGraduationDecisionResponse();
        response.setDecision(decision);
        response.setGuardrailStatus(guardrailStatus);
        response.setSummary(summary);
        response.setRiskFlags(riskFlags);
        when(aiDecisionService.decideGraduation(eq(context))).thenReturn(response);
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
        summary.setPrimaryMetricKey("PAYMENT_RATE");
        summary.setBestPrimaryMetricValue(targetRate);
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

    private Statistics statisticsWithPrimaryMetric(String experimentId, String bestGroupId,
                                                   double baselinePrimaryMetricValue,
                                                   double targetPrimaryMetricValue) {
        Statistics statistics = new Statistics();
        statistics.setExperimentId(experimentId);

        Statistics.ExperimentSummary summary = new Statistics.ExperimentSummary();
        summary.setBestPerformingGroup(bestGroupId);
        summary.setPrimaryMetricKey("PAYMENT_RATE");
        summary.setBestPrimaryMetricValue(targetPrimaryMetricValue);
        statistics.setSummary(summary);

        Statistics.GroupStatistics baseline = new Statistics.GroupStatistics();
        baseline.setGroupId("A");
        baseline.setConversionRate(0.0);
        baseline.setMetricValues(Map.of("PAYMENT_RATE", baselinePrimaryMetricValue));

        Statistics.GroupStatistics target = new Statistics.GroupStatistics();
        target.setGroupId("D");
        target.setConversionRate(0.0);
        target.setMetricValues(Map.of("PAYMENT_RATE", targetPrimaryMetricValue));

        Map<String, Statistics.GroupStatistics> groupStatistics = new LinkedHashMap<>();
        groupStatistics.put("A", baseline);
        groupStatistics.put("D", target);
        statistics.setGroupStatistics(groupStatistics);
        return statistics;
    }
}
