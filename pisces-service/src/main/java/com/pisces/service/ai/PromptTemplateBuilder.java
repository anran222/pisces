package com.pisces.service.ai;

import com.pisces.common.model.ExperimentDecisionContext;
import com.pisces.common.request.AIDesignRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * AI决策Prompt模板构建器
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/3/20 14:20
 */
@Component
public class PromptTemplateBuilder {

    private static final String EMPTY_VALUE = "N/A";
    private static final String DESIGN_JSON_FIELDS = "decisionType, summary, confidence, riskFlags, guardrailStatus";
    private static final String DIAGNOSIS_JSON_FIELDS = "decisionType, summary, confidence, riskFlags, guardrailStatus, recommendedActions";
    private static final String GRADUATION_JSON_FIELDS = "decisionType, summary, confidence, riskFlags, guardrailStatus, decision";

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
                """.formatted(businessScenario, targetMetric, constraints, DESIGN_JSON_FIELDS);
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
                """.formatted(
                defaultContextValue(context == null ? null : context.getExperimentId()),
                defaultContextValue(context == null ? null : context.getExperimentName()),
                defaultContextValue(context == null ? null : context.getExperimentStatus()),
                DIAGNOSIS_JSON_FIELDS);
    }

    public String buildGraduationPrompt(ExperimentDecisionContext context) {
        return """
                你是实验平台的AI毕业决策助手。
                请基于以下实验上下文输出毕业决策建议：
                - experimentId: %s
                - experimentName: %s
                - experimentStatus: %s

                输出要求：
                - 只返回JSON对象
                - 必须包含字段：%s
                - decision 只能为 GRADUATE、CONTINUE 或 ROLLBACK
                """.formatted(
                defaultContextValue(context == null ? null : context.getExperimentId()),
                defaultContextValue(context == null ? null : context.getExperimentName()),
                defaultContextValue(context == null ? null : context.getExperimentStatus()),
                GRADUATION_JSON_FIELDS);
    }

    private String defaultContextValue(String value) {
        return defaultValue(value);
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
}
