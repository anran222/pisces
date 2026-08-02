package com.pisces.service.ai;

import com.pisces.common.model.ExperimentDecisionContext;
import com.pisces.common.model.GroupConfigFieldDefinition;
import com.pisces.common.model.Statistics;
import com.pisces.common.request.AIDesignRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AI决策Prompt模板构建器
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/3/20 14:20
 */
@Component
@RequiredArgsConstructor
public class PromptTemplateBuilder {

    private static final String EMPTY_VALUE = "N/A";
    private static final String DESIGN_JSON_FIELDS =
            "decisionType, summary, confidence, riskFlags, guardrailStatus";
    private static final String DESIGN_SCHEMA_JSON_FIELDS =
            "decisionType, summary, confidence, riskFlags, guardrailStatus, schemaPlanning";
    private static final String DESIGN_DRAFT_JSON_FIELDS =
            "decisionType, summary, confidence, riskFlags, guardrailStatus, draftGeneration";
    private static final String DIAGNOSIS_JSON_FIELDS =
            "decisionType, summary, confidence, riskFlags, guardrailStatus, recommendedActions";
    private static final String GRADUATION_JSON_FIELDS =
            "decisionType, summary, confidence, riskFlags, guardrailStatus, decision";
    private static final String CONFIDENCE_REQUIREMENT = "- confidence 必须返回 0 到 1 之间的数字";

    private final AIDesignContextResolver aiDesignContextResolver;

    public String buildDesignPrompt(AIDesignRequest request) {
        return buildDesignSchemaPlanningPrompt(request, aiDesignContextResolver.resolve(request));
    }

    public String buildDesignSchemaPlanningPrompt(AIDesignRequest request, AIDesignPlanningContext planningContext) {
        return """
                你是实验平台的AI设计助手，当前处于 Schema Planning 阶段。
                请先判断这个实验应该设计哪些配置项，再返回结构化的字段规划结果。

                输入事实：
                - businessScenario: %s
                - targetMetric: %s
                - constraints: %s
                - baselineMode: %s
                - baselineConfig: %s
                - existingSchema: %s
                - schemaKeyHints: %s
                - disabledSchemaKeys: %s
                - draftGroups: %s
                - draftTrafficStrategy: %s
                - prioritizedConstraints: %s

                输出要求：
                - 只返回JSON对象
                - 必须包含字段：%s
                - schemaPlanning.groupConfigSchema 必须返回数组
                - 每个字段必须包含 key、label、valueType、required、description、fieldRole
                - key 必须使用 camelCase，不能包含下划线
                - fieldRole 只能为 BASELINE_STABLE、EXPERIMENT_VARIABLE、AUXILIARY_CONTEXT
                - 新增字段数不能少于 5 个
                - 不要输出 titleTemplateId 这类标识或引用型字段，实验本身已经独立定义
                - 不允许输出被 disabledSchemaKeys 禁用的字段
                - guardrailStatus 只能为 PASS 或 BLOCKED
                %s
                """.formatted(
                formatBusinessScenario(request),
                formatTargetMetric(request),
                formatList(request == null ? null : request.getConstraints()),
                planningContext != null && planningContext.isBaselineProvided() ? "REUSE" : "INFER",
                formatMap(planningContext == null ? null : planningContext.getBaselineConfig()),
                formatExistingSchema(planningContext == null ? null : planningContext.getExistingSchema()),
                formatList(planningContext == null ? null : planningContext.getSchemaKeyHints()),
                formatList(planningContext == null ? null : planningContext.getDisabledSchemaKeys()),
                formatList(planningContext == null ? null : planningContext.getDraftGroupIds()),
                defaultValue(planningContext == null ? null : planningContext.getTrafficStrategy()),
                formatList(planningContext == null ? null : planningContext.getPrioritizedConstraints()),
                DESIGN_SCHEMA_JSON_FIELDS,
                CONFIDENCE_REQUIREMENT);
    }

