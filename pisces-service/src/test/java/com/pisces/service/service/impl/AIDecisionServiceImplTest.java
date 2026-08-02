package com.pisces.service.service.impl;

import com.pisces.common.model.ExperimentDecisionContext;
import com.pisces.common.model.GroupConfigFieldDefinition;
import com.pisces.common.model.Statistics;
import com.pisces.common.request.AIDesignRequest;
import com.pisces.common.response.AIDesignResponse;
import com.pisces.common.response.AIDiagnosisResponse;
import com.pisces.common.response.AIGraduationDecisionResponse;
import com.pisces.service.ai.AIDecisionJsonParser;
import com.pisces.service.ai.AIDesignContextResolver;
import com.pisces.service.ai.AIDesignPlanningContext;
import com.pisces.service.ai.DecisionGuardrailEvaluator;
import com.pisces.service.ai.DecisionType;
import com.pisces.service.ai.ExperimentDecisionContextBuilder;
import com.pisces.service.ai.GuardrailStatus;
import com.pisces.service.ai.PromptTemplateBuilder;
import com.pisces.service.ai.TongYiTextGenerationClient;
import com.pisces.service.schema.GroupConfigSchemaValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI决策服务实现测试
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/3/20 13:48
 */
@ExtendWith(MockitoExtension.class)
class AIDecisionServiceImplTest {

    @Mock
    private ExperimentDecisionContextBuilder experimentDecisionContextBuilder;
    @Mock
    private PromptTemplateBuilder promptTemplateBuilder;
    @Mock
    private AIDecisionJsonParser aiDecisionJsonParser;
    @Mock
    private DecisionGuardrailEvaluator decisionGuardrailEvaluator;
    @Mock
    private TongYiTextGenerationClient tongYiTextGenerationClient;
    @Mock
    private GroupConfigSchemaValidator groupConfigSchemaValidator;

    private final AIDesignContextResolver aiDesignContextResolver = new AIDesignContextResolver();

