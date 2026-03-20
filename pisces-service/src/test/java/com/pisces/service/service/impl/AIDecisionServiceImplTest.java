package com.pisces.service.service.impl;

import com.pisces.common.request.AIDesignRequest;
import com.pisces.common.model.ExperimentDecisionContext;
import com.pisces.common.response.AIDesignResponse;
import com.pisces.common.response.AIDiagnosisResponse;
import com.pisces.common.response.AIGraduationDecisionResponse;
import com.pisces.service.ai.AIDecisionJsonParser;
import com.pisces.service.ai.DecisionGuardrailEvaluator;
import com.pisces.service.ai.DecisionType;
import com.pisces.service.ai.ExperimentDecisionContextBuilder;
import com.pisces.service.ai.GuardrailStatus;
import com.pisces.service.ai.PromptTemplateBuilder;
import com.pisces.service.util.JsonUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
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
    private JsonUtil jsonUtil;

    @Test
    void designExperimentShouldReturnDefaultDecision() {
        AIDecisionServiceImpl aiDecisionService = new AIDecisionServiceImpl(
                experimentDecisionContextBuilder,
                promptTemplateBuilder,
                aiDecisionJsonParser,
                decisionGuardrailEvaluator,
                jsonUtil);
        AIDesignRequest request = new AIDesignRequest();
        request.setBusinessScenario("checkout");
        request.setTargetMetric("conversion");
        request.setConstraints(List.of("avoid regression"));
        AIDesignResponse expected = new AIDesignResponse();
        expected.setDecisionType(DecisionType.DESIGN.getCode());
        expected.setGuardrailStatus(GuardrailStatus.PASS.getCode());

        when(promptTemplateBuilder.buildDesignPrompt(request)).thenReturn("design-prompt");
        when(jsonUtil.toJson(any())).thenReturn("{\"decisionType\":\"DESIGN\"}");
        when(aiDecisionJsonParser.parseDesign(org.mockito.ArgumentMatchers.anyString())).thenReturn(expected);

        AIDesignResponse response = aiDecisionService.designExperiment(request);

        assertSame(expected, response);
        verify(promptTemplateBuilder).buildDesignPrompt(request);
        verify(jsonUtil).toJson(any());
        verify(aiDecisionJsonParser).parseDesign(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void diagnoseExperimentShouldReturnDefaultDecision() {
        AIDecisionServiceImpl aiDecisionService = new AIDecisionServiceImpl(
                experimentDecisionContextBuilder,
                promptTemplateBuilder,
                aiDecisionJsonParser,
                decisionGuardrailEvaluator,
                jsonUtil);
        ExperimentDecisionContext context = new ExperimentDecisionContext();
        context.setExperimentId("exp_001");
        context.setExperimentName("新客首单优惠");
        AIDiagnosisResponse expected = new AIDiagnosisResponse();
        expected.setDecisionType(DecisionType.DIAGNOSIS.getCode());
        expected.setGuardrailStatus(GuardrailStatus.PASS.getCode());

        when(experimentDecisionContextBuilder.buildForExperiment("exp_001")).thenReturn(context);
        when(promptTemplateBuilder.buildDiagnosisPrompt(context)).thenReturn("diagnosis-prompt");
        when(decisionGuardrailEvaluator.evaluateDiagnosis(context)).thenReturn(GuardrailStatus.BLOCKED);
        when(decisionGuardrailEvaluator.collectRiskFlags(context)).thenReturn(List.of("SRM"));
        when(jsonUtil.toJson(any())).thenReturn("{\"decisionType\":\"DIAGNOSIS\"}");
        when(aiDecisionJsonParser.parseDiagnosis(org.mockito.ArgumentMatchers.anyString())).thenReturn(expected);

        AIDiagnosisResponse response = aiDecisionService.diagnoseExperiment("exp_001");

        assertSame(expected, response);
        verify(experimentDecisionContextBuilder).buildForExperiment("exp_001");
        verify(promptTemplateBuilder).buildDiagnosisPrompt(context);
        verify(decisionGuardrailEvaluator).evaluateDiagnosis(context);
        verify(decisionGuardrailEvaluator).collectRiskFlags(context);
        verify(jsonUtil).toJson(any());
        verify(aiDecisionJsonParser).parseDiagnosis(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void decideGraduationShouldReturnDefaultDecision() {
        AIDecisionServiceImpl aiDecisionService = new AIDecisionServiceImpl(
                experimentDecisionContextBuilder,
                promptTemplateBuilder,
                aiDecisionJsonParser,
                decisionGuardrailEvaluator,
                jsonUtil);
        ExperimentDecisionContext context = new ExperimentDecisionContext();
        context.setExperimentId("exp_001");
        context.setExperimentName("新客首单优惠");
        AIGraduationDecisionResponse expected = new AIGraduationDecisionResponse();
        expected.setDecisionType(DecisionType.GRADUATION.getCode());
        expected.setGuardrailStatus(GuardrailStatus.PASS.getCode());
        expected.setDecision("CONTINUE");

        when(experimentDecisionContextBuilder.buildForExperiment("exp_001")).thenReturn(context);
        when(promptTemplateBuilder.buildGraduationPrompt(context)).thenReturn("graduation-prompt");
        when(decisionGuardrailEvaluator.evaluateGraduation(context)).thenReturn(GuardrailStatus.BLOCKED);
        when(decisionGuardrailEvaluator.collectRiskFlags(context)).thenReturn(List.of("SRM"));
        when(jsonUtil.toJson(any())).thenReturn("{\"decisionType\":\"GRADUATION\"}");
        when(aiDecisionJsonParser.parseGraduation(org.mockito.ArgumentMatchers.anyString())).thenReturn(expected);

        AIGraduationDecisionResponse response = aiDecisionService.decideGraduation("exp_001");

        assertSame(expected, response);
        verify(experimentDecisionContextBuilder).buildForExperiment("exp_001");
        verify(promptTemplateBuilder).buildGraduationPrompt(context);
        verify(decisionGuardrailEvaluator).evaluateGraduation(context);
        verify(decisionGuardrailEvaluator).collectRiskFlags(context);
        verify(jsonUtil).toJson(any());
        verify(aiDecisionJsonParser).parseGraduation(org.mockito.ArgumentMatchers.anyString());
    }
}