    public String buildDesignDraftFillingPrompt(AIDesignRequest request,
                                                AIDesignPlanningContext planningContext,
                                                List<GroupConfigFieldDefinition> schema,
                                                Map<String, String> fieldRoles) {
        return """
                你是实验平台的AI设计助手，当前处于 Draft Filling 阶段。
                请基于既定 schema，为对照组和实验组生成完整配置值。

                输入事实：
                - businessScenario: %s
                - targetMetric: %s
                - constraints: %s
                - baselineConfig: %s
                - groupConfigSchema: %s
                - allSchemaKeys: %s
                - requiredSchemaKeys: %s
                - fieldRoles: %s
                - draftGroups: %s
                - draftTrafficStrategy: %s
                - prioritizedConstraints: %s
                - controlConfig 输出骨架: %s
                - variantConfigs[groupId] 输出骨架: %s

                输出要求：
                - 只返回JSON对象
                - 必须包含字段：%s
                - draftGeneration 必须包含 controlConfig、variantConfigs、filledGroups
                - filledGroups 至少返回 2 个组，可以多于 2 个组
                - controlConfig 必须是完整配置，并优先沿用 baselineConfig 已有值
                - variantConfigs 必须以 groupId 为 key 返回每个实验组的完整配置
                - 每个实验组必须覆盖 schema 中全部字段
                - allSchemaKeys 中列出的每个字段，都必须同时出现在 controlConfig 和每个 variantConfigs[groupId] 中
                - 即使字段 required=false，也必须在每个组配置中显式返回，不能省略
                - 任何 schema 字段都不允许缺失，缺失任意字段都视为失败
                - 如果 draftGroups 为空，需要自行规划实验组，但总组数不能少于 2 个
                - BASELINE_STABLE 字段默认保持与对照组一致，除非明确给出充分理由
                - guardrailStatus 只能为 PASS 或 BLOCKED
                %s
                """.formatted(
                formatBusinessScenario(request),
                formatTargetMetric(request),
                formatList(request == null ? null : request.getConstraints()),
                formatMap(planningContext == null ? null : planningContext.getBaselineConfig()),
                formatSchema(schema),
                formatAllSchemaKeys(schema),
                formatRequiredSchemaKeys(schema),
                formatSchemaFieldRoles(schema, fieldRoles),
                formatList(planningContext == null ? null : planningContext.getDraftGroupIds()),
                defaultValue(planningContext == null ? null : planningContext.getTrafficStrategy()),
                formatList(planningContext == null ? null : planningContext.getPrioritizedConstraints()),
                formatConfigSkeleton(schema),
                formatConfigSkeleton(schema),
                DESIGN_DRAFT_JSON_FIELDS,
                CONFIDENCE_REQUIREMENT);
    }

    public String buildDiagnosisPrompt(ExperimentDecisionContext context) {
        return """
                你是实验平台的AI诊断助手。
                请基于以下实验上下文输出诊断建议：
                - experimentId: %s
                - experimentName: %s
                - experimentStatus: %s
                - statisticsFacts: %s
                - groupMetricSnapshot: %s
                - dataQualityFacts: %s
                - reportSnapshotFacts: %s
                - decisionHints: %s

                输出要求：
                - 只返回JSON对象
                - 必须包含字段：%s
                - recommendedActions 必须返回数组
                %s
                """.formatted(
                defaultValue(context == null ? null : context.getExperimentId()),
                defaultValue(context == null ? null : context.getExperimentName()),
                defaultValue(context == null ? null : context.getExperimentStatus()),
                defaultValue(formatStatisticsFacts(context)),
                defaultValue(formatGroupMetricSnapshot(context)),
                defaultValue(formatDataQualityFacts(context)),
                defaultValue(formatReportSnapshotFacts(context)),
                defaultValue(buildDecisionHints(context)),
                DIAGNOSIS_JSON_FIELDS,
                CONFIDENCE_REQUIREMENT);
    }