    @Test
    void designExperimentShouldCallTongYiTwiceAndAttachDynamicExperimentDraft() {
        AIDecisionServiceImpl aiDecisionService = new AIDecisionServiceImpl(
                experimentDecisionContextBuilder,
                promptTemplateBuilder,
                aiDesignContextResolver,
                aiDecisionJsonParser,
                decisionGuardrailEvaluator,
                tongYiTextGenerationClient,
                groupConfigSchemaValidator);
        AIDesignRequest request = new AIDesignRequest();
        request.setBusinessScenario("checkout");
        request.setTargetMetric("conversion");
        request.setConstraints(List.of("avoid regression"));
        request.setBaselineConfig(Map.of("mainTitle", "官方质检二手手机"));
        AIDesignRequest.DesignPreferences designPreferences = new AIDesignRequest.DesignPreferences();
        designPreferences.setExpectedGroupCount(2);
        request.setDesignPreferences(designPreferences);
        AIDesignResponse planningResponse = new AIDesignResponse();
        planningResponse.setDecisionType(DecisionType.DESIGN.getCode());
        planningResponse.setGuardrailStatus(GuardrailStatus.PASS.getCode());
        planningResponse.setSchemaPlanning(Map.of(
                "groupConfigSchema", List.of(
                        Map.of("key", "mainTitle", "label", "主标题", "valueType", "STRING", "required", true),
                        Map.of("key", "qualityTone", "label", "质检语气", "valueType", "STRING", "required", true),
                        Map.of("key", "benefitTags", "label", "利益点标签", "valueType", "JSON", "required", true),
                        Map.of("key", "subtitle", "label", "副标题", "valueType", "STRING", "required", true),
                        Map.of("key", "showQualityBadge", "label", "展示质检标识", "valueType", "BOOLEAN", "required", true),
                        Map.of("key", "badgeCount", "label", "标签数量", "valueType", "INTEGER", "required", true)),
                "fieldRoles", Map.of(
                        "mainTitle", "BASELINE_STABLE",
                        "qualityTone", "EXPERIMENT_VARIABLE",
                        "benefitTags", "EXPERIMENT_VARIABLE",
                        "subtitle", "EXPERIMENT_VARIABLE",
                        "showQualityBadge", "EXPERIMENT_VARIABLE",
                        "badgeCount", "EXPERIMENT_VARIABLE")));
        AIDesignResponse draftResponse = new AIDesignResponse();
        draftResponse.setDecisionType(DecisionType.DESIGN.getCode());
        draftResponse.setGuardrailStatus(GuardrailStatus.PASS.getCode());
        draftResponse.setDraftGeneration(Map.of(
                "controlConfig", Map.of(
                        "mainTitle", "AI建议标题",
                        "qualityTone", "稳重可信",
                        "benefitTags", List.of("官方质检", "7天无理由"),
                        "subtitle", "官方质检 放心下单",
                        "showQualityBadge", true,
                        "badgeCount", 2),
                "variantConfigs", Map.of(
                        "variant_a", Map.of(
                                "mainTitle", "放心买官方质检二手手机",
                                "qualityTone", "强背书",
                                "benefitTags", List.of("官方质检", "品质保障"),
                                "subtitle", "官方质检 顺丰发货",
                                "showQualityBadge", true,
                                "badgeCount", 2))));
        List<GroupConfigFieldDefinition> normalizedSchema = List.of(
                schemaField("mainTitle", "主标题", GroupConfigFieldDefinition.ValueType.STRING, true),
                schemaField("qualityTone", "质检语气", GroupConfigFieldDefinition.ValueType.STRING, true),
                schemaField("benefitTags", "利益点标签", GroupConfigFieldDefinition.ValueType.JSON, true),
                schemaField("subtitle", "副标题", GroupConfigFieldDefinition.ValueType.STRING, true),
                schemaField("showQualityBadge", "展示质检标识", GroupConfigFieldDefinition.ValueType.BOOLEAN, true),
                schemaField("badgeCount", "标签数量", GroupConfigFieldDefinition.ValueType.INTEGER, true));

        when(promptTemplateBuilder.buildDesignSchemaPlanningPrompt(eq(request), any(AIDesignPlanningContext.class)))
                .thenReturn("schema-planning-prompt");
        when(tongYiTextGenerationClient.generateText(anyString(), eq("schema-planning-prompt"), eq("AI实验设计-字段规划")))
                .thenReturn("{\"decisionType\":\"DESIGN\",\"stage\":\"planning\"}");
        when(aiDecisionJsonParser.parseDesign("{\"decisionType\":\"DESIGN\",\"stage\":\"planning\"}"))
                .thenReturn(planningResponse);
        when(groupConfigSchemaValidator.normalizeSchema(anyList())).thenReturn(normalizedSchema);
        when(promptTemplateBuilder.buildDesignDraftFillingPrompt(eq(request), any(AIDesignPlanningContext.class),
                eq(normalizedSchema), eq(Map.of(
                        "mainTitle", "BASELINE_STABLE",
                        "qualityTone", "EXPERIMENT_VARIABLE",
                        "benefitTags", "EXPERIMENT_VARIABLE",
                        "subtitle", "EXPERIMENT_VARIABLE",
                        "showQualityBadge", "EXPERIMENT_VARIABLE",
                        "badgeCount", "EXPERIMENT_VARIABLE"))))
                .thenReturn("draft-filling-prompt");
        when(tongYiTextGenerationClient.generateText(anyString(), eq("draft-filling-prompt"), eq("AI实验设计-草案填充")))
                .thenReturn("{\"decisionType\":\"DESIGN\",\"stage\":\"draft\"}");
        when(aiDecisionJsonParser.parseDesign("{\"decisionType\":\"DESIGN\",\"stage\":\"draft\"}"))
                .thenReturn(draftResponse);
        when(groupConfigSchemaValidator.normalizeCompleteGroupConfig(eq(normalizedSchema),
                eq(Map.of(
                        "mainTitle", "官方质检二手手机",
                        "qualityTone", "稳重可信",
                        "benefitTags", List.of("官方质检", "7天无理由"),
                        "subtitle", "官方质检 放心下单",
                        "showQualityBadge", true,
                        "badgeCount", 2)),
                eq("control")))
                .thenReturn(Map.of(
                        "mainTitle", "官方质检二手手机",
                        "qualityTone", "稳重可信",
                        "benefitTags", List.of("官方质检", "7天无理由"),
                        "subtitle", "官方质检 放心下单",
                        "showQualityBadge", true,
                        "badgeCount", 2));
        when(groupConfigSchemaValidator.normalizeCompleteGroupConfig(eq(normalizedSchema),
                eq(Map.of(
                        "mainTitle", "放心买官方质检二手手机",
                        "qualityTone", "强背书",
                        "benefitTags", List.of("官方质检", "品质保障"),
                        "subtitle", "官方质检 顺丰发货",
                        "showQualityBadge", true,
                        "badgeCount", 2)),
                eq("variant_a")))
                .thenReturn(Map.of(
                        "mainTitle", "放心买官方质检二手手机",
                        "qualityTone", "强背书",
                        "benefitTags", List.of("官方质检", "品质保障"),
                        "subtitle", "官方质检 顺丰发货",
                        "showQualityBadge", true,
                        "badgeCount", 2));

        AIDesignResponse response = aiDecisionService.designExperiment(request);

        assertThat(response.getDecisionType()).isEqualTo(DecisionType.DESIGN.getCode());
        assertThat(response.getSchemaPlanning()).containsKey("groupConfigSchema");
        assertThat(response.getDraftGeneration()).containsKey("variantConfigs");
        assertNotNull(response.getExperimentDraft());
        assertThat(response.getExperimentDraft().getGroups().get(0).getConfig())
                .containsEntry("mainTitle", "官方质检二手手机")
                .containsEntry("qualityTone", "稳重可信");
        verify(promptTemplateBuilder).buildDesignSchemaPlanningPrompt(eq(request), any(AIDesignPlanningContext.class));
        verify(promptTemplateBuilder).buildDesignDraftFillingPrompt(eq(request), any(AIDesignPlanningContext.class),
                eq(normalizedSchema), eq(Map.of(
                        "mainTitle", "BASELINE_STABLE",
                        "qualityTone", "EXPERIMENT_VARIABLE",
                        "benefitTags", "EXPERIMENT_VARIABLE",
                        "subtitle", "EXPERIMENT_VARIABLE",
                        "showQualityBadge", "EXPERIMENT_VARIABLE",
                        "badgeCount", "EXPERIMENT_VARIABLE")));
        verify(tongYiTextGenerationClient).generateText(anyString(), eq("schema-planning-prompt"), eq("AI实验设计-字段规划"));
        verify(tongYiTextGenerationClient).generateText(anyString(), eq("draft-filling-prompt"), eq("AI实验设计-草案填充"));
    }

