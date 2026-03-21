package com.pisces.service.service.impl;

import com.pisces.common.model.GroupConfigFieldDefinition;
import com.pisces.common.request.AIDesignRequest;
import com.pisces.common.model.ExperimentDecisionContext;
import com.pisces.common.response.AIDesignResponse;
import com.pisces.common.response.AIDiagnosisResponse;
import com.pisces.common.response.AIGraduationDecisionResponse;
import com.pisces.common.request.ExperimentCreateRequest;
import com.pisces.service.ai.AIDecisionJsonParser;
import com.pisces.service.ai.DecisionGuardrailEvaluator;
import com.pisces.service.ai.DecisionType;
import com.pisces.service.ai.ExperimentDecisionContextBuilder;
import com.pisces.service.ai.GuardrailStatus;
import com.pisces.service.ai.PromptTemplateBuilder;
import com.pisces.service.ai.TongYiTextGenerationClient;
import com.pisces.service.service.AIDecisionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AI决策服务实现
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/3/20 13:48
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AIDecisionServiceImpl implements AIDecisionService {

    private static final String DEFAULT_DESIGN_SCENARIO = "通用业务场景";
    private static final String DESIGN_OPERATION_NAME = "AI实验设计";
    private static final String DIAGNOSIS_OPERATION_NAME = "AI实验诊断";
    private static final String GRADUATION_OPERATION_NAME = "AI毕业决策";
    private static final String DESIGN_SYSTEM_PROMPT = "你是实验平台的AI设计助手，只能基于输入信息输出结构化JSON，不要补充Markdown。";
    private static final String DIAGNOSIS_SYSTEM_PROMPT = "你是实验平台的AI诊断助手，只能基于给定实验事实输出结构化JSON，不要输出Markdown或执行自动化动作。";
    private static final String GRADUATION_SYSTEM_PROMPT = "你是实验平台的AI毕业决策助手，只能基于给定实验事实输出结构化JSON，不要输出Markdown或自动执行实验变更。";
    private static final String DEFAULT_GRADUATION_DECISION = "CONTINUE";
    private static final String DEFAULT_BASELINE_GROUP_ID = "control";
    private static final String DEFAULT_BASELINE_GROUP_NAME = "对照组";
    private static final String DEFAULT_VARIANT_GROUP_ID = "variant_a";
    private static final String DEFAULT_VARIANT_GROUP_NAME = "实验组A";
    private static final String MAIN_TITLE_KEY = "mainTitle";
    private static final String SUBTITLE_KEY = "subtitle";
    private static final String SHOW_QUALITY_BADGE_KEY = "showQualityBadge";
    private static final String BADGE_COUNT_KEY = "badgeCount";
    private static final String CARD_META_KEY = "cardMeta";
    private static final String HIGHLIGHT_TAGS_KEY = "highlightTags";
    private static final String DEFAULT_TRAFFIC_STRATEGY = "HASH";
    private static final double DEFAULT_TRAFFIC_RATIO = 0.5D;
    private static final double DEFAULT_TOTAL_TRAFFIC = 1.0D;
    private static final String DESIGN_NAME_SUFFIX = "实验";
    private static final String ACTION_EXECUTION_MODE_MANUAL_ONLY = "MANUAL_ONLY";
    private static final String BLOCKED_ACTION_TITLE = "先修复数据质量问题";
    private static final String BLOCKED_ACTION_DESCRIPTION = "优先处理 SRM、样本量或其他阻断问题，再重新评估实验";
    private static final String PASS_ACTION_TITLE = "继续观察实验";
    private static final String PASS_ACTION_DESCRIPTION = "保持当前实验运行，结合业务节奏复核关键指标变化";

    private final ExperimentDecisionContextBuilder experimentDecisionContextBuilder;
    private final PromptTemplateBuilder promptTemplateBuilder;
    private final AIDecisionJsonParser aiDecisionJsonParser;
    private final DecisionGuardrailEvaluator decisionGuardrailEvaluator;
    private final TongYiTextGenerationClient tongYiTextGenerationClient;

    @Override
    public AIDesignResponse designExperiment(AIDesignRequest request) {
        String prompt = promptTemplateBuilder.buildDesignPrompt(request);
        if (!StringUtils.hasText(prompt)) {
            throw new IllegalStateException("AI设计Prompt不能为空");
        }
        AIDesignResponse response = aiDecisionJsonParser.parseDesign(
                tongYiTextGenerationClient.generateText(DESIGN_SYSTEM_PROMPT, prompt, DESIGN_OPERATION_NAME));
        response.setDecisionType(DecisionType.DESIGN.getCode());
        response.setExperimentDraft(createExperimentDraft(request));
        return response;
    }

    @Override
    public AIDiagnosisResponse diagnoseExperiment(String experimentId) {
        ExperimentDecisionContext context = experimentDecisionContextBuilder.buildForExperiment(experimentId);
        String prompt = promptTemplateBuilder.buildDiagnosisPrompt(context);
        if (!StringUtils.hasText(prompt)) {
            throw new IllegalStateException("AI诊断Prompt不能为空");
        }
        GuardrailStatus guardrailStatus = decisionGuardrailEvaluator.evaluateDiagnosis(context);
        AIDiagnosisResponse response = aiDecisionJsonParser.parseDiagnosis(
                tongYiTextGenerationClient.generateText(DIAGNOSIS_SYSTEM_PROMPT, prompt, DIAGNOSIS_OPERATION_NAME));
        response.setDecisionType(DecisionType.DIAGNOSIS.getCode());
        response.setGuardrailStatus(guardrailStatus.getCode());
        response.setRiskFlags(mergeRiskFlags(response.getRiskFlags(), decisionGuardrailEvaluator.collectRiskFlags(context)));
        response.setRecommendedActions(normalizeDiagnosisActions(response.getRecommendedActions(), guardrailStatus));
        return response;
    }

    @Override
    public AIGraduationDecisionResponse decideGraduation(String experimentId) {
        ExperimentDecisionContext context = experimentDecisionContextBuilder.buildForExperiment(experimentId);
        String prompt = promptTemplateBuilder.buildGraduationPrompt(context);
        if (!StringUtils.hasText(prompt)) {
            throw new IllegalStateException("AI毕业决策Prompt不能为空");
        }
        GuardrailStatus guardrailStatus = decisionGuardrailEvaluator.evaluateGraduation(context);
        AIGraduationDecisionResponse response = aiDecisionJsonParser.parseGraduation(
                tongYiTextGenerationClient.generateText(GRADUATION_SYSTEM_PROMPT, prompt, GRADUATION_OPERATION_NAME));
        response.setDecisionType(DecisionType.GRADUATION.getCode());
        response.setGuardrailStatus(guardrailStatus.getCode());
        response.setRiskFlags(mergeRiskFlags(response.getRiskFlags(), decisionGuardrailEvaluator.collectRiskFlags(context)));
        if (GuardrailStatus.BLOCKED.equals(guardrailStatus)) {
            response.setDecision(DEFAULT_GRADUATION_DECISION);
        }
        log.info("AI毕业决策完成: experimentId={}, decision={}, guardrailStatus={}, riskFlags={}",
                experimentId, response.getDecision(), response.getGuardrailStatus(), response.getRiskFlags());
        return response;
    }

    private String defaultValue(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value.trim() : defaultValue;
    }

    private ExperimentCreateRequest createExperimentDraft(AIDesignRequest request) {
        ExperimentCreateRequest draft = new ExperimentCreateRequest();
        String businessScenario = request == null ? null : request.getBusinessScenario();
        String targetMetric = request == null ? null : request.getTargetMetric();
        draft.setName(defaultValue(businessScenario, DEFAULT_DESIGN_SCENARIO) + DESIGN_NAME_SUFFIX);
        draft.setDescription("目标指标: " + defaultValue(targetMetric, "待补充"));
        draft.setGroupConfigSchema(createDefaultGroupConfigSchema());
        draft.setGroups(List.of(
                createGroup(DEFAULT_BASELINE_GROUP_ID, DEFAULT_BASELINE_GROUP_NAME, DEFAULT_TRAFFIC_RATIO),
                createGroup(DEFAULT_VARIANT_GROUP_ID, DEFAULT_VARIANT_GROUP_NAME, DEFAULT_TRAFFIC_RATIO)));
        draft.setTraffic(createTrafficConfig());
        return draft;
    }

    private ExperimentCreateRequest.GroupConfig createGroup(String id, String name, double trafficRatio) {
        ExperimentCreateRequest.GroupConfig groupConfig = new ExperimentCreateRequest.GroupConfig();
        groupConfig.setId(id);
        groupConfig.setName(name);
        groupConfig.setTrafficRatio(trafficRatio);
        groupConfig.setConfig(Map.of());
        return groupConfig;
    }

    private List<GroupConfigFieldDefinition> createDefaultGroupConfigSchema() {
        return List.of(
                createSchemaField(MAIN_TITLE_KEY, "主标题", GroupConfigFieldDefinition.ValueType.STRING,
                        true, "实验组主标题", null),
                createSchemaField(SUBTITLE_KEY, "副标题", GroupConfigFieldDefinition.ValueType.STRING,
                        false, "实验组副标题", null),
                createSchemaField(SHOW_QUALITY_BADGE_KEY, "展示质检标识",
                        GroupConfigFieldDefinition.ValueType.BOOLEAN, false, "是否展示质检背书", null),
                createSchemaField(BADGE_COUNT_KEY, "标签数量", GroupConfigFieldDefinition.ValueType.INTEGER,
                        false, "展示的标签数量", null),
                createSchemaField(CARD_META_KEY, "卡片样式信息", GroupConfigFieldDefinition.ValueType.OBJECT,
                        false, "卡片样式和展示参数", null),
                createSchemaField(HIGHLIGHT_TAGS_KEY, "亮点标签", GroupConfigFieldDefinition.ValueType.JSON,
                        false, "展示在卡片上的标签列表", null)
        );
    }

    private GroupConfigFieldDefinition createSchemaField(String key, String label,
                                                         GroupConfigFieldDefinition.ValueType valueType,
                                                         boolean required, String description,
                                                         Object defaultValue) {
        GroupConfigFieldDefinition field = new GroupConfigFieldDefinition();
        field.setKey(key);
        field.setLabel(label);
        field.setValueType(valueType);
        field.setRequired(required);
        field.setDescription(description);
        field.setDefaultValue(defaultValue);
        return field;
    }

    private ExperimentCreateRequest.TrafficConfigRequest createTrafficConfig() {
        ExperimentCreateRequest.TrafficConfigRequest traffic = new ExperimentCreateRequest.TrafficConfigRequest();
        traffic.setTotalTraffic(DEFAULT_TOTAL_TRAFFIC);
        traffic.setStrategy(DEFAULT_TRAFFIC_STRATEGY);
        traffic.setAllocation(List.of(
                createAllocation(DEFAULT_BASELINE_GROUP_ID, DEFAULT_TRAFFIC_RATIO),
                createAllocation(DEFAULT_VARIANT_GROUP_ID, DEFAULT_TRAFFIC_RATIO)));
        return traffic;
    }

    private ExperimentCreateRequest.GroupAllocationRequest createAllocation(String groupId, double ratio) {
        ExperimentCreateRequest.GroupAllocationRequest allocation = new ExperimentCreateRequest.GroupAllocationRequest();
        allocation.setGroup(groupId);
        allocation.setRatio(ratio);
        return allocation;
    }

    private List<String> mergeRiskFlags(List<String> aiRiskFlags, List<String> guardrailRiskFlags) {
        List<String> mergedRiskFlags = new ArrayList<>();
        appendRiskFlags(mergedRiskFlags, aiRiskFlags);
        appendRiskFlags(mergedRiskFlags, guardrailRiskFlags);
        return mergedRiskFlags;
    }

    private void appendRiskFlags(List<String> mergedRiskFlags, List<String> sourceRiskFlags) {
        if (sourceRiskFlags == null || sourceRiskFlags.isEmpty()) {
            return;
        }
        for (String riskFlag : sourceRiskFlags) {
            if (StringUtils.hasText(riskFlag) && !mergedRiskFlags.contains(riskFlag)) {
                mergedRiskFlags.add(riskFlag);
            }
        }
    }

    private List<AIDiagnosisResponse.RecommendedAction> normalizeDiagnosisActions(
            List<AIDiagnosisResponse.RecommendedAction> aiActions,
            GuardrailStatus guardrailStatus) {
        if (aiActions == null || aiActions.isEmpty()) {
            return List.of(createDefaultDiagnosisAction(guardrailStatus));
        }
        aiActions.forEach(action -> action.setExecutionMode(ACTION_EXECUTION_MODE_MANUAL_ONLY));
        return aiActions;
    }

    private AIDiagnosisResponse.RecommendedAction createDefaultDiagnosisAction(GuardrailStatus guardrailStatus) {
        AIDiagnosisResponse.RecommendedAction action = new AIDiagnosisResponse.RecommendedAction();
        action.setExecutionMode(ACTION_EXECUTION_MODE_MANUAL_ONLY);
        if (GuardrailStatus.BLOCKED.equals(guardrailStatus)) {
            action.setTitle(BLOCKED_ACTION_TITLE);
            action.setAction(BLOCKED_ACTION_DESCRIPTION);
            return action;
        }
        action.setTitle(PASS_ACTION_TITLE);
        action.setAction(PASS_ACTION_DESCRIPTION);
        return action;
    }
}