    public String buildGraduationPrompt(ExperimentDecisionContext context) {
        return """
                你是实验平台的AI毕业决策助手。
                请基于以下实验上下文输出毕业决策建议：
                - experimentId: %s
                - experimentName: %s
                - experimentStatus: %s
                - statisticsFacts: %s
                - groupMetricSnapshot: %s
                - dataQualityFacts: %s
                - reportSnapshotFacts: %s
                - decisionHints: %s

                输出要求：
                - 只返回JSON对象
                - 必须包含字段：%s
                - decision 只能为 GRADUATE、CONTINUE 或 ROLLBACK
                %s
                """.formatted(
                defaultValue(context == null ? null : context.getExperimentId()),
                defaultValue(context == null ? null : context.getExperimentName()),
                defaultValue(context == null ? null : context.getExperimentStatus()),
                defaultValue(formatStatisticsFacts(context)),
                defaultValue(formatGroupMetricSnapshot(context)),
                defaultValue(formatDataQualityFacts(context)),
                defaultValue(formatReportSnapshotFacts(context)),
                defaultValue(buildDecisionHints(context)),
                GRADUATION_JSON_FIELDS,
                CONFIDENCE_REQUIREMENT);
    }

    private String formatBusinessScenario(AIDesignRequest request) {
        return request == null ? EMPTY_VALUE : defaultValue(request.getBusinessScenario());
    }

    private String formatTargetMetric(AIDesignRequest request) {
        return request == null ? EMPTY_VALUE : defaultValue(request.getTargetMetric());
    }

    private String defaultValue(String value) {
        return StringUtils.hasText(value) ? value.trim() : EMPTY_VALUE;
    }