    @Test
    void designExperimentShouldThrowWhenSchemaPlanningPayloadIsInvalid() {
        AIDecisionServiceImpl aiDecisionService = new AIDecisionServiceImpl(
                experimentDecisionContextBuilder,
                promptTemplateBuilder,
                aiDesignContextResolver,
                aiDecisionJsonParser,
                decisionGuardrailEvaluator,
                tongYiTextGenerationClient,
                groupConfigSchemaValidator);
        AIDesignRequest request = new AIDesignRequest();
        request.setBusinessScenario("checkout");
        request.setTargetMetric("conversion");
        request.setConstraints(List.of("avoid regression"));

        when(promptTemplateBuilder.buildDesignSchemaPlanningPrompt(eq(request), any(AIDesignPlanningContext.class)))
                .thenReturn("schema-planning-prompt");
        when(tongYiTextGenerationClient.generateText(anyString(), eq("schema-planning-prompt"), eq("AI实验设计-字段规划")))
                .thenReturn("{invalid-json}");
        when(aiDecisionJsonParser.parseDesign(anyString()))
                .thenThrow(new IllegalArgumentException("AI决策结果不是合法JSON"));

        assertThatThrownBy(() -> aiDecisionService.designExperiment(request))
                .isInstanceOf(com.pisces.service.exception.BusinessException.class)
                .hasMessageContaining("AI实验设计暂不可用");
    }

