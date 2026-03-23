package com.pisces.common.response;

import com.pisces.common.model.ExperimentDecisionContext;
import com.pisces.common.model.GroupConfigFieldDefinition;
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
        AIDesignRequest.DesignContext designContext = new AIDesignRequest.DesignContext();
        designContext.setSchemaKeys(List.of("mainTitle", "highlightTags"));
        designContext.setDraftGroupIds(List.of("control", "variant_a"));
        designContext.setTrafficStrategy("HASH");
        designContext.setPrioritizedConstraints(List.of("保护毛利率", "突出质检背书"));
        request.setDesignContext(designContext);
        request.setBaselineConfig(Map.of("mainTitle", "官方质检二手手机", "showQualityBadge", true));
        GroupConfigFieldDefinition fieldDefinition = new GroupConfigFieldDefinition();
        fieldDefinition.setKey("mainTitle");
        fieldDefinition.setLabel("主标题");
        fieldDefinition.setValueType(GroupConfigFieldDefinition.ValueType.STRING);
        fieldDefinition.setRequired(true);
        request.setExistingSchema(List.of(fieldDefinition));
        AIDesignRequest.DesignPreferences designPreferences = new AIDesignRequest.DesignPreferences();
        designPreferences.setExpectedGroupCount(3);
        designPreferences.setPreferredTrafficStrategy("HASH");
        designPreferences.setDisabledSchemaKeys(List.of("cardMeta"));
        request.setDesignPreferences(designPreferences);

        assertThat(request.getBusinessScenario()).isEqualTo("二手手机详情页优化");
        assertThat(request.getTargetMetric()).isEqualTo("支付转化率");
        assertThat(request.getConstraints()).containsExactly("突出质保", "文案简洁");
        assertThat(request.getDesignContext().getSchemaKeys()).containsExactly("mainTitle", "highlightTags");
        assertThat(request.getDesignContext().getDraftGroupIds()).containsExactly("control", "variant_a");
        assertThat(request.getDesignContext().getTrafficStrategy()).isEqualTo("HASH");
        assertThat(request.getDesignContext().getPrioritizedConstraints())
                .containsExactly("保护毛利率", "突出质检背书");
        assertThat(request.getBaselineConfig()).containsEntry("mainTitle", "官方质检二手手机");
        assertThat(request.getExistingSchema()).extracting(GroupConfigFieldDefinition::getKey)
                .containsExactly("mainTitle");
        assertThat(request.getDesignPreferences().getExpectedGroupCount()).isEqualTo(3);
        assertThat(request.getDesignPreferences().getPreferredTrafficStrategy()).isEqualTo("HASH");
        assertThat(request.getDesignPreferences().getDisabledSchemaKeys()).containsExactly("cardMeta");
    }

    @Test
    void shouldExposeAIDesignResponseFields() {
        AIDesignResponse response = new AIDesignResponse();
        response.setDecisionType("DESIGN");
        response.setSummary("建议先做主图文案实验");
        response.setConfidence(0.92);
        response.setRiskFlags(List.of("样本量偏小"));
        response.setGuardrailStatus("PASS");
        response.setSchemaPlanning(Map.of("plannedSchemaSize", 4, "baselineMode", "REUSE"));
        response.setDraftGeneration(Map.of("filledGroups", List.of("control", "variant_a")));

        assertThat(response.getDecisionType()).isEqualTo("DESIGN");
        assertThat(response.getSummary()).isEqualTo("建议先做主图文案实验");
        assertThat(response.getConfidence()).isEqualTo(0.92);
        assertThat(response.getRiskFlags()).containsExactly("样本量偏小");
        assertThat(response.getGuardrailStatus()).isEqualTo("PASS");
        assertThat(response.getSchemaPlanning()).containsEntry("plannedSchemaSize", 4);
        assertThat(response.getDraftGeneration()).containsKey("filledGroups");
    }

    @Test
    void shouldExposeAIDiagnosisResponseFields() {
        AIDiagnosisResponse response = new AIDiagnosisResponse();
        AIDiagnosisResponse.RecommendedAction firstAction = new AIDiagnosisResponse.RecommendedAction();
        firstAction.setTitle("继续观察");
        firstAction.setAction("继续观察实验核心指标变化");
        firstAction.setExecutionMode("MANUAL_ONLY");
        AIDiagnosisResponse.RecommendedAction secondAction = new AIDiagnosisResponse.RecommendedAction();
        secondAction.setTitle("补充数据");
        secondAction.setAction("补充样本后重新判断");
        secondAction.setExecutionMode("MANUAL_ONLY");
        response.setDecisionType("DIAGNOSIS");
        response.setSummary("当前实验整体健康");
        response.setConfidence(0.88);
        response.setRiskFlags(List.of("曝光数据延迟"));
        response.setGuardrailStatus("PASS");
        response.setRecommendedActions(List.of(firstAction, secondAction));

        assertThat(response.getDecisionType()).isEqualTo("DIAGNOSIS");
        assertThat(response.getSummary()).isEqualTo("当前实验整体健康");
        assertThat(response.getConfidence()).isEqualTo(0.88);
        assertThat(response.getRiskFlags()).containsExactly("曝光数据延迟");
        assertThat(response.getGuardrailStatus()).isEqualTo("PASS");
        assertThat(response.getRecommendedActions())
                .extracting(AIDiagnosisResponse.RecommendedAction::getTitle)
                .containsExactly("继续观察", "补充数据");
        assertThat(response.getRecommendedActions())
                .extracting(AIDiagnosisResponse.RecommendedAction::getExecutionMode)
                .containsExactly("MANUAL_ONLY", "MANUAL_ONLY");
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