    private String formatList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return EMPTY_VALUE;
        }
        return values.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .reduce((left, right) -> left + ", " + right)
                .orElse(EMPTY_VALUE);
    }

    private String formatMap(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return EMPTY_VALUE;
        }
        List<String> pairs = new ArrayList<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            pairs.add(entry.getKey() + "=" + entry.getValue());
        }
        return String.join("; ", pairs);
    }

    private String formatExistingSchema(List<GroupConfigFieldDefinition> schema) {
        if (schema == null || schema.isEmpty()) {
            return EMPTY_VALUE;
        }
        return formatSchema(schema);
    }

    private String formatSchema(List<GroupConfigFieldDefinition> schema) {
        if (schema == null || schema.isEmpty()) {
            return EMPTY_VALUE;
        }
        List<String> schemaFacts = new ArrayList<>();
        for (GroupConfigFieldDefinition fieldDefinition : schema) {
            if (fieldDefinition == null) {
                continue;
            }
            schemaFacts.add(fieldDefinition.getKey() + "("
                    + defaultValue(fieldDefinition.getLabel()) + ","
                    + (fieldDefinition.getValueType() == null ? EMPTY_VALUE : fieldDefinition.getValueType().name()) + ","
                    + "required=" + Boolean.TRUE.equals(fieldDefinition.getRequired()) + ","
                    + "description=" + defaultValue(fieldDefinition.getDescription()) + ","
                    + "defaultValue=" + formatDefaultValue(fieldDefinition.getDefaultValue()) + ")");
        }
        return schemaFacts.isEmpty() ? EMPTY_VALUE : String.join("; ", schemaFacts);
    }

    private String formatRequiredSchemaKeys(List<GroupConfigFieldDefinition> schema) {
        if (schema == null || schema.isEmpty()) {
            return EMPTY_VALUE;
        }
        List<String> requiredSchemaKeys = new ArrayList<>();
        for (GroupConfigFieldDefinition fieldDefinition : schema) {
            if (fieldDefinition == null
                    || !Boolean.TRUE.equals(fieldDefinition.getRequired())
                    || !StringUtils.hasText(fieldDefinition.getKey())) {
                continue;
            }
            requiredSchemaKeys.add(fieldDefinition.getKey().trim());
        }
        return requiredSchemaKeys.isEmpty() ? EMPTY_VALUE : String.join(", ", requiredSchemaKeys);
    }

    private String formatAllSchemaKeys(List<GroupConfigFieldDefinition> schema) {
        if (schema == null || schema.isEmpty()) {
            return EMPTY_VALUE;
        }
        List<String> schemaKeys = new ArrayList<>();
        for (GroupConfigFieldDefinition fieldDefinition : schema) {
            if (fieldDefinition == null || !StringUtils.hasText(fieldDefinition.getKey())) {
                continue;
            }
            schemaKeys.add(fieldDefinition.getKey().trim());
        }
        return schemaKeys.isEmpty() ? EMPTY_VALUE : String.join(", ", schemaKeys);
    }

    private String formatDefaultValue(Object defaultValue) {
        return defaultValue == null ? EMPTY_VALUE : String.valueOf(defaultValue);
    }

    private String formatSchemaFieldRoles(List<GroupConfigFieldDefinition> schema, Map<String, String> values) {
        if (schema == null || schema.isEmpty() || values == null || values.isEmpty()) {
            return EMPTY_VALUE;
        }
        List<String> pairs = new ArrayList<>();
        for (GroupConfigFieldDefinition fieldDefinition : schema) {
            if (fieldDefinition == null || !StringUtils.hasText(fieldDefinition.getKey())) {
                continue;
            }
            String role = values.get(fieldDefinition.getKey());
            if (!StringUtils.hasText(role)) {
                continue;
            }
            pairs.add(fieldDefinition.getKey().trim() + "=" + role.trim());
        }
        return pairs.isEmpty() ? EMPTY_VALUE : String.join("; ", pairs);
    }

    private String formatStringMap(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return EMPTY_VALUE;
        }
        List<String> pairs = new ArrayList<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            pairs.add(entry.getKey() + "=" + entry.getValue());
        }
        return String.join("; ", pairs);
    }

    private String formatConfigSkeleton(List<GroupConfigFieldDefinition> schema) {
        if (schema == null || schema.isEmpty()) {
            return EMPTY_VALUE;
        }
        List<String> pairs = new ArrayList<>();
        for (GroupConfigFieldDefinition fieldDefinition : schema) {
            if (fieldDefinition == null || !StringUtils.hasText(fieldDefinition.getKey())) {
                continue;
            }
            pairs.add("\"" + fieldDefinition.getKey().trim() + "\": " + buildValuePlaceholder(fieldDefinition));
        }
        return pairs.isEmpty() ? EMPTY_VALUE : "{ " + String.join(", ", pairs) + " }";
    }

    private String buildValuePlaceholder(GroupConfigFieldDefinition fieldDefinition) {
        if (fieldDefinition == null || fieldDefinition.getValueType() == null) {
            return "\"" + EMPTY_VALUE + "\"";
        }
        return switch (fieldDefinition.getValueType()) {
            case STRING -> "\"STRING\"";
            case INTEGER -> "0";
            case BOOLEAN -> "true/false";
            case OBJECT -> "{\"key\":\"value\"}";
            case JSON -> "\"JSON\"";
        };
    }

    private String formatStatisticsFacts(ExperimentDecisionContext context) {
        if (context != null && context.getStatisticsFacts() != null && !context.getStatisticsFacts().isEmpty()) {
            return String.join("; ", context.getStatisticsFacts());
        }
        if (context == null || context.getStatistics() == null || context.getStatistics().getSummary() == null) {
            return EMPTY_VALUE;
        }
        Statistics.ExperimentSummary summary = context.getStatistics().getSummary();
        List<String> facts = new ArrayList<>();
        appendFact(facts, "bestPerformingGroup", summary.getBestPerformingGroup());
        appendFact(facts, "primaryMetricKey", summary.getPrimaryMetricKey());
        appendFact(facts, "bestPrimaryMetricValue", summary.getBestPrimaryMetricValue());
        appendFact(facts, "totalAssignments", summary.getTotalAssignments());
        appendFact(facts, "totalExposures", summary.getTotalExposures());
        return facts.isEmpty() ? EMPTY_VALUE : String.join("; ", facts);
    }

    private String formatGroupMetricSnapshot(ExperimentDecisionContext context) {
        if (context != null && context.getGroupMetricSnapshots() != null && !context.getGroupMetricSnapshots().isEmpty()) {
            return String.join("; ", context.getGroupMetricSnapshots());
        }
        if (context == null
                || context.getStatistics() == null
                || context.getStatistics().getGroupStatistics() == null
                || context.getStatistics().getGroupStatistics().isEmpty()) {
            return EMPTY_VALUE;
        }
        String primaryMetricKey = context.getStatistics().getSummary() != null
                ? context.getStatistics().getSummary().getPrimaryMetricKey() : null;
        List<String> snapshots = new ArrayList<>();
        for (Statistics.GroupStatistics groupStatistics : context.getStatistics().getGroupStatistics().values()) {
            if (groupStatistics == null) {
                continue;
            }
            StringBuilder builder = new StringBuilder();
            builder.append(defaultValue(groupStatistics.getGroupId()));
            if (StringUtils.hasText(groupStatistics.getGroupName())) {
                builder.append("(").append(groupStatistics.getGroupName().trim()).append(")");
            }
            if (StringUtils.hasText(primaryMetricKey)
                    && groupStatistics.getMetricValues() != null
                    && groupStatistics.getMetricValues().containsKey(primaryMetricKey)) {
                builder.append(": ").append(primaryMetricKey).append("=")
                        .append(groupStatistics.getMetricValues().get(primaryMetricKey));
            } else if (groupStatistics.getConversionRate() != null) {
                builder.append(": conversionRate=").append(groupStatistics.getConversionRate());
            }
            snapshots.add(builder.toString());
        }
        return snapshots.isEmpty() ? EMPTY_VALUE : String.join("; ", snapshots);
    }

    private String formatDataQualityFacts(ExperimentDecisionContext context) {
        if (context != null && context.getDataQualityFacts() != null && !context.getDataQualityFacts().isEmpty()) {
            return String.join("; ", context.getDataQualityFacts());
        }
        if (context == null || context.getStatistics() == null || context.getStatistics().getDataQualityCheck() == null) {
            return EMPTY_VALUE;
        }
        Statistics.DataQualityCheck dataQualityCheck = context.getStatistics().getDataQualityCheck();
        List<String> facts = new ArrayList<>();
        appendFact(facts, "analysisReady", dataQualityCheck.getAnalysisReady());
        appendFact(facts, "hasSrm", dataQualityCheck.getHasSrm());
        appendFact(facts, "sampleSizeReached", dataQualityCheck.getSampleSizeReached());
        appendFact(facts, "blockingIssues", dataQualityCheck.getBlockingIssues());
        appendFact(facts, "warnings", dataQualityCheck.getWarnings());
        return facts.isEmpty() ? EMPTY_VALUE : String.join("; ", facts);
    }

    private String formatReportSnapshotFacts(ExperimentDecisionContext context) {
        if (context == null || context.getReportSnapshotFacts() == null || context.getReportSnapshotFacts().isEmpty()) {
            return EMPTY_VALUE;
        }
        return String.join("; ", context.getReportSnapshotFacts());
    }

    private String buildDecisionHints(ExperimentDecisionContext context) {
        if (context != null && context.getDecisionHints() != null && !context.getDecisionHints().isEmpty()) {
            return String.join("; ", context.getDecisionHints());
        }
        return EMPTY_VALUE;
    }

    private void appendFact(List<String> facts, String key, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String stringValue && !StringUtils.hasText(stringValue)) {
            return;
        }
        facts.add(key + "=" + value);
    }
}