    @Test
    void designExperimentShouldThrowWhenSchemaPlanningAddsLessThanFiveNewFields() {
        AIDecisionServiceImpl aiDecisionService = new AIDecisionServiceImpl(
                experimentDecisionContextBuilder,
                promptTemplateBuilder,
                aiDesignContextResolver,
                aiDecisionJsonParser,
                decisionGuardrailEvaluator,
                tongYiTextGenerationClient,
                groupConfigSchemaValidator);
        AIDesignRequest request = new AIDesignRequest();
        request.setBusinessScenario("checkout");
        request.setTargetMetric("conversion");
        request.setBaselineConfig(Map.of("mainTitle", "官方质检二手手机"));
        request.setExistingSchema(List.of(schemaField("mainTitle", "主标题",
                GroupConfigFieldDefinition.ValueType.STRING, true)));
        AIDesignResponse planningResponse = new AIDesignResponse();
        planningResponse.setDecisionType(DecisionType.DESIGN.getCode());
        planningResponse.setGuardrailStatus(GuardrailStatus.PASS.getCode());
        planningResponse.setSchemaPlanning(Map.of(
                "groupConfigSchema", List.of(
                        Map.of("key", "mainTitle", "label", "主标题", "valueType", "STRING", "required", true),
                        Map.of("key", "qualityTone", "label", "质检语气", "valueType", "STRING", "required", true),
                        Map.of("key", "benefitTags", "label", "利益点标签", "valueType", "JSON", "required", true),
                        Map.of("key", "subtitle", "label", "副标题", "valueType", "STRING", "required", true),
                        Map.of("key", "showQualityBadge", "label", "展示质检标识", "valueType", "BOOLEAN", "required", true))));
        List<GroupConfigFieldDefinition> normalizedSchema = List.of(
                schemaField("mainTitle", "主标题", GroupConfigFieldDefinition.ValueType.STRING, true),
                schemaField("qualityTone", "质检语气", GroupConfigFieldDefinition.ValueType.STRING, true),
                schemaField("benefitTags", "利益点标签", GroupConfigFieldDefinition.ValueType.JSON, true),
                schemaField("subtitle", "副标题", GroupConfigFieldDefinition.ValueType.STRING, true),
                schemaField("showQualityBadge", "展示质检标识", GroupConfigFieldDefinition.ValueType.BOOLEAN, true));

        when(promptTemplateBuilder.buildDesignSchemaPlanningPrompt(eq(request), any(AIDesignPlanningContext.class)))
                .thenReturn("schema-planning-prompt");
        when(tongYiTextGenerationClient.generateText(anyString(), eq("schema-planning-prompt"), eq("AI实验设计-字段规划")))
                .thenReturn("{\"decisionType\":\"DESIGN\",\"stage\":\"planning-too-small\"}");
        when(aiDecisionJsonParser.parseDesign("{\"decisionType\":\"DESIGN\",\"stage\":\"planning-too-small\"}"))
                .thenReturn(planningResponse);
        when(groupConfigSchemaValidator.normalizeSchema(anyList())).thenReturn(normalizedSchema);

        assertThatThrownBy(() -> aiDecisionService.designExperiment(request))
                .isInstanceOf(com.pisces.service.exception.BusinessException.class)
                .hasMessageContaining("新增字段数不能少于 5 个");
        verify(promptTemplateBuilder, never()).buildDesignDraftFillingPrompt(any(), any(), anyList(), any());
    }

