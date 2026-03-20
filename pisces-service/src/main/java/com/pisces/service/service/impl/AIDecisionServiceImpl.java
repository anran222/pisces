package com.pisces.service.service.impl;

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
    private static final String DEFAULT_BASELINE_GROUP_ID = "control";
    private static final String DEFAULT_BASELINE_GROUP_NAME = "对照组";
    private static final String DEFAULT_VARIANT_GROUP_ID = "variant_a";
    private static final String DEFAULT_VARIANT_GROUP_NAME = "实验组A";
    private static final String DEFAULT_TRAFFIC_STRATEGY = "HASH";
    private static final double DEFAULT_TRAFFIC_RATIO = 0.5D;
    private static final double DEFAULT_TOTAL_TRAFFIC = 1.0D;
    private static final String DESIGN_NAME_SUFFIX = "实验";

    private final ExperimentDecisionContextBuilder experimentDecisionContextBuilder;
    private final PromptTemplateBuilder promptTemplateBuilder;
    private final AIDecisionJsonParser aiDecisionJsonParser;
    private final DecisionGuardrailEvaluator decisionGuardrailEvaluator;
    private final JsonUtil jsonUtil;

    @Override
    public AIDesignResponse designExperiment(AIDesignRequest request) {
        String prompt = promptTemplateBuilder.buildDesignPrompt(request);
        if (!StringUtils.hasText(prompt)) {
            throw new IllegalStateException("AI设计Prompt不能为空");
        }
        String businessScenario = request == null ? null : request.getBusinessScenario();
        ExperimentCreateRequest experimentDraft = createExperimentDraft(request);
        String payload = jsonUtil.toJson(Map.of(
                "decisionType", DecisionType.DESIGN.getCode(),
                "summary", DESIGN_SUMMARY_PREFIX + defaultValue(businessScenario, DEFAULT_DESIGN_SCENARIO),
                "confidence", DEFAULT_CONFIDENCE,
                "riskFlags", List.of(),
                "guardrailStatus", GuardrailStatus.PASS.getCode(),
                "experimentDraft", experimentDraft));
        return aiDecisionJsonParser.parseDesign(payload);
    }

    @Override
    public AIDiagnosisResponse diagnoseExperiment(String experimentId) {
        ExperimentDecisionContext context = experimentDecisionContextBuilder.buildForExperiment(experimentId);
        String prompt = promptTemplateBuilder.buildDiagnosisPrompt(context);
        if (!StringUtils.hasText(prompt)) {
            throw new IllegalStateException("AI诊断Prompt不能为空");
        }
        GuardrailStatus guardrailStatus = decisionGuardrailEvaluator.evaluateDiagnosis(context);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("decisionType", DecisionType.DIAGNOSIS.getCode());
        payload.put("summary", DIAGNOSIS_SUMMARY_PREFIX + defaultValue(
                context == null ? null : context.getExperimentName(),
                DEFAULT_EXPERIMENT_NAME));
        payload.put("confidence", DEFAULT_CONFIDENCE);
        payload.put("riskFlags", decisionGuardrailEvaluator.collectRiskFlags(context));
        payload.put("guardrailStatus", guardrailStatus.getCode());
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
        GuardrailStatus guardrailStatus = decisionGuardrailEvaluator.evaluateGraduation(context);
        String payload = jsonUtil.toJson(Map.of(
                "decisionType", DecisionType.GRADUATION.getCode(),
                "summary", GRADUATION_SUMMARY_PREFIX + defaultValue(
                        context == null ? null : context.getExperimentName(),
                        DEFAULT_EXPERIMENT_NAME),
                "confidence", DEFAULT_CONFIDENCE,
                "riskFlags", decisionGuardrailEvaluator.collectRiskFlags(context),
                "guardrailStatus", guardrailStatus.getCode(),
                "decision", DEFAULT_GRADUATION_DECISION));
        return aiDecisionJsonParser.parseGraduation(payload);
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
        return groupConfig;
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
}
