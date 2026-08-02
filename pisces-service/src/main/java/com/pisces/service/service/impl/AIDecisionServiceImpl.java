package com.pisces.service.service.impl;

import com.pisces.common.enums.ResponseCode;
import com.pisces.common.model.ExperimentDecisionContext;
import com.pisces.common.model.GroupConfigFieldDefinition;
import com.pisces.common.model.Statistics;
import com.pisces.common.request.AIDesignRequest;
import com.pisces.common.request.ExperimentCreateRequest;
import com.pisces.common.response.AIDesignResponse;
import com.pisces.common.response.AIDecisionEvidenceResponse;
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
import com.pisces.service.exception.BusinessException;
import com.pisces.service.schema.GroupConfigSchemaValidator;
import com.pisces.service.service.AIDecisionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
    private static final String DESIGN_SCHEMA_PLANNING_OPERATION_NAME = "AI实验设计-字段规划";
    private static final String DESIGN_DRAFT_FILLING_OPERATION_NAME = "AI实验设计-草案填充";
    private static final String DIAGNOSIS_OPERATION_NAME = "AI实验诊断";
    private static final String GRADUATION_OPERATION_NAME = "AI毕业决策";
    private static final String DESIGN_SCHEMA_PLANNING_SYSTEM_PROMPT =
            "你是实验平台的AI设计助手，只能基于输入事实输出结构化JSON Schema规划结果，不要补充Markdown。";
    private static final String DESIGN_DRAFT_FILLING_SYSTEM_PROMPT =
            "你是实验平台的AI设计助手，只能基于输入事实输出结构化JSON草案填充结果，不要补充Markdown。";
    private static final String DIAGNOSIS_SYSTEM_PROMPT =
            "你是实验平台的AI诊断助手，只能基于给定实验事实输出结构化JSON，不要输出Markdown或执行自动化动作。";
    private static final String GRADUATION_SYSTEM_PROMPT =
            "你是实验平台的AI毕业决策助手，只能基于给定实验事实输出结构化JSON，不要输出Markdown或自动执行实验变更。";
    private static final String DEFAULT_GRADUATION_DECISION = "CONTINUE";
    private static final String DEFAULT_BASELINE_GROUP_ID = "control";
    private static final String DEFAULT_BASELINE_GROUP_NAME = "对照组";
    private static final String DEFAULT_VARIANT_GROUP_ID = "variant_a";
    private static final String DEFAULT_VARIANT_GROUP_NAME = "实验组A";
    private static final String DEFAULT_GROUP_NAME_PREFIX = "实验组";
    private static final String MAIN_TITLE_KEY = "mainTitle";
    private static final String SUBTITLE_KEY = "subtitle";
    private static final String SHOW_QUALITY_BADGE_KEY = "showQualityBadge";
    private static final String BADGE_COUNT_KEY = "badgeCount";
    private static final String CARD_META_KEY = "cardMeta";
    private static final String HIGHLIGHT_TAGS_KEY = "highlightTags";
    private static final String FIELD_ROLE_BASELINE_STABLE = "BASELINE_STABLE";
    private static final String FIELD_ROLE_EXPERIMENT_VARIABLE = "EXPERIMENT_VARIABLE";
    private static final String FIELD_ROLE_AUXILIARY_CONTEXT = "AUXILIARY_CONTEXT";
    private static final int MIN_AI_GENERATED_GROUP_COUNT = 2;
    private static final int MIN_AI_GENERATED_NEW_SCHEMA_FIELD_COUNT = 5;
    private static final String SCHEMA_PLANNING_KEY = "schemaPlanning";
    private static final String GROUP_CONFIG_SCHEMA_KEY = "groupConfigSchema";
    private static final String FIELD_ROLES_KEY = "fieldRoles";
    private static final String DRAFT_GENERATION_KEY = "draftGeneration";
    private static final String CONTROL_CONFIG_KEY = "controlConfig";
    private static final String VARIANT_CONFIGS_KEY = "variantConfigs";
    private static final String FILLED_GROUPS_KEY = "filledGroups";
    private static final double DEFAULT_TOTAL_TRAFFIC = 1.0D;
    private static final String DESIGN_NAME_SUFFIX = "实验";
    private static final String ACTION_EXECUTION_MODE_MANUAL_ONLY = "MANUAL_ONLY";
    private static final String BLOCKED_ACTION_TITLE = "先修复数据质量问题";
    private static final String BLOCKED_ACTION_DESCRIPTION = "优先处理 SRM、样本量或其他阻断问题，再重新评估实验";
    private static final String PASS_ACTION_TITLE = "继续观察实验";
    private static final String PASS_ACTION_DESCRIPTION = "保持当前实验运行，结合业务节奏复核关键指标变化";
    private static final String AI_UNAVAILABLE_RISK_FLAG = "AI_UNAVAILABLE";
    private static final String DESIGN_DRAFT_INCOMPLETE_RISK_FLAG = "DESIGN_DRAFT_INCOMPLETE";
    private static final double FALLBACK_CONFIDENCE_SCORE = 0.0D;
    private static final String DESIGN_FALLBACK_SUMMARY = "AI实验设计暂不可用，已切换为平台保守草案";
    private static final String DESIGN_INCOMPLETE_SUMMARY = "AI实验设计结果不完整，已阻断草案发布";
    private static final String DIAGNOSIS_FALLBACK_SUMMARY = "AI诊断结果暂不可用，已切换为保守建议";
    private static final String GRADUATION_FALLBACK_SUMMARY = "AI毕业决策暂不可用，已切换为保守结论";
    private static final String DESIGN_BLOCKED_STATUS = "BLOCKED";
    private static final String IDENTIFIER_FIELD_SUFFIX = "Id";
    private static final String GENERATED_GROUP_ROLE_CONTROL = "CONTROL";
    private static final String GENERATED_GROUP_ROLE_VARIANT = "VARIANT";
    private static final String GENERATED_GROUP_ID_KEY = "groupId";
    private static final String GENERATED_GROUP_ROLE_KEY = "role";
    private static final String GENERATED_GROUP_NAME_KEY = "name";
    private static final String GENERATED_GROUP_LABEL_KEY = "label";

    private final ExperimentDecisionContextBuilder experimentDecisionContextBuilder;
    private final PromptTemplateBuilder promptTemplateBuilder;
    private final AIDesignContextResolver aiDesignContextResolver;
    private final AIDecisionJsonParser aiDecisionJsonParser;
    private final DecisionGuardrailEvaluator decisionGuardrailEvaluator;
    private final TongYiTextGenerationClient tongYiTextGenerationClient;
    private final GroupConfigSchemaValidator groupConfigSchemaValidator;

    @Override
    public AIDesignResponse designExperiment(AIDesignRequest request) {
        AIDesignPlanningContext planningContext = aiDesignContextResolver.resolve(request);
        String schemaPlanningPrompt = promptTemplateBuilder.buildDesignSchemaPlanningPrompt(request, planningContext);
        if (!StringUtils.hasText(schemaPlanningPrompt)) {
            throw new IllegalStateException("AI设计Schema Planning Prompt不能为空");
        }
        AIDesignResponse schemaPlanningResponse = generateDesignStageResponse(
                DESIGN_SCHEMA_PLANNING_SYSTEM_PROMPT,
                schemaPlanningPrompt,
                DESIGN_SCHEMA_PLANNING_OPERATION_NAME);
        if (schemaPlanningResponse == null) {
            throw new BusinessException(ResponseCode.OPERATION_FAILED, DESIGN_FALLBACK_SUMMARY);
        }

        List<GroupConfigFieldDefinition> effectiveSchema;
        Map<String, String> fieldRoles;
        try {
            effectiveSchema = resolveEffectiveSchema(planningContext, schemaPlanningResponse);
            validateSchemaPlanningConstraints(planningContext, effectiveSchema);
            fieldRoles = resolveFieldRoles(planningContext, schemaPlanningResponse, effectiveSchema);
        } catch (BusinessException exception) {
            log.warn("AI字段规划不符合平台约束", exception);
            throw exception;
        }
        String draftFillingPrompt = promptTemplateBuilder.buildDesignDraftFillingPrompt(
                request, planningContext, effectiveSchema, fieldRoles);
        if (!StringUtils.hasText(draftFillingPrompt)) {
            throw new IllegalStateException("AI设计Draft Filling Prompt不能为空");
        }
        AIDesignResponse draftFillingResponse = generateDesignStageResponse(
                DESIGN_DRAFT_FILLING_SYSTEM_PROMPT,
                draftFillingPrompt,
                DESIGN_DRAFT_FILLING_OPERATION_NAME);
        if (draftFillingResponse == null) {
            throw new BusinessException(ResponseCode.OPERATION_FAILED, DESIGN_FALLBACK_SUMMARY);
        }

        try {
            ExperimentCreateRequest experimentDraft = createExperimentDraft(
                    request, planningContext, effectiveSchema, fieldRoles, draftFillingResponse.getDraftGeneration());
            return mergeDesignResponses(schemaPlanningResponse, draftFillingResponse, experimentDraft);
        } catch (RuntimeException exception) {
            log.warn("AI实验设计草案填充失败，直接返回错误", exception);
            throw wrapDesignFailure(exception);
        }
    }

    @Override
    public AIDiagnosisResponse diagnoseExperiment(String experimentId) {
        ExperimentDecisionContext context = experimentDecisionContextBuilder.buildForExperiment(experimentId);
        String prompt = promptTemplateBuilder.buildDiagnosisPrompt(context);
        if (!StringUtils.hasText(prompt)) {
            throw new IllegalStateException("AI诊断Prompt不能为空");
        }
        GuardrailStatus guardrailStatus = decisionGuardrailEvaluator.evaluateDiagnosis(context);
        List<String> guardrailRiskFlags = decisionGuardrailEvaluator.collectRiskFlags(context);
        AIDiagnosisResponse response = generateDiagnosisResponse(prompt, guardrailStatus, guardrailRiskFlags);
        response.setDecisionType(DecisionType.DIAGNOSIS.getCode());
        response.setGuardrailStatus(guardrailStatus.getCode());
        response.setRiskFlags(mergeRiskFlags(response.getRiskFlags(), guardrailRiskFlags,
                containsAiUnavailableRisk(response.getRiskFlags())));
        response.setRecommendedActions(normalizeDiagnosisActions(response.getRecommendedActions(), guardrailStatus));
        response.setEvidence(buildDecisionEvidence(context));
        return response;
    }

    @Override
    public AIGraduationDecisionResponse decideGraduation(String experimentId) {
        ExperimentDecisionContext context = experimentDecisionContextBuilder.buildForExperiment(experimentId);
        return decideGraduation(context);
    }

    @Override
    public AIGraduationDecisionResponse decideGraduation(ExperimentDecisionContext context) {
        String prompt = promptTemplateBuilder.buildGraduationPrompt(context);
        if (!StringUtils.hasText(prompt)) {
            throw new IllegalStateException("AI毕业决策Prompt不能为空");
        }
        GuardrailStatus guardrailStatus = decisionGuardrailEvaluator.evaluateGraduation(context);
        List<String> guardrailRiskFlags = decisionGuardrailEvaluator.collectRiskFlags(context);
        AIGraduationDecisionResponse response = generateGraduationResponse(prompt, guardrailStatus, guardrailRiskFlags);
        response.setDecisionType(DecisionType.GRADUATION.getCode());
        response.setGuardrailStatus(guardrailStatus.getCode());
        response.setRiskFlags(mergeRiskFlags(response.getRiskFlags(), guardrailRiskFlags,
                containsAiUnavailableRisk(response.getRiskFlags())));
        if (GuardrailStatus.BLOCKED.equals(guardrailStatus)) {
            response.setDecision(DEFAULT_GRADUATION_DECISION);
        }
        response.setEvidence(buildDecisionEvidence(context));
        log.info("AI毕业决策完成: experimentId={}, decision={}, guardrailStatus={}, riskFlags={}",
                context == null ? null : context.getExperimentId(),
                response.getDecision(), response.getGuardrailStatus(), response.getRiskFlags());
        return response;
    }

    private AIDesignResponse generateDesignStageResponse(String systemPrompt, String prompt, String operationName) {
        try {
            return aiDecisionJsonParser.parseDesign(
                    tongYiTextGenerationClient.generateText(systemPrompt, prompt, operationName));
        } catch (RuntimeException exception) {
            log.warn("AI实验设计阶段执行失败: operation={}", operationName, exception);
            return null;
        }
    }

    private AIDiagnosisResponse generateDiagnosisResponse(String prompt,
                                                          GuardrailStatus guardrailStatus,
                                                          List<String> guardrailRiskFlags) {
        try {
            return aiDecisionJsonParser.parseDiagnosis(
                    tongYiTextGenerationClient.generateText(DIAGNOSIS_SYSTEM_PROMPT, prompt, DIAGNOSIS_OPERATION_NAME));
        } catch (RuntimeException exception) {
            log.warn("AI诊断结果生成失败，降级为保守响应: guardrailStatus={}, riskFlags={}",
                    guardrailStatus, guardrailRiskFlags, exception);
            return createDiagnosisFallbackResponse(guardrailStatus);
        }
    }

    private AIGraduationDecisionResponse generateGraduationResponse(String prompt,
                                                                   GuardrailStatus guardrailStatus,
                                                                   List<String> guardrailRiskFlags) {
        try {
            return aiDecisionJsonParser.parseGraduation(
                    tongYiTextGenerationClient.generateText(GRADUATION_SYSTEM_PROMPT, prompt, GRADUATION_OPERATION_NAME));
        } catch (RuntimeException exception) {
            log.warn("AI毕业决策生成失败，降级为保守响应: guardrailStatus={}, riskFlags={}",
                    guardrailStatus, guardrailRiskFlags, exception);
            return createGraduationFallbackResponse(guardrailStatus);
        }
    }

    private AIDesignResponse mergeDesignResponses(AIDesignResponse schemaPlanningResponse,
                                                  AIDesignResponse draftFillingResponse,
                                                  ExperimentCreateRequest experimentDraft) {
        AIDesignResponse response = new AIDesignResponse();
        response.setDecisionType(DecisionType.DESIGN.getCode());
        response.setSummary(firstNonBlank(draftFillingResponse == null ? null : draftFillingResponse.getSummary(),
                schemaPlanningResponse == null ? null : schemaPlanningResponse.getSummary(),
                "建议开展实验"));
        response.setConfidence(resolveConfidence(schemaPlanningResponse, draftFillingResponse));
        response.setRiskFlags(mergeRiskFlags(
                schemaPlanningResponse == null ? null : schemaPlanningResponse.getRiskFlags(),
                draftFillingResponse == null ? null : draftFillingResponse.getRiskFlags(),
                false));
        response.setGuardrailStatus(resolveDesignGuardrailStatus(schemaPlanningResponse, draftFillingResponse));
        response.setSchemaPlanning(schemaPlanningResponse == null ? Map.of() : safeMap(schemaPlanningResponse.getSchemaPlanning()));
        response.setDraftGeneration(draftFillingResponse == null ? Map.of() : safeMap(draftFillingResponse.getDraftGeneration()));
        response.setExperimentDraft(experimentDraft);
        return response;
    }

    private Double resolveConfidence(AIDesignResponse schemaPlanningResponse, AIDesignResponse draftFillingResponse) {
        if (draftFillingResponse != null && draftFillingResponse.getConfidence() != null) {
            return draftFillingResponse.getConfidence();
        }
        if (schemaPlanningResponse != null && schemaPlanningResponse.getConfidence() != null) {
            return schemaPlanningResponse.getConfidence();
        }
        return FALLBACK_CONFIDENCE_SCORE;
    }

    private String resolveDesignGuardrailStatus(AIDesignResponse schemaPlanningResponse,
                                                AIDesignResponse draftFillingResponse) {
        String schemaPlanningStatus = schemaPlanningResponse == null ? null : schemaPlanningResponse.getGuardrailStatus();
        if (DESIGN_BLOCKED_STATUS.equals(schemaPlanningStatus)) {
            return DESIGN_BLOCKED_STATUS;
        }
        String draftFillingStatus = draftFillingResponse == null ? null : draftFillingResponse.getGuardrailStatus();
        if (DESIGN_BLOCKED_STATUS.equals(draftFillingStatus)) {
            return DESIGN_BLOCKED_STATUS;
        }
        return firstNonBlank(draftFillingStatus, schemaPlanningStatus, GuardrailStatus.PASS.getCode());
    }

    private ExperimentCreateRequest createExperimentDraft(AIDesignRequest request,
                                                          AIDesignPlanningContext planningContext,
                                                          List<GroupConfigFieldDefinition> schema,
                                                          Map<String, String> fieldRoles,
                                                          Map<String, Object> draftGeneration) {
        List<GeneratedGroup> generatedGroups = resolveGeneratedGroups(planningContext, draftGeneration);
        List<String> generatedGroupIds = extractGroupIds(generatedGroups);
        ExperimentCreateRequest draft = createExperimentDraftSkeleton(request, planningContext, schema, generatedGroupIds);
        Map<String, Map<String, Object>> groupConfigs = buildGroupConfigs(
                planningContext, schema, fieldRoles, draftGeneration, generatedGroups);
        List<ExperimentCreateRequest.GroupConfig> groups = new ArrayList<>();
        double trafficRatio = DEFAULT_TOTAL_TRAFFIC / generatedGroupIds.size();
        int variantIndex = 1;
        for (GeneratedGroup generatedGroup : generatedGroups) {
            String groupId = generatedGroup.groupId();
            String groupName = resolveGroupName(generatedGroup, variantIndex);
            if (!generatedGroup.control()) {
                variantIndex++;
            }
            groups.add(createGroup(groupId, groupName, trafficRatio, groupConfigs.get(groupId)));
        }
        draft.setGroups(groups);
        return draft;
    }

    private ExperimentCreateRequest createExperimentDraftSkeleton(AIDesignRequest request,
                                                                  AIDesignPlanningContext planningContext,
                                                                  List<GroupConfigFieldDefinition> schema) {
        List<String> groupIds = resolveSkeletonGroupIds(planningContext);
        return createExperimentDraftSkeleton(request, planningContext, schema, groupIds);
    }

    private ExperimentCreateRequest createExperimentDraftSkeleton(AIDesignRequest request,
                                                                  AIDesignPlanningContext planningContext,
                                                                  List<GroupConfigFieldDefinition> schema,
                                                                  List<String> groupIds) {
        ExperimentCreateRequest draft = new ExperimentCreateRequest();
        String businessScenario = request == null ? null : request.getBusinessScenario();
        String targetMetric = request == null ? null : request.getTargetMetric();
        draft.setName(defaultValue(businessScenario, DEFAULT_DESIGN_SCENARIO) + DESIGN_NAME_SUFFIX);
        draft.setDescription("目标指标: " + defaultValue(targetMetric, "待补充"));
        draft.setGroupConfigSchema(schema == null || schema.isEmpty() ? resolveFallbackSchema(planningContext) : schema);
        draft.setGroups(createEmptyGroups(groupIds));
        String trafficStrategy = planningContext == null ? null : planningContext.getTrafficStrategy();
        draft.setTraffic(createTrafficConfig(groupIds, defaultValue(trafficStrategy, "HASH")));
        return draft;
    }

    private Map<String, Map<String, Object>> buildGroupConfigs(AIDesignPlanningContext planningContext,
                                                               List<GroupConfigFieldDefinition> schema,
                                                               Map<String, String> fieldRoles,
                                                               Map<String, Object> draftGeneration,
                                                               List<GeneratedGroup> generatedGroups) {
        Map<String, Object> normalizedDraftGeneration = safeMap(draftGeneration);
        Map<String, Object> baselineConfig = planningContext == null ? Map.of() : planningContext.getBaselineConfig();
        String controlGroupId = resolveControlGroupId(generatedGroups);
        Map<String, Object> controlConfigCandidate = mergeControlConfig(
                baselineConfig, castObjectMap(normalizedDraftGeneration.get(CONTROL_CONFIG_KEY)));
        Map<String, Object> normalizedControlConfig = groupConfigSchemaValidator.normalizeCompleteGroupConfig(
                schema, controlConfigCandidate, controlGroupId);

        Map<String, Map<String, Object>> groupConfigs = new LinkedHashMap<>();
        groupConfigs.put(controlGroupId, normalizedControlConfig);
        Map<String, Map<String, Object>> variantConfigs = castNestedObjectMap(normalizedDraftGeneration.get(VARIANT_CONFIGS_KEY));
        for (GeneratedGroup generatedGroup : generatedGroups) {
            if (generatedGroup.control()) {
                continue;
            }
            String groupId = generatedGroup.groupId();
            Map<String, Object> variantCandidate = mergeVariantConfig(
                    normalizedControlConfig, variantConfigs.get(groupId), schema, fieldRoles);
            groupConfigs.put(groupId,
                    groupConfigSchemaValidator.normalizeCompleteGroupConfig(schema, variantCandidate, groupId));
        }
        return groupConfigs;
    }

    private Map<String, Object> mergeControlConfig(Map<String, Object> baselineConfig,
                                                   Map<String, Object> aiControlConfig) {
        Map<String, Object> mergedConfig = new LinkedHashMap<>();
        if (aiControlConfig != null) {
            mergedConfig.putAll(aiControlConfig);
        }
        if (baselineConfig != null && !baselineConfig.isEmpty()) {
            for (Map.Entry<String, Object> entry : baselineConfig.entrySet()) {
                mergedConfig.put(entry.getKey(), entry.getValue());
            }
        }
        return mergedConfig;
    }

    private Map<String, Object> mergeVariantConfig(Map<String, Object> controlConfig,
                                                   Map<String, Object> variantConfig,
                                                   List<GroupConfigFieldDefinition> schema,
                                                   Map<String, String> fieldRoles) {
        Map<String, Object> mergedConfig = new LinkedHashMap<>();
        if (variantConfig != null) {
            mergedConfig.putAll(variantConfig);
        }
        for (GroupConfigFieldDefinition fieldDefinition : schema) {
            if (fieldDefinition == null || !StringUtils.hasText(fieldDefinition.getKey())) {
                continue;
            }
            String fieldRole = fieldRoles.get(fieldDefinition.getKey());
            if (FIELD_ROLE_BASELINE_STABLE.equals(fieldRole)
                    && !mergedConfig.containsKey(fieldDefinition.getKey())
                    && controlConfig.containsKey(fieldDefinition.getKey())) {
                mergedConfig.put(fieldDefinition.getKey(), controlConfig.get(fieldDefinition.getKey()));
            }
        }
        return mergedConfig;
    }

    private List<GroupConfigFieldDefinition> resolveEffectiveSchema(AIDesignPlanningContext planningContext,
                                                                    AIDesignResponse schemaPlanningResponse) {
        Map<String, GroupConfigFieldDefinition> schemaFieldMap = new LinkedHashMap<>();
        appendSchemaCandidates(schemaFieldMap, extractSchemaPlanningFields(schemaPlanningResponse), planningContext);
        appendSchemaCandidates(schemaFieldMap, planningContext.getExistingSchema(), planningContext);
        appendSchemaCandidates(schemaFieldMap, inferSchemaFromBaseline(planningContext.getBaselineConfig()), planningContext);
        if (schemaFieldMap.isEmpty()) {
            appendSchemaCandidates(schemaFieldMap, createDefaultGroupConfigSchema(), planningContext);
        }
        return normalizeSchemaSafely(new ArrayList<>(schemaFieldMap.values()));
    }

    private void validateSchemaPlanningConstraints(AIDesignPlanningContext planningContext,
                                                   List<GroupConfigFieldDefinition> effectiveSchema) {
        validateMinimumNewSchemaFields(planningContext, effectiveSchema);
        validateIdentifierLikeSchemaFields(planningContext, effectiveSchema);
    }

    private void validateMinimumNewSchemaFields(AIDesignPlanningContext planningContext,
                                                List<GroupConfigFieldDefinition> effectiveSchema) {
        int newSchemaFieldCount = 0;
        List<String> existingFieldKeys = collectExistingFieldKeys(planningContext);
        for (GroupConfigFieldDefinition fieldDefinition : effectiveSchema) {
            if (fieldDefinition == null || !StringUtils.hasText(fieldDefinition.getKey())) {
                continue;
            }
            if (!existingFieldKeys.contains(fieldDefinition.getKey())) {
                newSchemaFieldCount++;
            }
        }
        if (newSchemaFieldCount < MIN_AI_GENERATED_NEW_SCHEMA_FIELD_COUNT) {
            throw new BusinessException(ResponseCode.VALIDATION_ERROR,
                    "AI生成阶段新增字段数不能少于 " + MIN_AI_GENERATED_NEW_SCHEMA_FIELD_COUNT + " 个");
        }
    }

    private void validateIdentifierLikeSchemaFields(AIDesignPlanningContext planningContext,
                                                    List<GroupConfigFieldDefinition> effectiveSchema) {
        List<String> existingFieldKeys = collectExistingFieldKeys(planningContext);
        for (GroupConfigFieldDefinition fieldDefinition : effectiveSchema) {
            if (fieldDefinition == null || !StringUtils.hasText(fieldDefinition.getKey())) {
                continue;
            }
            String fieldKey = fieldDefinition.getKey();
            if (existingFieldKeys.contains(fieldKey)) {
                continue;
            }
            if (isIdentifierLikeField(fieldKey)) {
                throw new BusinessException(ResponseCode.VALIDATION_ERROR,
                        "AI字段规划不允许输出标识/引用型字段: " + fieldKey);
            }
        }
    }

    private List<String> collectExistingFieldKeys(AIDesignPlanningContext planningContext) {
        List<String> fieldKeys = new ArrayList<>();
        if (planningContext == null) {
            return fieldKeys;
        }
        if (planningContext.getExistingSchema() != null) {
            for (GroupConfigFieldDefinition fieldDefinition : planningContext.getExistingSchema()) {
                if (fieldDefinition != null
                        && StringUtils.hasText(fieldDefinition.getKey())
                        && !fieldKeys.contains(fieldDefinition.getKey())) {
                    fieldKeys.add(fieldDefinition.getKey());
                }
            }
        }
        if (planningContext.getBaselineConfig() != null) {
            for (String baselineKey : planningContext.getBaselineConfig().keySet()) {
                if (StringUtils.hasText(baselineKey) && !fieldKeys.contains(baselineKey)) {
                    fieldKeys.add(baselineKey);
                }
            }
        }
        return fieldKeys;
    }

    private boolean isIdentifierLikeField(String fieldKey) {
        return StringUtils.hasText(fieldKey) && fieldKey.endsWith(IDENTIFIER_FIELD_SUFFIX);
    }

    private List<GeneratedGroup> resolveGeneratedGroups(AIDesignPlanningContext planningContext,
                                                        Map<String, Object> draftGeneration) {
        List<GeneratedGroup> filledGroups = extractFilledGroups(draftGeneration);
        if (filledGroups.size() >= MIN_AI_GENERATED_GROUP_COUNT) {
            return orderGeneratedGroups(filledGroups);
        }
        List<GeneratedGroup> derivedGroups = deriveGroupsFromVariantConfigs(draftGeneration);
        if (derivedGroups.size() >= MIN_AI_GENERATED_GROUP_COUNT) {
            return derivedGroups;
        }
        List<String> plannedGroupIds = planningContext == null ? List.of() : planningContext.getDraftGroupIds();
        if (plannedGroupIds.size() >= MIN_AI_GENERATED_GROUP_COUNT) {
            return createGeneratedGroupsFromIds(plannedGroupIds);
        }
        throw new BusinessException(ResponseCode.VALIDATION_ERROR,
                "AI生成阶段实验组数量不能少于 " + MIN_AI_GENERATED_GROUP_COUNT + " 个");
    }

    private List<GeneratedGroup> extractFilledGroups(Map<String, Object> draftGeneration) {
        Map<String, Object> normalizedDraftGeneration = safeMap(draftGeneration);
        Object filledGroupsPayload = normalizedDraftGeneration.get(FILLED_GROUPS_KEY);
        if (!(filledGroupsPayload instanceof List<?> filledGroupList)) {
            return List.of();
        }
        List<GeneratedGroup> filledGroups = new ArrayList<>();
        for (Object groupItem : filledGroupList) {
            GeneratedGroup generatedGroup = parseGeneratedGroup(groupItem);
            if (generatedGroup != null && filledGroups.stream()
                    .noneMatch(existingGroup -> existingGroup.groupId().equals(generatedGroup.groupId()))) {
                filledGroups.add(generatedGroup);
            }
        }
        return filledGroups;
    }

    private List<GeneratedGroup> deriveGroupsFromVariantConfigs(Map<String, Object> draftGeneration) {
        Map<String, Map<String, Object>> variantConfigs = castNestedObjectMap(safeMap(draftGeneration).get(VARIANT_CONFIGS_KEY));
        if (variantConfigs.isEmpty()) {
            return List.of();
        }
        List<GeneratedGroup> groups = new ArrayList<>();
        groups.add(new GeneratedGroup(DEFAULT_BASELINE_GROUP_ID, true, null));
        for (String groupId : variantConfigs.keySet()) {
            if (StringUtils.hasText(groupId)
                    && !DEFAULT_BASELINE_GROUP_ID.equals(groupId)
                    && groups.stream().noneMatch(group -> group.groupId().equals(groupId))) {
                groups.add(new GeneratedGroup(groupId, false, null));
            }
        }
        return groups;
    }

    private List<GeneratedGroup> createGeneratedGroupsFromIds(List<String> groupIds) {
        List<GeneratedGroup> generatedGroups = new ArrayList<>();
        if (groupIds == null || groupIds.isEmpty()) {
            return generatedGroups;
        }
        for (int i = 0; i < groupIds.size(); i++) {
            String groupId = groupIds.get(i);
            if (!StringUtils.hasText(groupId)) {
                continue;
            }
            boolean control = i == 0 || DEFAULT_BASELINE_GROUP_ID.equals(groupId);
            generatedGroups.add(new GeneratedGroup(groupId, control, null));
        }
        return orderGeneratedGroups(generatedGroups);
    }

    private List<String> extractGroupIds(List<GeneratedGroup> generatedGroups) {
        List<String> groupIds = new ArrayList<>();
        if (generatedGroups == null || generatedGroups.isEmpty()) {
            return groupIds;
        }
        for (GeneratedGroup generatedGroup : generatedGroups) {
            groupIds.add(generatedGroup.groupId());
        }
        return groupIds;
    }

    private GeneratedGroup parseGeneratedGroup(Object groupItem) {
        if (groupItem instanceof Map<?, ?>) {
            Map<String, Object> groupPayload = castObjectMap(groupItem);
            String groupId = asText(groupPayload.get(GENERATED_GROUP_ID_KEY));
            if (!StringUtils.hasText(groupId)) {
                return null;
            }
            boolean control = GENERATED_GROUP_ROLE_CONTROL.equalsIgnoreCase(asText(groupPayload.get(GENERATED_GROUP_ROLE_KEY)));
            String groupName = firstNonBlank(
                    asText(groupPayload.get(GENERATED_GROUP_NAME_KEY)),
                    asText(groupPayload.get(GENERATED_GROUP_LABEL_KEY)),
                    null);
            return new GeneratedGroup(groupId, control, groupName);
        }
        String groupId = asText(groupItem);
        if (!StringUtils.hasText(groupId)) {
            return null;
        }
        return new GeneratedGroup(groupId, DEFAULT_BASELINE_GROUP_ID.equals(groupId), null);
    }

    private List<GeneratedGroup> orderGeneratedGroups(List<GeneratedGroup> generatedGroups) {
        if (generatedGroups == null || generatedGroups.isEmpty()) {
            return List.of();
        }
        List<GeneratedGroup> orderedGroups = new ArrayList<>();
        for (GeneratedGroup generatedGroup : generatedGroups) {
            if (generatedGroup.control()) {
                orderedGroups.add(generatedGroup);
                break;
            }
        }
        for (GeneratedGroup generatedGroup : generatedGroups) {
            if (!orderedGroups.contains(generatedGroup)) {
                orderedGroups.add(generatedGroup);
            }
        }
        return orderedGroups;
    }

    private String resolveControlGroupId(List<GeneratedGroup> generatedGroups) {
        if (generatedGroups == null || generatedGroups.isEmpty()) {
            return DEFAULT_BASELINE_GROUP_ID;
        }
        for (GeneratedGroup generatedGroup : generatedGroups) {
            if (generatedGroup.control()) {
                return generatedGroup.groupId();
            }
        }
        return generatedGroups.get(0).groupId();
    }

    private List<String> resolveSkeletonGroupIds(AIDesignPlanningContext planningContext) {
        List<String> plannedGroupIds = planningContext == null ? List.of() : planningContext.getDraftGroupIds();
        if (plannedGroupIds.size() >= MIN_AI_GENERATED_GROUP_COUNT) {
            return plannedGroupIds;
        }
        return List.of(DEFAULT_BASELINE_GROUP_ID, DEFAULT_VARIANT_GROUP_ID);
    }

    private BusinessException wrapDesignFailure(RuntimeException exception) {
        if (exception instanceof BusinessException businessException) {
            return businessException;
        }
        return new BusinessException(ResponseCode.OPERATION_FAILED,
                firstNonBlank(exception == null ? null : exception.getMessage(), DESIGN_INCOMPLETE_SUMMARY, DESIGN_INCOMPLETE_SUMMARY));
    }

    private void appendSchemaCandidates(Map<String, GroupConfigFieldDefinition> schemaFieldMap,
                                        List<GroupConfigFieldDefinition> candidates,
                                        AIDesignPlanningContext planningContext) {
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        for (GroupConfigFieldDefinition candidate : candidates) {
            if (candidate == null || !StringUtils.hasText(candidate.getKey())) {
                continue;
            }
            String fieldKey = candidate.getKey().trim();
            if (planningContext.getDisabledSchemaKeys().contains(fieldKey) || schemaFieldMap.containsKey(fieldKey)) {
                continue;
            }
            if (candidate.getValueType() == null) {
                continue;
            }
            schemaFieldMap.put(fieldKey, sanitizeSchemaField(candidate));
        }
    }

    private List<GroupConfigFieldDefinition> extractSchemaPlanningFields(AIDesignResponse schemaPlanningResponse) {
        Map<String, Object> schemaPlanning = schemaPlanningResponse == null ? Map.of() : safeMap(schemaPlanningResponse.getSchemaPlanning());
        Object schemaPayload = schemaPlanning.get(GROUP_CONFIG_SCHEMA_KEY);
        if (!(schemaPayload instanceof List<?> schemaList)) {
            return List.of();
        }
        List<GroupConfigFieldDefinition> schemaFields = new ArrayList<>();
        for (Object schemaItem : schemaList) {
            Map<String, Object> fieldPayload = castObjectMap(schemaItem);
            if (fieldPayload.isEmpty()) {
                continue;
            }
            GroupConfigFieldDefinition fieldDefinition = new GroupConfigFieldDefinition();
            fieldDefinition.setKey(asText(fieldPayload.get("key")));
            fieldDefinition.setLabel(firstNonBlank(asText(fieldPayload.get("label")), asText(fieldPayload.get("key")), null));
            fieldDefinition.setValueType(GroupConfigFieldDefinition.ValueType.of(asText(fieldPayload.get("valueType"))));
            fieldDefinition.setRequired(asBoolean(fieldPayload.get("required")));
            fieldDefinition.setDescription(asText(fieldPayload.get("description")));
            fieldDefinition.setDefaultValue(fieldPayload.get("defaultValue"));
            schemaFields.add(fieldDefinition);
        }
        return schemaFields;
    }

    private Map<String, String> resolveFieldRoles(AIDesignPlanningContext planningContext,
                                                  AIDesignResponse schemaPlanningResponse,
                                                  List<GroupConfigFieldDefinition> effectiveSchema) {
        Map<String, String> fieldRoles = new LinkedHashMap<>();
        Map<String, Object> schemaPlanning = schemaPlanningResponse == null ? Map.of() : safeMap(schemaPlanningResponse.getSchemaPlanning());
        fieldRoles.putAll(castStringMap(schemaPlanning.get(FIELD_ROLES_KEY)));
        Object schemaPayload = schemaPlanning.get(GROUP_CONFIG_SCHEMA_KEY);
        if (schemaPayload instanceof List<?> schemaList) {
            for (Object schemaItem : schemaList) {
                Map<String, Object> fieldPayload = castObjectMap(schemaItem);
                String fieldKey = asText(fieldPayload.get("key"));
                String fieldRole = normalizeFieldRole(asText(fieldPayload.get("fieldRole")));
                if (StringUtils.hasText(fieldKey) && StringUtils.hasText(fieldRole) && !fieldRoles.containsKey(fieldKey)) {
                    fieldRoles.put(fieldKey, fieldRole);
                }
            }
        }
        for (GroupConfigFieldDefinition fieldDefinition : effectiveSchema) {
            if (fieldDefinition == null || !StringUtils.hasText(fieldDefinition.getKey())) {
                continue;
            }
            fieldRoles.putIfAbsent(fieldDefinition.getKey(),
                    planningContext.getBaselineConfig().containsKey(fieldDefinition.getKey())
                            ? FIELD_ROLE_BASELINE_STABLE : FIELD_ROLE_EXPERIMENT_VARIABLE);
        }
        return fieldRoles;
    }

    private String normalizeFieldRole(String fieldRole) {
        if (!StringUtils.hasText(fieldRole)) {
            return null;
        }
        String normalizedFieldRole = fieldRole.trim();
        if (FIELD_ROLE_BASELINE_STABLE.equals(normalizedFieldRole)
                || FIELD_ROLE_EXPERIMENT_VARIABLE.equals(normalizedFieldRole)
                || FIELD_ROLE_AUXILIARY_CONTEXT.equals(normalizedFieldRole)) {
            return normalizedFieldRole;
        }
        return null;
    }

    private List<GroupConfigFieldDefinition> inferSchemaFromBaseline(Map<String, Object> baselineConfig) {
        if (baselineConfig == null || baselineConfig.isEmpty()) {
            return List.of();
        }
        List<GroupConfigFieldDefinition> schemaFields = new ArrayList<>();
        for (Map.Entry<String, Object> entry : baselineConfig.entrySet()) {
            GroupConfigFieldDefinition.ValueType valueType = inferValueType(entry.getValue());
            if (valueType == null) {
                continue;
            }
            GroupConfigFieldDefinition fieldDefinition = new GroupConfigFieldDefinition();
            fieldDefinition.setKey(entry.getKey());
            fieldDefinition.setLabel(entry.getKey());
            fieldDefinition.setValueType(valueType);
            fieldDefinition.setRequired(true);
            fieldDefinition.setDescription("基线配置推断字段");
            fieldDefinition.setDefaultValue(entry.getValue());
            schemaFields.add(fieldDefinition);
        }
        return schemaFields;
    }

    private GroupConfigFieldDefinition sanitizeSchemaField(GroupConfigFieldDefinition fieldDefinition) {
        GroupConfigFieldDefinition normalizedField = new GroupConfigFieldDefinition();
        normalizedField.setKey(fieldDefinition.getKey().trim());
        normalizedField.setLabel(defaultValue(fieldDefinition.getLabel(), fieldDefinition.getKey().trim()));
        normalizedField.setValueType(fieldDefinition.getValueType());
        normalizedField.setRequired(Boolean.TRUE.equals(fieldDefinition.getRequired()));
        normalizedField.setDescription(fieldDefinition.getDescription());
        normalizedField.setDefaultValue(fieldDefinition.getDefaultValue());
        return normalizedField;
    }

    private GroupConfigFieldDefinition.ValueType inferValueType(Object value) {
        if (value instanceof String) {
            return GroupConfigFieldDefinition.ValueType.STRING;
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short) {
            return GroupConfigFieldDefinition.ValueType.INTEGER;
        }
        if (value instanceof Boolean) {
            return GroupConfigFieldDefinition.ValueType.BOOLEAN;
        }
        if (value instanceof Map<?, ?>) {
            return GroupConfigFieldDefinition.ValueType.OBJECT;
        }
        if (value instanceof List<?> || value instanceof Number) {
            return GroupConfigFieldDefinition.ValueType.JSON;
        }
        return null;
    }

    private List<GroupConfigFieldDefinition> resolveFallbackSchema(AIDesignPlanningContext planningContext) {
        Map<String, GroupConfigFieldDefinition> schemaFieldMap = new LinkedHashMap<>();
        appendSchemaCandidates(schemaFieldMap, planningContext == null ? List.of() : planningContext.getExistingSchema(), planningContextFallback(planningContext));
        appendSchemaCandidates(schemaFieldMap,
                inferSchemaFromBaseline(planningContext == null ? Map.of() : planningContext.getBaselineConfig()),
                planningContextFallback(planningContext));
        if (schemaFieldMap.isEmpty()) {
            appendSchemaCandidates(schemaFieldMap, createDefaultGroupConfigSchema(), planningContextFallback(planningContext));
        }
        return normalizeSchemaSafely(new ArrayList<>(schemaFieldMap.values()));
    }

    private AIDesignPlanningContext planningContextFallback(AIDesignPlanningContext planningContext) {
        if (planningContext != null) {
            return planningContext;
        }
        return new AIDesignPlanningContext(List.of(), Map.of(), List.of(),
                List.of(DEFAULT_BASELINE_GROUP_ID, DEFAULT_VARIANT_GROUP_ID), "HASH", List.of(), List.of(), false);
    }

    private List<GroupConfigFieldDefinition> normalizeSchemaSafely(List<GroupConfigFieldDefinition> schemaFields) {
        if (schemaFields == null || schemaFields.isEmpty()) {
            return List.of();
        }
        List<GroupConfigFieldDefinition> normalizedSchema = groupConfigSchemaValidator.normalizeSchema(schemaFields);
        return normalizedSchema == null || normalizedSchema.isEmpty() ? schemaFields : normalizedSchema;
    }

    private List<ExperimentCreateRequest.GroupConfig> createEmptyGroups(List<String> groupIds) {
        double trafficRatio = DEFAULT_TOTAL_TRAFFIC / groupIds.size();
        List<ExperimentCreateRequest.GroupConfig> groups = new ArrayList<>();
        for (int i = 0; i < groupIds.size(); i++) {
            String groupId = groupIds.get(i);
            groups.add(createGroup(groupId, resolveSkeletonGroupName(groupId, i), trafficRatio, Map.of()));
        }
        return groups;
    }

    private ExperimentCreateRequest.GroupConfig createGroup(String id,
                                                            String name,
                                                            double trafficRatio,
                                                            Map<String, Object> config) {
        ExperimentCreateRequest.GroupConfig groupConfig = new ExperimentCreateRequest.GroupConfig();
        groupConfig.setId(id);
        groupConfig.setName(name);
        groupConfig.setTrafficRatio(trafficRatio);
        groupConfig.setConfig(config == null ? Map.of() : config);
        return groupConfig;
    }

    private List<GroupConfigFieldDefinition> createDefaultGroupConfigSchema() {
        return List.of(
                createSchemaField(MAIN_TITLE_KEY, "主标题", GroupConfigFieldDefinition.ValueType.STRING,
                        true, "实验组主标题", "官方质检二手手机"),
                createSchemaField(SUBTITLE_KEY, "副标题", GroupConfigFieldDefinition.ValueType.STRING,
                        true, "实验组副标题", "7天无理由退货"),
                createSchemaField(SHOW_QUALITY_BADGE_KEY, "展示质检标识",
                        GroupConfigFieldDefinition.ValueType.BOOLEAN, true, "是否展示质检背书", true),
                createSchemaField(BADGE_COUNT_KEY, "标签数量", GroupConfigFieldDefinition.ValueType.INTEGER,
                        true, "展示的标签数量", 2),
                createSchemaField(CARD_META_KEY, "卡片样式信息", GroupConfigFieldDefinition.ValueType.OBJECT,
                        true, "卡片样式和展示参数", Map.of("theme", "default")),
                createSchemaField(HIGHLIGHT_TAGS_KEY, "亮点标签", GroupConfigFieldDefinition.ValueType.JSON,
                        true, "展示在卡片上的标签列表", List.of("官方质检", "极速发货"))
        );
    }

    private String resolveGroupName(GeneratedGroup generatedGroup, int variantIndex) {
        if (generatedGroup == null) {
            return DEFAULT_GROUP_NAME_PREFIX + variantIndex;
        }
        if (StringUtils.hasText(generatedGroup.name())) {
            return generatedGroup.name().trim();
        }
        if (generatedGroup.control() || DEFAULT_BASELINE_GROUP_ID.equals(generatedGroup.groupId())) {
            return DEFAULT_BASELINE_GROUP_NAME;
        }
        if (DEFAULT_VARIANT_GROUP_ID.equals(generatedGroup.groupId()) || variantIndex == 1) {
            return DEFAULT_VARIANT_GROUP_NAME;
        }
        return DEFAULT_GROUP_NAME_PREFIX + variantIndex;
    }

    private String resolveSkeletonGroupName(String groupId, int index) {
        if (DEFAULT_BASELINE_GROUP_ID.equals(groupId) || index == 0) {
            return DEFAULT_BASELINE_GROUP_NAME;
        }
        if (DEFAULT_VARIANT_GROUP_ID.equals(groupId) || index == 1) {
            return DEFAULT_VARIANT_GROUP_NAME;
        }
        return DEFAULT_GROUP_NAME_PREFIX + index;
    }

    private GroupConfigFieldDefinition createSchemaField(String key,
                                                         String label,
                                                         GroupConfigFieldDefinition.ValueType valueType,
                                                         boolean required,
                                                         String description,
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

    private ExperimentCreateRequest.TrafficConfigRequest createTrafficConfig(List<String> groupIds, String strategy) {
        ExperimentCreateRequest.TrafficConfigRequest traffic = new ExperimentCreateRequest.TrafficConfigRequest();
        traffic.setTotalTraffic(DEFAULT_TOTAL_TRAFFIC);
        traffic.setStrategy(strategy);
        traffic.setAllocation(createAllocations(groupIds));
        return traffic;
    }

    private List<ExperimentCreateRequest.GroupAllocationRequest> createAllocations(List<String> groupIds) {
        double ratio = DEFAULT_TOTAL_TRAFFIC / groupIds.size();
        List<ExperimentCreateRequest.GroupAllocationRequest> allocations = new ArrayList<>();
        for (String groupId : groupIds) {
            allocations.add(createAllocation(groupId, ratio));
        }
        return allocations;
    }

    private ExperimentCreateRequest.GroupAllocationRequest createAllocation(String groupId, double ratio) {
        ExperimentCreateRequest.GroupAllocationRequest allocation = new ExperimentCreateRequest.GroupAllocationRequest();
        allocation.setGroup(groupId);
        allocation.setRatio(ratio);
        return allocation;
    }

    private List<String> mergeRiskFlags(List<String> aiRiskFlags, List<String> guardrailRiskFlags, boolean includeAiUnavailable) {
        List<String> mergedRiskFlags = new ArrayList<>();
        appendRiskFlags(mergedRiskFlags, aiRiskFlags);
        appendRiskFlags(mergedRiskFlags, guardrailRiskFlags);
        if (includeAiUnavailable) {
            appendRiskFlags(mergedRiskFlags, List.of(AI_UNAVAILABLE_RISK_FLAG));
        }
        return mergedRiskFlags;
    }

    private boolean containsAiUnavailableRisk(List<String> riskFlags) {
        return riskFlags != null && riskFlags.contains(AI_UNAVAILABLE_RISK_FLAG);
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

    private AIDiagnosisResponse createDiagnosisFallbackResponse(GuardrailStatus guardrailStatus) {
        AIDiagnosisResponse response = new AIDiagnosisResponse();
        response.setDecisionType(DecisionType.DIAGNOSIS.getCode());
        response.setGuardrailStatus(guardrailStatus.getCode());
        response.setSummary(DIAGNOSIS_FALLBACK_SUMMARY);
        response.setConfidence(FALLBACK_CONFIDENCE_SCORE);
        response.setRiskFlags(List.of(AI_UNAVAILABLE_RISK_FLAG));
        response.setRecommendedActions(List.of(createDefaultDiagnosisAction(guardrailStatus)));
        return response;
    }

    private AIGraduationDecisionResponse createGraduationFallbackResponse(GuardrailStatus guardrailStatus) {
        AIGraduationDecisionResponse response = new AIGraduationDecisionResponse();
        response.setDecisionType(DecisionType.GRADUATION.getCode());
        response.setGuardrailStatus(guardrailStatus.getCode());
        response.setSummary(GRADUATION_FALLBACK_SUMMARY);
        response.setConfidence(FALLBACK_CONFIDENCE_SCORE);
        response.setRiskFlags(List.of(AI_UNAVAILABLE_RISK_FLAG));
        response.setDecision(DEFAULT_GRADUATION_DECISION);
        return response;
    }

    private AIDecisionEvidenceResponse buildDecisionEvidence(ExperimentDecisionContext context) {
        AIDecisionEvidenceResponse evidence = new AIDecisionEvidenceResponse();
        if (context == null) {
            evidence.setBlockingIssues(List.of());
            evidence.setWarnings(List.of());
            evidence.setBreachedGuardrails(List.of());
            evidence.setStatisticsFacts(List.of());
            evidence.setGroupMetricSnapshots(List.of());
            evidence.setDataQualityFacts(List.of());
            evidence.setLatestReportBreachedGuardrails(List.of());
            evidence.setReportSnapshotFacts(List.of());
            return evidence;
        }
        Statistics statistics = context.getStatistics();
        evidence.setExperimentId(firstNonBlank(
                context.getExperimentId(), statistics == null ? null : statistics.getExperimentId(), null));
        evidence.setExperimentName(firstNonBlank(
                context.getExperimentName(), statistics == null ? null : statistics.getExperimentName(), null));
        evidence.setExperimentStatus(firstNonBlank(
                context.getExperimentStatus(), statistics == null ? null : statistics.getExperimentStatus(), null));
        evidence.setStatisticsFacts(safeStringList(context.getStatisticsFacts()));
        evidence.setGroupMetricSnapshots(safeStringList(context.getGroupMetricSnapshots()));
        evidence.setDataQualityFacts(safeStringList(context.getDataQualityFacts()));
        evidence.setReportSnapshotFacts(safeStringList(context.getReportSnapshotFacts()));
        evidence.setLatestReportSnapshotVersion(context.getLatestReportSnapshotVersion());
        evidence.setLatestReportGeneratedAt(context.getLatestReportGeneratedAt());
        evidence.setLatestReportConclusionStatus(context.getLatestReportConclusionStatus());
        evidence.setLatestReportAnalysisReady(context.getLatestReportAnalysisReady());
        evidence.setLatestReportHasSrm(context.getLatestReportHasSrm());
        evidence.setLatestReportPrimaryMetricKey(context.getLatestReportPrimaryMetricKey());
        evidence.setLatestReportBestPerformingGroup(context.getLatestReportBestPerformingGroup());
        evidence.setLatestReportWinningVariant(context.getLatestReportWinningVariant());
        evidence.setLatestReportBreachedGuardrails(safeStringList(context.getLatestReportBreachedGuardrails()));
        bindSummaryEvidence(evidence, statistics == null ? null : statistics.getSummary());
        bindDataQualityEvidence(evidence, statistics == null ? null : statistics.getDataQualityCheck());
        return evidence;
    }

    private void bindSummaryEvidence(AIDecisionEvidenceResponse evidence, Statistics.ExperimentSummary summary) {
        if (summary == null) {
            evidence.setBreachedGuardrails(List.of());
            return;
        }
        evidence.setPrimaryMetricKey(summary.getPrimaryMetricKey());
        evidence.setBestPerformingGroup(summary.getBestPerformingGroup());
        evidence.setBestPrimaryMetricValue(summary.getBestPrimaryMetricValue());
        evidence.setTotalAssignments(summary.getTotalAssignments());
        evidence.setTotalExposures(summary.getTotalExposures());
        evidence.setTotalEvents(summary.getTotalEvents());
        evidence.setTotalVisitors(summary.getTotalVisitors());
        evidence.setBreachedGuardrails(safeStringList(summary.getBreachedGuardrails()));
    }

    private void bindDataQualityEvidence(AIDecisionEvidenceResponse evidence,
                                         Statistics.DataQualityCheck dataQualityCheck) {
        if (dataQualityCheck == null) {
            evidence.setBlockingIssues(List.of());
            evidence.setWarnings(List.of());
            return;
        }
        evidence.setAnalysisReady(dataQualityCheck.getAnalysisReady());
        evidence.setHasSrm(dataQualityCheck.getHasSrm());
        evidence.setSrmPValue(dataQualityCheck.getSrmPValue());
        evidence.setSampleSizeReached(dataQualityCheck.getSampleSizeReached());
        evidence.setRequiredSampleSizePerGroup(dataQualityCheck.getRequiredSampleSizePerGroup());
        evidence.setBlockingIssues(safeStringList(dataQualityCheck.getBlockingIssues()));
        evidence.setWarnings(safeStringList(dataQualityCheck.getWarnings()));
    }

    private String defaultValue(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value.trim() : defaultValue;
    }

    private String firstNonBlank(String firstValue, String secondValue, String defaultValue) {
        if (StringUtils.hasText(firstValue)) {
            return firstValue.trim();
        }
        if (StringUtils.hasText(secondValue)) {
            return secondValue.trim();
        }
        return defaultValue;
    }

    private Map<String, Object> safeMap(Map<String, Object> map) {
        return map == null ? Map.of() : Map.copyOf(map);
    }

    private List<String> safeStringList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> normalizedValues = new ArrayList<>();
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                normalizedValues.add(value.trim());
            }
        }
        return normalizedValues;
    }

    private Map<String, Object> castObjectMap(Object value) {
        if (!(value instanceof Map<?, ?> rawMap)) {
            return Map.of();
        }
        Map<String, Object> objectMap = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            objectMap.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return objectMap;
    }

    private Map<String, Map<String, Object>> castNestedObjectMap(Object value) {
        Map<String, Map<String, Object>> nestedMap = new LinkedHashMap<>();
        if (!(value instanceof Map<?, ?> rawMap)) {
            return nestedMap;
        }
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            nestedMap.put(String.valueOf(entry.getKey()), castObjectMap(entry.getValue()));
        }
        return nestedMap;
    }

    private Map<String, String> castStringMap(Object value) {
        Map<String, String> stringMap = new LinkedHashMap<>();
        if (!(value instanceof Map<?, ?> rawMap)) {
            return stringMap;
        }
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            String normalizedRole = normalizeFieldRole(asText(entry.getValue()));
            if (StringUtils.hasText(normalizedRole)) {
                stringMap.put(String.valueOf(entry.getKey()), normalizedRole);
            }
        }
        return stringMap;
    }

    private String asText(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private Boolean asBoolean(Object value) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value instanceof String stringValue && StringUtils.hasText(stringValue)) {
            return Boolean.parseBoolean(stringValue.trim());
        }
        return false;
    }

    private record GeneratedGroup(String groupId, boolean control, String name) {
    }
}