    @Test
    void diagnoseExperimentShouldOverrideGuardrailAndForceManualOnlyAction() {
        AIDecisionServiceImpl aiDecisionService = new AIDecisionServiceImpl(
                experimentDecisionContextBuilder,
                promptTemplateBuilder,
                aiDesignContextResolver,
                aiDecisionJsonParser,
                decisionGuardrailEvaluator,
                tongYiTextGenerationClient,
                groupConfigSchemaValidator);
        ExperimentDecisionContext context = decisionContextWithStatistics("exp_001");
        AIDiagnosisResponse expected = new AIDiagnosisResponse();
        expected.setDecisionType(DecisionType.DIAGNOSIS.getCode());
        expected.setGuardrailStatus(GuardrailStatus.PASS.getCode());
        AIDiagnosisResponse.RecommendedAction action = new AIDiagnosisResponse.RecommendedAction();
        action.setTitle("自动调流");
        action.setAction("立即提升流量");
        action.setExecutionMode("AUTO");
        expected.setRecommendedActions(List.of(action));

        when(experimentDecisionContextBuilder.buildForExperiment("exp_001")).thenReturn(context);
        when(promptTemplateBuilder.buildDiagnosisPrompt(context)).thenReturn("diagnosis-prompt");
        when(decisionGuardrailEvaluator.evaluateDiagnosis(context)).thenReturn(GuardrailStatus.BLOCKED);
        when(decisionGuardrailEvaluator.collectRiskFlags(context)).thenReturn(List.of("SRM"));
        when(tongYiTextGenerationClient.generateText(anyString(), eq("diagnosis-prompt"), eq("AI实验诊断")))
                .thenReturn("{\"decisionType\":\"DIAGNOSIS\"}");
        when(aiDecisionJsonParser.parseDiagnosis(anyString())).thenReturn(expected);

        AIDiagnosisResponse response = aiDecisionService.diagnoseExperiment("exp_001");

        assertSame(expected, response);
        assertThat(response.getGuardrailStatus()).isEqualTo(GuardrailStatus.BLOCKED.getCode());
        assertThat(response.getRecommendedActions().get(0).getExecutionMode()).isEqualTo("MANUAL_ONLY");
        assertThat(response.getEvidence().getExperimentId()).isEqualTo("exp_001");
        assertThat(response.getEvidence().getAnalysisReady()).isFalse();
        assertThat(response.getEvidence().getHasSrm()).isTrue();
        assertThat(response.getEvidence().getBlockingIssues()).containsExactly("样本量不足");
        assertThat(response.getEvidence().getPrimaryMetricKey()).isEqualTo("PAYMENT_RATE");
        assertThat(response.getEvidence().getLatestReportSnapshotVersion()).isEqualTo(7);
        assertThat(response.getEvidence().getGroupMetricSnapshots())
                .containsExactly("variant_a(实验组A): PAYMENT_RATE=0.31");
    }

    @Test
    void decideGraduationShouldDowngradeBlockedResultToContinue() {
        AIDecisionServiceImpl aiDecisionService = new AIDecisionServiceImpl(
                experimentDecisionContextBuilder,
                promptTemplateBuilder,
                aiDesignContextResolver,
                aiDecisionJsonParser,
                decisionGuardrailEvaluator,
                tongYiTextGenerationClient,
                groupConfigSchemaValidator);
        ExperimentDecisionContext context = decisionContextWithStatistics("exp_001");
        AIGraduationDecisionResponse expected = new AIGraduationDecisionResponse();
        expected.setDecisionType(DecisionType.GRADUATION.getCode());
        expected.setGuardrailStatus(GuardrailStatus.PASS.getCode());
        expected.setDecision("GRADUATE");

        when(experimentDecisionContextBuilder.buildForExperiment("exp_001")).thenReturn(context);
        when(promptTemplateBuilder.buildGraduationPrompt(context)).thenReturn("graduation-prompt");
        when(decisionGuardrailEvaluator.evaluateGraduation(context)).thenReturn(GuardrailStatus.BLOCKED);
        when(decisionGuardrailEvaluator.collectRiskFlags(context)).thenReturn(List.of("SRM"));
        when(tongYiTextGenerationClient.generateText(anyString(), eq("graduation-prompt"), eq("AI毕业决策")))
                .thenReturn("{\"decisionType\":\"GRADUATION\"}");
        when(aiDecisionJsonParser.parseGraduation(anyString())).thenReturn(expected);

        AIGraduationDecisionResponse response = aiDecisionService.decideGraduation("exp_001");

        assertSame(expected, response);
        assertThat(response.getGuardrailStatus()).isEqualTo(GuardrailStatus.BLOCKED.getCode());
        assertThat(response.getDecision()).isEqualTo("CONTINUE");
        assertThat(response.getEvidence().getAnalysisReady()).isFalse();
        assertThat(response.getEvidence().getSampleSizeReached()).isFalse();
        assertThat(response.getEvidence().getRequiredSampleSizePerGroup()).isEqualTo(1000L);
        assertThat(response.getEvidence().getBreachedGuardrails()).containsExactly("MARGIN_DROP");
        assertThat(response.getEvidence().getLatestReportConclusionStatus()).isEqualTo("GRADUATED");
        assertThat(response.getEvidence().getLatestReportBreachedGuardrails()).containsExactly("MARGIN_DROP");
        assertThat(response.getEvidence().getDataQualityFacts())
                .containsExactly("analysisReady=false", "blockingIssues=[样本量不足]");
    }

