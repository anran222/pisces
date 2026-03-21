package com.pisces.service.ai;

import com.pisces.common.model.ExperimentDecisionContext;
import com.pisces.common.model.Statistics;
import com.pisces.common.request.AIDesignRequest;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prompt模板构建器测试
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/3/20 14:09
 */
class PromptTemplateBuilderTest {

    @Test
    void shouldBuildDesignPromptWithStructuredOutputConstraint() {
        PromptTemplateBuilder builder = new PromptTemplateBuilder();
        AIDesignRequest request = new AIDesignRequest();
        request.setBusinessScenario("checkout");
        request.setTargetMetric("conversion");
        request.setConstraints(List.of("avoid revenue regression", "protect user experience"));

        String prompt = builder.buildDesignPrompt(request);

        assertThat(prompt).contains("checkout");
        assertThat(prompt).contains("conversion");
        assertThat(prompt).contains("avoid revenue regression");
        assertThat(prompt).contains("JSON");
        assertThat(prompt).contains("decisionType");
        assertThat(prompt).contains("guardrailStatus");
    }

    @Test
    void shouldBuildGraduationPromptWithExperimentContext() {
        PromptTemplateBuilder builder = new PromptTemplateBuilder();
        ExperimentDecisionContext context = new ExperimentDecisionContext();
        context.setExperimentId("exp_001");
        context.setExperimentName("二手手机售卖页优化实验 [USED_PHONE_DEMO_PASS]");
        context.setExperimentStatus("RUNNING");
        context.setStatistics(statistics());

        String prompt = builder.buildGraduationPrompt(context);

        assertThat(prompt).contains("exp_001");
        assertThat(prompt).contains("二手手机售卖页优化实验");
        assertThat(prompt).contains("RUNNING");
        assertThat(prompt).contains("decision");
        assertThat(prompt).contains("PAYMENT_RATE");
        assertThat(prompt).contains("0.76");
        assertThat(prompt).contains("D");
        assertThat(prompt).contains("固定演示实验");
    }

    private Statistics statistics() {
        Statistics statistics = new Statistics();
        Statistics.ExperimentSummary summary = new Statistics.ExperimentSummary();
        summary.setBestPerformingGroup("D");
        summary.setPrimaryMetricKey("PAYMENT_RATE");
        summary.setBestPrimaryMetricValue(0.76);
        statistics.setSummary(summary);

        Statistics.GroupStatistics baseline = new Statistics.GroupStatistics();
        baseline.setGroupId("A");
        baseline.setGroupName("基准组");
        baseline.setMetricValues(Map.of("PAYMENT_RATE", 0.60));

        Statistics.GroupStatistics winning = new Statistics.GroupStatistics();
        winning.setGroupId("D");
        winning.setGroupName("变体3");
        winning.setMetricValues(Map.of("PAYMENT_RATE", 0.76));

        Statistics.DataQualityCheck dataQualityCheck = new Statistics.DataQualityCheck();
        dataQualityCheck.setAnalysisReady(true);
        dataQualityCheck.setSampleSizeReached(false);
        statistics.setDataQualityCheck(dataQualityCheck);

        Map<String, Statistics.GroupStatistics> groupStatistics = new LinkedHashMap<>();
        groupStatistics.put("A", baseline);
        groupStatistics.put("D", winning);
        statistics.setGroupStatistics(groupStatistics);
        return statistics;
    }
}
