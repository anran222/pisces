package com.pisces.common.response;

import com.pisces.common.model.ExperimentDecisionContext;
import com.pisces.common.model.Statistics;
import com.pisces.common.request.AIDesignRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI决策协议形状测试
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/3/20 13:34
 */
public class AIDecisionResponseShapeTest {

    @Test
    void shouldExposeAIDesignRequestFields() {
        AIDesignRequest request = new AIDesignRequest();
        request.setBusinessScenario("二手手机详情页优化");
        request.setTargetMetric("支付转化率");
        request.setConstraints(List.of("突出质保", "文案简洁"));

        assertThat(request.getBusinessScenario()).isEqualTo("二手手机详情页优化");
        assertThat(request.getTargetMetric()).isEqualTo("支付转化率");
        assertThat(request.getConstraints()).containsExactly("突出质保", "文案简洁");
    }

    @Test
    void shouldExposeAIDesignResponseFields() {
        AIDesignResponse response = new AIDesignResponse();
        response.setDecisionType("DESIGN");
        response.setSummary("建议先做主图文案实验");
        response.setConfidence(0.92);
        response.setRiskFlags(List.of("样本量偏小"));
        response.setGuardrailStatus("PASS");

        assertThat(response.getDecisionType()).isEqualTo("DESIGN");
        assertThat(response.getSummary()).isEqualTo("建议先做主图文案实验");
        assertThat(response.getConfidence()).isEqualTo(0.92);
        assertThat(response.getRiskFlags()).containsExactly("样本量偏小");
        assertThat(response.getGuardrailStatus()).isEqualTo("PASS");
    }

    @Test
    void shouldExposeAIDiagnosisResponseFields() {
        AIDiagnosisResponse response = new AIDiagnosisResponse();
        response.setDecisionType("DIAGNOSIS");
        response.setSummary("当前实验整体健康");
        response.setConfidence(0.88);
        response.setRiskFlags(List.of("曝光数据延迟"));
        response.setGuardrailStatus("PASS");
        response.setRecommendedActions(List.of("继续观察", "补充数据"));

        assertThat(response.getDecisionType()).isEqualTo("DIAGNOSIS");
        assertThat(response.getSummary()).isEqualTo("当前实验整体健康");
        assertThat(response.getConfidence()).isEqualTo(0.88);
        assertThat(response.getRiskFlags()).containsExactly("曝光数据延迟");
        assertThat(response.getGuardrailStatus()).isEqualTo("PASS");
        assertThat(response.getRecommendedActions()).containsExactly("继续观察", "补充数据");
    }

    @Test
    void shouldExposeAIGraduationDecisionResponseFields() {
        AIGraduationDecisionResponse response = new AIGraduationDecisionResponse();
        response.setDecisionType("GRADUATION");
        response.setSummary("推荐进入毕业流程");
        response.setConfidence(0.95);
        response.setRiskFlags(List.of("护栏指标轻微波动"));
        response.setGuardrailStatus("PASS");
        response.setDecision("GRADUATE");

        assertThat(response.getDecisionType()).isEqualTo("GRADUATION");
        assertThat(response.getSummary()).isEqualTo("推荐进入毕业流程");
        assertThat(response.getConfidence()).isEqualTo(0.95);
        assertThat(response.getRiskFlags()).containsExactly("护栏指标轻微波动");
        assertThat(response.getGuardrailStatus()).isEqualTo("PASS");
        assertThat(response.getDecision()).isEqualTo("GRADUATE");
    }

    @Test
    void shouldExposeExperimentDecisionContextFields() {
        Statistics statistics = new Statistics();
        ExperimentDecisionContext context = new ExperimentDecisionContext();
        context.setExperimentId("exp_1");
        context.setExperimentName("二手手机详情页实验");
        context.setExperimentStatus("RUNNING");
        context.setStatistics(statistics);
        context.setAttributes(Map.of("scene", "detail-page"));

        assertThat(context.getExperimentId()).isEqualTo("exp_1");
        assertThat(context.getExperimentName()).isEqualTo("二手手机详情页实验");
        assertThat(context.getExperimentStatus()).isEqualTo("RUNNING");
        assertThat(context.getStatistics()).isSameAs(statistics);
        assertThat(context.getAttributes()).containsEntry("scene", "detail-page");
    }
}