    @Test
    void diagnoseExperimentShouldFallbackToStructuredResponseWhenAiPayloadIsInvalid() {
        AIDecisionServiceImpl aiDecisionService = new AIDecisionServiceImpl(
                experimentDecisionContextBuilder,
                promptTemplateBuilder,
                aiDesignContextResolver,
                aiDecisionJsonParser,
                decisionGuardrailEvaluator,
                tongYiTextGenerationClient,
                groupConfigSchemaValidator);
        ExperimentDecisionContext context = new ExperimentDecisionContext();
        context.setExperimentId("exp_001");
        context.setExperimentName("新客首单优惠");

        when(experimentDecisionContextBuilder.buildForExperiment("exp_001")).thenReturn(context);
        when(promptTemplateBuilder.buildDiagnosisPrompt(context)).thenReturn("diagnosis-prompt");
        when(decisionGuardrailEvaluator.evaluateDiagnosis(context)).thenReturn(GuardrailStatus.PASS);
        when(decisionGuardrailEvaluator.collectRiskFlags(context)).thenReturn(List.of("SRM"));
        when(tongYiTextGenerationClient.generateText(anyString(), eq("diagnosis-prompt"), eq("AI实验诊断")))
                .thenReturn("{invalid-json}");
        when(aiDecisionJsonParser.parseDiagnosis(anyString()))
                .thenThrow(new IllegalArgumentException("AI决策结果不是合法JSON"));

        AIDiagnosisResponse response = aiDecisionService.diagnoseExperiment("exp_001");

        assertThat(response.getDecisionType()).isEqualTo(DecisionType.DIAGNOSIS.getCode());
        assertThat(response.getGuardrailStatus()).isEqualTo(GuardrailStatus.PASS.getCode());
        assertThat(response.getRiskFlags()).contains("SRM", "AI_UNAVAILABLE");
        assertThat(response.getRecommendedActions()).hasSize(1);
        assertThat(response.getRecommendedActions().get(0).getExecutionMode()).isEqualTo("MANUAL_ONLY");
        assertThat(response.getEvidence().getExperimentId()).isEqualTo("exp_001");
        assertThat(response.getEvidence().getBlockingIssues()).isEmpty();
    }

    @Test
    void decideGraduationShouldFallbackToContinueWhenAiPayloadIsInvalid() {
        AIDecisionServiceImpl aiDecisionService = new AIDecisionServiceImpl(
                experimentDecisionContextBuilder,
                promptTemplateBuilder,
                aiDesignContextResolver,
                aiDecisionJsonParser,
                decisionGuardrailEvaluator,
                tongYiTextGenerationClient,
                groupConfigSchemaValidator);
        ExperimentDecisionContext context = new ExperimentDecisionContext();
        context.setExperimentId("exp_001");
        context.setExperimentName("新客首单优惠");

        when(experimentDecisionContextBuilder.buildForExperiment("exp_001")).thenReturn(context);
        when(promptTemplateBuilder.buildGraduationPrompt(context)).thenReturn("graduation-prompt");
        when(decisionGuardrailEvaluator.evaluateGraduation(context)).thenReturn(GuardrailStatus.PASS);
        when(decisionGuardrailEvaluator.collectRiskFlags(context)).thenReturn(List.of("ANALYSIS_NOT_READY"));
        when(tongYiTextGenerationClient.generateText(anyString(), eq("graduation-prompt"), eq("AI毕业决策")))
                .thenReturn("{invalid-json}");
        when(aiDecisionJsonParser.parseGraduation(anyString()))
                .thenThrow(new IllegalArgumentException("AI决策结果不是合法JSON"));

        AIGraduationDecisionResponse response = aiDecisionService.decideGraduation("exp_001");

        assertThat(response.getDecisionType()).isEqualTo(DecisionType.GRADUATION.getCode());
        assertThat(response.getDecision()).isEqualTo("CONTINUE");
        assertThat(response.getGuardrailStatus()).isEqualTo(GuardrailStatus.PASS.getCode());
        assertThat(response.getRiskFlags()).contains("ANALYSIS_NOT_READY", "AI_UNAVAILABLE");
        assertThat(response.getEvidence().getExperimentId()).isEqualTo("exp_001");
    }

