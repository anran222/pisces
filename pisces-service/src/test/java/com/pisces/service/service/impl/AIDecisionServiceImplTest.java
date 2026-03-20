package com.pisces.service.service.impl;

import com.pisces.common.request.AIDesignRequest;
import com.pisces.common.response.AIDesignResponse;
import com.pisces.common.response.AIDiagnosisResponse;
import com.pisces.common.response.AIGraduationDecisionResponse;
import com.pisces.service.ai.DecisionType;
import com.pisces.service.ai.ExperimentDecisionContextBuilder;
import com.pisces.service.ai.GuardrailStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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

    @Test
    void designExperimentShouldReturnDefaultDecision() {
        AIDecisionServiceImpl aiDecisionService = new AIDecisionServiceImpl(experimentDecisionContextBuilder);
        AIDesignRequest request = new AIDesignRequest();
        request.setBusinessScenario("checkout");
        request.setTargetMetric("conversion");
        request.setConstraints(List.of("avoid regression"));

        AIDesignResponse response = aiDecisionService.designExperiment(request);

        assertNotNull(response);
        assertEquals(DecisionType.DESIGN.getCode(), response.getDecisionType());
        assertEquals(GuardrailStatus.PASS.getCode(), response.getGuardrailStatus());
    }

    @Test
    void diagnoseExperimentShouldReturnDefaultDecision() {
        AIDecisionServiceImpl aiDecisionService = new AIDecisionServiceImpl(experimentDecisionContextBuilder);
        AIDiagnosisResponse response = aiDecisionService.diagnoseExperiment("exp_001");

        assertNotNull(response);
        assertEquals(DecisionType.DIAGNOSIS.getCode(), response.getDecisionType());
        assertEquals(GuardrailStatus.PASS.getCode(), response.getGuardrailStatus());
    }

    @Test
    void decideGraduationShouldReturnDefaultDecision() {
        AIDecisionServiceImpl aiDecisionService = new AIDecisionServiceImpl(experimentDecisionContextBuilder);
        AIGraduationDecisionResponse response = aiDecisionService.decideGraduation("exp_001");

        assertNotNull(response);
        assertEquals(DecisionType.GRADUATION.getCode(), response.getDecisionType());
        assertEquals(GuardrailStatus.PASS.getCode(), response.getGuardrailStatus());
        assertEquals("CONTINUE", response.getDecision());
    }
}
