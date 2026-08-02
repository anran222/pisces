package com.pisces.service.ai;

import com.pisces.common.model.ExperimentDecisionContext;
import com.pisces.common.model.GroupConfigFieldDefinition;
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

    private final AIDesignContextResolver aiDesignContextResolver = new AIDesignContextResolver();

    @Test
    void shouldBuildDesignSchemaPlanningPromptWithBaselineAndExistingSchema() {
        PromptTemplateBuilder builder = new PromptTemplateBuilder(aiDesignContextResolver);
        AIDesignRequest request = new AIDesignRequest();
        request.setBusinessScenario("二手手机详情页");
        request.setTargetMetric("支付转化率");
        request.setConstraints(List.of("保护毛利率", "突出质检背书"));
        AIDesignRequest.DesignContext designContext = new AIDesignRequest.DesignContext();
        designContext.setSchemaKeys(List.of("mainTitle", "qualityTone"));
        designContext.setDraftGroupIds(List.of("control", "variant_a", "variant_b"));
        designContext.setTrafficStrategy("HASH");
        designContext.setPrioritizedConstraints(List.of("保护毛利率", "控制改动范围"));
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
        designPreferences.setDisabledSchemaKeys(List.of("showQualityBadge"));
        request.setDesignPreferences(designPreferences);
        AIDesignPlanningContext planningContext = aiDesignContextResolver.resolve(request);

        String prompt = builder.buildDesignSchemaPlanningPrompt(request, planningContext);

        assertThat(prompt).contains("二手手机详情页");
        assertThat(prompt).contains("支付转化率");
        assertThat(prompt).contains("保护毛利率");
        assertThat(prompt).contains("JSON");
        assertThat(prompt).contains("decisionType");
        assertThat(prompt).contains("schemaPlanning");
        assertThat(prompt).contains("baselineConfig");
        assertThat(prompt).contains("mainTitle");
        assertThat(prompt).contains("官方质检二手手机");
        assertThat(prompt).contains("showQualityBadge");
        assertThat(prompt).contains("disabledSchemaKeys");
        assertThat(prompt).contains("variant_b");
        assertThat(prompt).contains("key 必须使用 camelCase");
        assertThat(prompt).contains("新增字段数不能少于 5 个");
        assertThat(prompt).contains("不要输出 titleTemplateId");
    }

    @Test
    void shouldBuildDesignDraftFillingPromptWithCompleteConfigRequirement() {
        PromptTemplateBuilder builder = new PromptTemplateBuilder(aiDesignContextResolver);
        AIDesignRequest request = new AIDesignRequest();
        request.setBusinessScenario("二手手机详情页");
        request.setTargetMetric("支付转化率");
        request.setConstraints(List.of("保护毛利率"));
        AIDesignRequest.DesignContext designContext = new AIDesignRequest.DesignContext();
        designContext.setDraftGroupIds(List.of("control", "variant_a"));
        designContext.setTrafficStrategy("HASH");
        request.setDesignContext(designContext);
        request.setBaselineConfig(Map.of("mainTitle", "官方质检二手手机", "qualityTone", "稳重可信"));
        AIDesignPlanningContext planningContext = aiDesignContextResolver.resolve(request);
        List<GroupConfigFieldDefinition> schema = List.of(
                schemaField("mainTitle", "主标题", GroupConfigFieldDefinition.ValueType.STRING, true,
                        "商品标题主体", "官方质检二手手机"),
                schemaField("qualityTone", "质检语气", GroupConfigFieldDefinition.ValueType.STRING, true,
                        "标题语气风格", "稳重可信"),
                schemaField("benefitTags", "利益点标签", GroupConfigFieldDefinition.ValueType.JSON, true,
                        "展示给用户的标签集合", List.of("官方质检", "7天无理由"))
        );
        Map<String, String> fieldRoles = Map.of(
                "mainTitle", "BASELINE_STABLE",
                "qualityTone", "EXPERIMENT_VARIABLE",
                "benefitTags", "EXPERIMENT_VARIABLE"
        );

        String prompt = builder.buildDesignDraftFillingPrompt(request, planningContext, schema, fieldRoles);

        assertThat(prompt).contains("draftGeneration");
        assertThat(prompt).contains("controlConfig");
        assertThat(prompt).contains("variantConfigs");
        assertThat(prompt).contains("mainTitle");
        assertThat(prompt).contains("qualityTone");
        assertThat(prompt).contains("benefitTags");
        assertThat(prompt).contains("BASELINE_STABLE");
        assertThat(prompt).contains("EXPERIMENT_VARIABLE");
        assertThat(prompt).contains("商品标题主体");
        assertThat(prompt).contains("标题语气风格");
        assertThat(prompt).contains("展示给用户的标签集合");
        assertThat(prompt).contains("defaultValue=官方质检二手手机");
        assertThat(prompt).contains("defaultValue=稳重可信");
        assertThat(prompt).contains("allSchemaKeys");
        assertThat(prompt).contains("mainTitle, qualityTone, benefitTags");
        assertThat(prompt).contains("requiredSchemaKeys");
        assertThat(prompt).contains("controlConfig 输出骨架");
        assertThat(prompt).contains("\"mainTitle\": \"STRING\"");
        assertThat(prompt).contains("\"qualityTone\": \"STRING\"");
        assertThat(prompt).contains("\"benefitTags\": \"JSON\"");
        assertThat(prompt).contains("variantConfigs[groupId] 输出骨架");
        assertThat(prompt).contains("每个实验组必须覆盖 schema 中全部字段");
        assertThat(prompt).contains("任何 schema 字段都不允许缺失");
        assertThat(prompt).contains("即使字段 required=false，也必须在每个组配置中显式返回");
        assertThat(prompt).contains("filledGroups 至少返回 2 个组");
        assertThat(prompt).contains("官方质检二手手机");
    }

    @Test
    void shouldBuildGraduationPromptWithExperimentContext() {
        PromptTemplateBuilder builder = new PromptTemplateBuilder(aiDesignContextResolver);
        ExperimentDecisionContext context = new ExperimentDecisionContext();
        context.setExperimentId("exp_001");
        context.setExperimentName("二手手机售卖页优化实验");
        context.setExperimentStatus("RUNNING");
        context.setStatistics(statistics());
        context.setReportSnapshotFacts(List.of("latestReportSnapshotVersion=3", "latestReportConclusionStatus=GRADUATED"));

        String prompt = builder.buildGraduationPrompt(context);

        assertThat(prompt).contains("exp_001");
        assertThat(prompt).contains("二手手机售卖页优化实验");
        assertThat(prompt).contains("RUNNING");
        assertThat(prompt).contains("decision");
        assertThat(prompt).contains("PAYMENT_RATE");
        assertThat(prompt).contains("0.76");
        assertThat(prompt).contains("D");
        assertThat(prompt).contains("reportSnapshotFacts");
        assertThat(prompt).contains("latestReportSnapshotVersion=3");
        assertThat(prompt).contains("decisionHints: N/A");
    }

    @Test
    void shouldBuildGraduationPromptWithExplicitDecisionHints() {
        PromptTemplateBuilder builder = new PromptTemplateBuilder(aiDesignContextResolver);
        ExperimentDecisionContext context = new ExperimentDecisionContext();
        context.setExperimentId("exp_002");
        context.setExperimentName("二手手机售卖页优化实验");
        context.setExperimentStatus("RUNNING");
        context.setStatistics(statistics());
        context.setDecisionHints(List.of("这是固定未达标演示实验。请优先基于当前主指标、护栏和风险信号给出继续观察或不毕业建议，不要为了演示效果直接返回 GRADUATE。"));

        String prompt = builder.buildGraduationPrompt(context);

        assertThat(prompt).contains("exp_002");
        assertThat(prompt).contains("不要为了演示效果直接返回 GRADUATE");
    }

    @Test
    void shouldBuildDiagnosisPromptWithStructuredFacts() {
        PromptTemplateBuilder builder = new PromptTemplateBuilder(aiDesignContextResolver);
        ExperimentDecisionContext context = new ExperimentDecisionContext();
        context.setExperimentId("exp_001");
        context.setExperimentName("二手手机售卖页优化实验 [USED_PHONE_DEMO_PASS]");
        context.setExperimentStatus("RUNNING");
        context.setStatisticsFacts(List.of("bestPerformingGroup=D", "primaryMetricKey=PAYMENT_RATE"));
        context.setGroupMetricSnapshots(List.of("D(变体3): PAYMENT_RATE=0.76"));
        context.setDataQualityFacts(List.of("analysisReady=true", "sampleSizeReached=false"));
        context.setReportSnapshotFacts(List.of("latestReportSnapshotVersion=4"));
        context.setDecisionHints(List.of("固定演示实验"));

        String prompt = builder.buildDiagnosisPrompt(context);

        assertThat(prompt).contains("exp_001");
        assertThat(prompt).contains("二手手机售卖页优化实验");
        assertThat(prompt).contains("statisticsFacts");
        assertThat(prompt).contains("groupMetricSnapshot");
        assertThat(prompt).contains("dataQualityFacts");
        assertThat(prompt).contains("reportSnapshotFacts");
        assertThat(prompt).contains("PAYMENT_RATE");
        assertThat(prompt).contains("0.76");
        assertThat(prompt).contains("analysisReady=true");
        assertThat(prompt).contains("latestReportSnapshotVersion=4");
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

    private GroupConfigFieldDefinition schemaField(String key,
                                                   String label,
                                                   GroupConfigFieldDefinition.ValueType valueType,
                                                   boolean required) {
        return schemaField(key, label, valueType, required, null, null);
    }

    private GroupConfigFieldDefinition schemaField(String key,
                                                   String label,
                                                   GroupConfigFieldDefinition.ValueType valueType,
                                                   boolean required,
                                                   String description,
                                                   Object defaultValue) {
        GroupConfigFieldDefinition fieldDefinition = new GroupConfigFieldDefinition();
        fieldDefinition.setKey(key);
        fieldDefinition.setLabel(label);
        fieldDefinition.setValueType(valueType);
        fieldDefinition.setRequired(required);
        fieldDefinition.setDescription(description);
        fieldDefinition.setDefaultValue(defaultValue);
        return fieldDefinition;
    }
}
