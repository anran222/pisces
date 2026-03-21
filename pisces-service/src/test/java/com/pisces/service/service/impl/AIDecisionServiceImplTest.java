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
import com.pisces.service.ai.TongYiTextGenerationClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

    @Test
    void designExperimentShouldCallTongYiAndAttachExperimentDraft() {
        AIDecisionServiceImpl aiDecisionService = new AIDecisionServiceImpl(
                experimentDecisionContextBuilder,
                promptTemplateBuilder,
                aiDecisionJsonParser,
                decisionGuardrailEvaluator,
                tongYiTextGenerationClient);
        AIDesignRequest request = new AIDesignRequest();
        request.setBusinessScenario("checkout");
        request.setTargetMetric("conversion");
        request.setConstraints(List.of("avoid regression"));
        AIDesignResponse expected = new AIDesignResponse();
        expected.setDecisionType(DecisionType.DESIGN.getCode());
        expected.setGuardrailStatus(GuardrailStatus.PASS.getCode());

        when(promptTemplateBuilder.buildDesignPrompt(request)).thenReturn("design-prompt");
        when(tongYiTextGenerationClient.generateText(anyString(), eq("design-prompt"), eq("AI实验设计")))
                .thenReturn("{\"decisionType\":\"DESIGN\"}");
        when(aiDecisionJsonParser.parseDesign(org.mockito.ArgumentMatchers.anyString())).thenReturn(expected);

        AIDesignResponse response = aiDecisionService.designExperiment(request);

        assertSame(expected, response);
        assertNotNull(response.getExperimentDraft());
        verify(promptTemplateBuilder).buildDesignPrompt(request);
        verify(tongYiTextGenerationClient).generateText(anyString(), eq("design-prompt"), eq("AI实验设计"));
        verify(aiDecisionJsonParser).parseDesign(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void diagnoseExperimentShouldOverrideGuardrailAndForceManualOnlyAction() {
        AIDecisionServiceImpl aiDecisionService = new AIDecisionServiceImpl(
                experimentDecisionContextBuilder,
                promptTemplateBuilder,
                aiDecisionJsonParser,
                decisionGuardrailEvaluator,
                tongYiTextGenerationClient);
        ExperimentDecisionContext context = new ExperimentDecisionContext();
        context.setExperimentId("exp_001");
        context.setExperimentName("新客首单优惠");
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
        when(aiDecisionJsonParser.parseDiagnosis(org.mockito.ArgumentMatchers.anyString())).thenReturn(expected);

        AIDiagnosisResponse response = aiDecisionService.diagnoseExperiment("exp_001");

        assertSame(expected, response);
        org.junit.jupiter.api.Assertions.assertEquals(GuardrailStatus.BLOCKED.getCode(), response.getGuardrailStatus());
        org.junit.jupiter.api.Assertions.assertEquals("MANUAL_ONLY",
                response.getRecommendedActions().get(0).getExecutionMode());
        verify(experimentDecisionContextBuilder).buildForExperiment("exp_001");
        verify(promptTemplateBuilder).buildDiagnosisPrompt(context);
        verify(decisionGuardrailEvaluator).evaluateDiagnosis(context);
        verify(decisionGuardrailEvaluator).collectRiskFlags(context);
        verify(tongYiTextGenerationClient).generateText(anyString(), eq("diagnosis-prompt"), eq("AI实验诊断"));
        verify(aiDecisionJsonParser).parseDiagnosis(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void decideGraduationShouldDowngradeBlockedResultToContinue() {
        AIDecisionServiceImpl aiDecisionService = new AIDecisionServiceImpl(
                experimentDecisionContextBuilder,
                promptTemplateBuilder,
                aiDecisionJsonParser,
                decisionGuardrailEvaluator,
                tongYiTextGenerationClient);
        ExperimentDecisionContext context = new ExperimentDecisionContext();
        context.setExperimentId("exp_001");
        context.setExperimentName("新客首单优惠");
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
        when(aiDecisionJsonParser.parseGraduation(org.mockito.ArgumentMatchers.anyString())).thenReturn(expected);

        AIGraduationDecisionResponse response = aiDecisionService.decideGraduation("exp_001");

        assertSame(expected, response);
        org.junit.jupiter.api.Assertions.assertEquals(GuardrailStatus.BLOCKED.getCode(), response.getGuardrailStatus());
        org.junit.jupiter.api.Assertions.assertEquals("CONTINUE", response.getDecision());
        verify(experimentDecisionContextBuilder).buildForExperiment("exp_001");
        verify(promptTemplateBuilder).buildGraduationPrompt(context);
        verify(decisionGuardrailEvaluator).evaluateGraduation(context);
        verify(decisionGuardrailEvaluator).collectRiskFlags(context);
        verify(tongYiTextGenerationClient).generateText(anyString(), eq("graduation-prompt"), eq("AI毕业决策"));
        verify(aiDecisionJsonParser).parseGraduation(org.mockito.ArgumentMatchers.anyString());
    }
}
