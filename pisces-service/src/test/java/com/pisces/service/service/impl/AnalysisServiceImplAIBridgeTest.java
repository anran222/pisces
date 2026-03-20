package com.pisces.service.service.impl;

import com.pisces.common.response.AIGraduationDecisionResponse;
import com.pisces.service.service.AIDecisionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AnalysisService AI桥接测试
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/3/20 14:45
 */
class AnalysisServiceImplAIBridgeTest {

    private AIGraduationDecisionResponse response;

    private String capturedExperimentId;

    @BeforeEach
    void setUp() {
        response = new AIGraduationDecisionResponse();
        response.setDecisionType("GRADUATION");
        response.setGuardrailStatus("BLOCKED");
        response.setDecision("CONTINUE");
        response.setConfidence(0.78D);
        response.setRiskFlags(List.of("SRM"));
        capturedExperimentId = null;
    }

    @Test
    void shouldReuseAiDecisionServiceForGraduation() {
        AnalysisServiceImpl analysisService = new AnalysisServiceImpl();
        AIDecisionService aiDecisionService = new StubAIDecisionService();

        ReflectionTestUtils.setField(analysisService, "aiDecisionService", aiDecisionService);

        Map<String, Object> result = analysisService.autoGraduateDecision("exp_1");

        assertThat(result).containsEntry("experimentId", "exp_1");
        assertThat(result).containsEntry("guardrailStatus", "BLOCKED");
        assertThat(result).containsEntry("decision", "CONTINUE");
        assertThat(result).containsEntry("decisionType", "GRADUATION");
        assertThat(capturedExperimentId).isEqualTo("exp_1");
    }

    private class StubAIDecisionService implements AIDecisionService {

        @Override
        public com.pisces.common.response.AIDesignResponse designExperiment(
                com.pisces.common.request.AIDesignRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.pisces.common.response.AIDiagnosisResponse diagnoseExperiment(String experimentId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AIGraduationDecisionResponse decideGraduation(String experimentId) {
            capturedExperimentId = experimentId;
            return response;
        }
    }
}
