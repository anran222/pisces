package com.pisces.service.service.impl;

import com.pisces.common.request.AIDesignRequest;
import com.pisces.common.model.ExperimentDecisionContext;
import com.pisces.common.response.AIDesignResponse;
import com.pisces.common.response.AIDiagnosisResponse;
import com.pisces.common.response.AIGraduationDecisionResponse;
import com.pisces.service.ai.AIDecisionJsonParser;
import com.pisces.service.ai.DecisionType;
import com.pisces.service.ai.ExperimentDecisionContextBuilder;
import com.pisces.service.ai.GuardrailStatus;
import com.pisces.service.ai.PromptTemplateBuilder;
import com.pisces.service.service.AIDecisionService;
import com.pisces.service.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

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
public class AIDecisionServiceImpl implements AIDecisionService {

    private static final double DEFAULT_CONFIDENCE = 0.5D;
    private static final String DEFAULT_DESIGN_SCENARIO = "通用业务场景";
    private static final String DEFAULT_EXPERIMENT_NAME = "未知实验";
    private static final String DEFAULT_GRADUATION_DECISION = "CONTINUE";
    private static final String DESIGN_SUMMARY_PREFIX = "AI实验设计草案: ";
    private static final String DIAGNOSIS_SUMMARY_PREFIX = "AI实验诊断草案: ";
    private static final String GRADUATION_SUMMARY_PREFIX = "AI毕业决策草案: ";

    private final ExperimentDecisionContextBuilder experimentDecisionContextBuilder;
    private final PromptTemplateBuilder promptTemplateBuilder;
    private final AIDecisionJsonParser aiDecisionJsonParser;
    private final JsonUtil jsonUtil;

    @Override
    public AIDesignResponse designExperiment(AIDesignRequest request) {
        String prompt = promptTemplateBuilder.buildDesignPrompt(request);
        if (!StringUtils.hasText(prompt)) {
            throw new IllegalStateException("AI设计Prompt不能为空");
        }
        String businessScenario = request == null ? null : request.getBusinessScenario();
        String payload = jsonUtil.toJson(Map.of(
                "decisionType", DecisionType.DESIGN.getCode(),
                "summary", DESIGN_SUMMARY_PREFIX + defaultValue(businessScenario, DEFAULT_DESIGN_SCENARIO),
                "confidence", DEFAULT_CONFIDENCE,
                "riskFlags", List.of(),
                "guardrailStatus", GuardrailStatus.PASS.getCode()));
        return aiDecisionJsonParser.parseDesign(payload);
    }

    @Override
    public AIDiagnosisResponse diagnoseExperiment(String experimentId) {
        ExperimentDecisionContext context = experimentDecisionContextBuilder.buildForExperiment(experimentId);
        String prompt = promptTemplateBuilder.buildDiagnosisPrompt(context);
        if (!StringUtils.hasText(prompt)) {
            throw new IllegalStateException("AI诊断Prompt不能为空");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("decisionType", DecisionType.DIAGNOSIS.getCode());
        payload.put("summary", DIAGNOSIS_SUMMARY_PREFIX + defaultValue(
                context == null ? null : context.getExperimentName(),
                DEFAULT_EXPERIMENT_NAME));
        payload.put("confidence", DEFAULT_CONFIDENCE);
        payload.put("riskFlags", List.of());
        payload.put("guardrailStatus", GuardrailStatus.PASS.getCode());
        payload.put("recommendedActions", List.of());
        return aiDecisionJsonParser.parseDiagnosis(jsonUtil.toJson(payload));
    }

    @Override
    public AIGraduationDecisionResponse decideGraduation(String experimentId) {
        ExperimentDecisionContext context = experimentDecisionContextBuilder.buildForExperiment(experimentId);
        String prompt = promptTemplateBuilder.buildGraduationPrompt(context);
        if (!StringUtils.hasText(prompt)) {
            throw new IllegalStateException("AI毕业决策Prompt不能为空");
        }
        String payload = jsonUtil.toJson(Map.of(
                "decisionType", DecisionType.GRADUATION.getCode(),
                "summary", GRADUATION_SUMMARY_PREFIX + defaultValue(
                        context == null ? null : context.getExperimentName(),
                        DEFAULT_EXPERIMENT_NAME),
                "confidence", DEFAULT_CONFIDENCE,
                "riskFlags", List.of(),
                "guardrailStatus", GuardrailStatus.PASS.getCode(),
                "decision", DEFAULT_GRADUATION_DECISION));
        return aiDecisionJsonParser.parseGraduation(payload);
    }

    private String defaultValue(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value.trim() : defaultValue;
    }
}
