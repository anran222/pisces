package com.pisces.service.ai;

import com.pisces.common.model.ExperimentDecisionContext;
import com.pisces.common.model.Statistics;
import com.pisces.common.request.AIDesignRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * AI决策Prompt模板构建器
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/3/20 14:20
 */
@Component
public class PromptTemplateBuilder {

    private static final String DEMO_EXPERIMENT_TAG = "[USED_PHONE_DEMO_";
    private static final String EMPTY_VALUE = "N/A";
    private static final String DESIGN_JSON_FIELDS = "decisionType, summary, confidence, riskFlags, guardrailStatus";
    private static final String DIAGNOSIS_JSON_FIELDS = "decisionType, summary, confidence, riskFlags, guardrailStatus, recommendedActions";
    private static final String GRADUATION_JSON_FIELDS = "decisionType, summary, confidence, riskFlags, guardrailStatus, decision";
    private static final String CONFIDENCE_REQUIREMENT = "- confidence 必须返回 0 到 1 之间的数字";

    public String buildDesignPrompt(AIDesignRequest request) {
        String businessScenario = request == null ? EMPTY_VALUE : defaultValue(request.getBusinessScenario());
        String targetMetric = request == null ? EMPTY_VALUE : defaultValue(request.getTargetMetric());
        String constraints = request == null ? EMPTY_VALUE : formatList(request.getConstraints());
        return """
                你是实验平台的AI设计助手。
                请基于以下信息输出实验设计建议：
                - businessScenario: %s
                - targetMetric: %s
                - constraints: %s

                输出要求：
                - 只返回JSON对象
                - 必须包含字段：%s
                - guardrailStatus 只能为 PASS 或 BLOCKED
                %s
                """.formatted(businessScenario, targetMetric, constraints, DESIGN_JSON_FIELDS, CONFIDENCE_REQUIREMENT);
    }

    public String buildDiagnosisPrompt(ExperimentDecisionContext context) {
        return """
                你是实验平台的AI诊断助手。
                请基于以下实验上下文输出诊断建议：
                - experimentId: %s
                - experimentName: %s
                - experimentStatus: %s

                输出要求：
                - 只返回JSON对象
                - 必须包含字段：%s
                - recommendedActions 必须返回数组
                %s
                """.formatted(
                defaultValue(context == null ? null : context.getExperimentId()),
                defaultValue(context == null ? null : context.getExperimentName()),
                defaultValue(context == null ? null : context.getExperimentStatus()),
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
                defaultValue(buildDecisionHints(context)),
                GRADUATION_JSON_FIELDS,
                CONFIDENCE_REQUIREMENT);
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

    private String formatStatisticsFacts(ExperimentDecisionContext context) {
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

    private String buildDecisionHints(ExperimentDecisionContext context) {
        if (context == null || !StringUtils.hasText(context.getExperimentName())) {
            return EMPTY_VALUE;
        }
        if (context.getExperimentName().contains(DEMO_EXPERIMENT_TAG)) {
            return "这是固定演示实验。请优先依据当前主指标和最佳组表现给出演示性毕业建议，不要因为样本量门槛而保守返回 CONTINUE。";
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
