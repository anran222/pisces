package com.pisces.common.response;

import com.pisces.common.model.ExperimentDecisionContext;
import com.pisces.common.model.GroupConfigFieldDefinition;
import com.pisces.common.model.Statistics;
import com.pisces.common.request.AIDesignRequest;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
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
        AIDecisionEvidenceResponse evidence = decisionEvidence();
        response.setEvidence(evidence);

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
        assertThat(response.getEvidence()).isSameAs(evidence);
        assertThat(response.getEvidence().getAnalysisReady()).isFalse();
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
        AIDecisionEvidenceResponse evidence = decisionEvidence();
        response.setEvidence(evidence);

        assertThat(response.getDecisionType()).isEqualTo("GRADUATION");
        assertThat(response.getSummary()).isEqualTo("推荐进入毕业流程");
        assertThat(response.getConfidence()).isEqualTo(0.95);
        assertThat(response.getRiskFlags()).containsExactly("护栏指标轻微波动");
        assertThat(response.getGuardrailStatus()).isEqualTo("PASS");
        assertThat(response.getDecision()).isEqualTo("GRADUATE");
        assertThat(response.getEvidence()).isSameAs(evidence);
        assertThat(response.getEvidence().getBlockingIssues()).containsExactly("样本量不足");
    }

    @Test
    void shouldExposeAIDecisionEvidenceResponseFields() {
        AIDecisionEvidenceResponse evidence = decisionEvidence();

        assertThat(evidence.getExperimentId()).isEqualTo("exp_1");
        assertThat(evidence.getExperimentStatus()).isEqualTo("RUNNING");
        assertThat(evidence.getAnalysisReady()).isFalse();
        assertThat(evidence.getHasSrm()).isTrue();
        assertThat(evidence.getSrmPValue()).isEqualTo(0.001);
        assertThat(evidence.getSampleSizeReached()).isFalse();
        assertThat(evidence.getRequiredSampleSizePerGroup()).isEqualTo(1000L);
        assertThat(evidence.getPrimaryMetricKey()).isEqualTo("PAYMENT_RATE");
        assertThat(evidence.getBestPerformingGroup()).isEqualTo("variant_a");
        assertThat(evidence.getBestPrimaryMetricValue()).isEqualTo(0.31);
        assertThat(evidence.getTotalAssignments()).isEqualTo(1200L);
        assertThat(evidence.getTotalExposures()).isEqualTo(1100L);
        assertThat(evidence.getTotalEvents()).isEqualTo(342L);
        assertThat(evidence.getTotalVisitors()).isEqualTo(980L);
        assertThat(evidence.getWarnings()).containsExactly("曝光数据延迟");
        assertThat(evidence.getBreachedGuardrails()).containsExactly("MARGIN_DROP");
        assertThat(evidence.getLatestReportSnapshotVersion()).isEqualTo(7);
        assertThat(evidence.getLatestReportGeneratedAt()).isEqualTo(LocalDateTime.of(2026, 3, 21, 10, 0));
        assertThat(evidence.getLatestReportConclusionStatus()).isEqualTo("GRADUATED");
        assertThat(evidence.getLatestReportAnalysisReady()).isFalse();
        assertThat(evidence.getLatestReportHasSrm()).isTrue();
        assertThat(evidence.getLatestReportPrimaryMetricKey()).isEqualTo("PAYMENT_RATE");
        assertThat(evidence.getLatestReportBestPerformingGroup()).isEqualTo("variant_a");
        assertThat(evidence.getLatestReportWinningVariant()).isEqualTo("variant_a");
        assertThat(evidence.getLatestReportBreachedGuardrails()).containsExactly("MARGIN_DROP");
        assertThat(evidence.getStatisticsFacts()).containsExactly("primaryMetricKey=PAYMENT_RATE");
        assertThat(evidence.getReportSnapshotFacts()).containsExactly("latestReportSnapshotVersion=7");
    }

    @Test
    void shouldExposeExperimentDecisionContextFields() {
        Statistics statistics = new Statistics();
        ExperimentDecisionContext context = new ExperimentDecisionContext();
        context.setExperimentId("exp_1");
        context.setExperimentName("二手手机详情页实验");
        context.setExperimentStatus("RUNNING");
        context.setStatistics(statistics);
        context.setLatestReportSnapshotVersion(7);
        context.setLatestReportGeneratedAt(LocalDateTime.of(2026, 3, 21, 10, 0));
        context.setLatestReportConclusionStatus("GRADUATED");
        context.setLatestReportAnalysisReady(false);
        context.setLatestReportHasSrm(true);
        context.setLatestReportPrimaryMetricKey("PAYMENT_RATE");
        context.setLatestReportBestPerformingGroup("variant_a");
        context.setLatestReportWinningVariant("variant_a");
        context.setLatestReportBreachedGuardrails(List.of("MARGIN_DROP"));
        context.setReportSnapshotFacts(List.of("latestReportSnapshotVersion=7"));
        context.setAttributes(Map.of("scene", "detail-page"));

        assertThat(context.getExperimentId()).isEqualTo("exp_1");
        assertThat(context.getExperimentName()).isEqualTo("二手手机详情页实验");
        assertThat(context.getExperimentStatus()).isEqualTo("RUNNING");
        assertThat(context.getStatistics()).isSameAs(statistics);
        assertThat(context.getLatestReportSnapshotVersion()).isEqualTo(7);
        assertThat(context.getLatestReportConclusionStatus()).isEqualTo("GRADUATED");
        assertThat(context.getLatestReportBreachedGuardrails()).containsExactly("MARGIN_DROP");
        assertThat(context.getReportSnapshotFacts()).containsExactly("latestReportSnapshotVersion=7");
        assertThat(context.getAttributes()).containsEntry("scene", "detail-page");
    }

    private AIDecisionEvidenceResponse decisionEvidence() {
        AIDecisionEvidenceResponse evidence = new AIDecisionEvidenceResponse();
        evidence.setExperimentId("exp_1");
        evidence.setExperimentName("二手手机详情页实验");
        evidence.setExperimentStatus("RUNNING");
        evidence.setAnalysisReady(false);
        evidence.setHasSrm(true);
        evidence.setSrmPValue(0.001);
        evidence.setSampleSizeReached(false);
        evidence.setRequiredSampleSizePerGroup(1000L);
        evidence.setBlockingIssues(List.of("样本量不足"));
        evidence.setWarnings(List.of("曝光数据延迟"));
        evidence.setPrimaryMetricKey("PAYMENT_RATE");
        evidence.setBestPerformingGroup("variant_a");
        evidence.setBestPrimaryMetricValue(0.31);
        evidence.setTotalAssignments(1200L);
        evidence.setTotalExposures(1100L);
        evidence.setTotalEvents(342L);
        evidence.setTotalVisitors(980L);
        evidence.setBreachedGuardrails(List.of("MARGIN_DROP"));
        evidence.setLatestReportSnapshotVersion(7);
        evidence.setLatestReportGeneratedAt(LocalDateTime.of(2026, 3, 21, 10, 0));
        evidence.setLatestReportConclusionStatus("GRADUATED");
        evidence.setLatestReportAnalysisReady(false);
        evidence.setLatestReportHasSrm(true);
        evidence.setLatestReportPrimaryMetricKey("PAYMENT_RATE");
        evidence.setLatestReportBestPerformingGroup("variant_a");
        evidence.setLatestReportWinningVariant("variant_a");
        evidence.setLatestReportBreachedGuardrails(List.of("MARGIN_DROP"));
        evidence.setStatisticsFacts(List.of("primaryMetricKey=PAYMENT_RATE"));
        evidence.setGroupMetricSnapshots(List.of("variant_a(实验组A): PAYMENT_RATE=0.31"));
        evidence.setDataQualityFacts(List.of("analysisReady=false"));
        evidence.setReportSnapshotFacts(List.of("latestReportSnapshotVersion=7"));
        return evidence;
    }
}