    private GroupConfigFieldDefinition schemaField(String key,
                                                   String label,
                                                   GroupConfigFieldDefinition.ValueType valueType,
                                                   boolean required) {
        GroupConfigFieldDefinition fieldDefinition = new GroupConfigFieldDefinition();
        fieldDefinition.setKey(key);
        fieldDefinition.setLabel(label);
        fieldDefinition.setValueType(valueType);
        fieldDefinition.setRequired(required);
        return fieldDefinition;
    }

    private ExperimentDecisionContext decisionContextWithStatistics(String experimentId) {
        Statistics.ExperimentSummary summary = new Statistics.ExperimentSummary();
        summary.setPrimaryMetricKey("PAYMENT_RATE");
        summary.setBestPerformingGroup("variant_a");
        summary.setBestPrimaryMetricValue(0.31D);
        summary.setTotalAssignments(1200L);
        summary.setTotalExposures(1100L);
        summary.setTotalEvents(342L);
        summary.setTotalVisitors(980L);
        summary.setBreachedGuardrails(List.of("MARGIN_DROP"));

        Statistics.DataQualityCheck dataQualityCheck = new Statistics.DataQualityCheck();
        dataQualityCheck.setAnalysisReady(false);
        dataQualityCheck.setHasSrm(true);
        dataQualityCheck.setSrmPValue(0.001D);
        dataQualityCheck.setSampleSizeReached(false);
        dataQualityCheck.setRequiredSampleSizePerGroup(1000L);
        dataQualityCheck.setBlockingIssues(List.of("样本量不足"));
        dataQualityCheck.setWarnings(List.of("曝光数据延迟"));

        Statistics statistics = new Statistics();
        statistics.setExperimentId(experimentId);
        statistics.setExperimentName("新客首单优惠");
        statistics.setExperimentStatus("RUNNING");
        statistics.setSummary(summary);
        statistics.setDataQualityCheck(dataQualityCheck);

        ExperimentDecisionContext context = new ExperimentDecisionContext();
        context.setExperimentId(experimentId);
        context.setExperimentName("新客首单优惠");
        context.setExperimentStatus("RUNNING");
        context.setStatistics(statistics);
        context.setStatisticsFacts(List.of("primaryMetricKey=PAYMENT_RATE"));
        context.setGroupMetricSnapshots(List.of("variant_a(实验组A): PAYMENT_RATE=0.31"));
        context.setDataQualityFacts(List.of("analysisReady=false", "blockingIssues=[样本量不足]"));
        context.setLatestReportSnapshotVersion(7);
        context.setLatestReportGeneratedAt(LocalDateTime.of(2026, 3, 21, 10, 0));
        context.setLatestReportConclusionStatus("GRADUATED");
        context.setLatestReportAnalysisReady(false);
        context.setLatestReportHasSrm(true);
        context.setLatestReportPrimaryMetricKey("PAYMENT_RATE");
        context.setLatestReportBestPerformingGroup("variant_a");
        context.setLatestReportWinningVariant("variant_a");
        context.setLatestReportBreachedGuardrails(List.of("MARGIN_DROP"));
        context.setReportSnapshotFacts(List.of("latestReportSnapshotVersion=7", "latestReportConclusionStatus=GRADUATED"));
        return context;
    }
}
