package com.pisces.service.service.impl;

import com.pisces.common.model.ExperimentDecisionContext;
import com.pisces.common.model.ExperimentMetadata;
import com.pisces.common.response.AIDecisionEvidenceResponse;
import com.pisces.common.response.AIGraduationDecisionResponse;
import com.pisces.service.service.AIDecisionService;
import com.pisces.service.service.ConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
        AIDecisionEvidenceResponse evidence = new AIDecisionEvidenceResponse();
        evidence.setExperimentId("exp_1");
        evidence.setLatestReportSnapshotVersion(7);
        response.setEvidence(evidence);
        capturedExperimentId = null;
    }

    @Test
    void shouldReuseAiDecisionServiceForGraduation() {
        AnalysisServiceImpl analysisService = new AnalysisServiceImpl();
        AIDecisionService aiDecisionService = new StubAIDecisionService();
        ConfigService configService = mock(ConfigService.class);

        ReflectionTestUtils.setField(analysisService, "aiDecisionServiceProvider", new StubObjectProvider(aiDecisionService));
        ReflectionTestUtils.setField(analysisService, "configService", configService);
        when(configService.getExperimentConfig("exp_1")).thenReturn(new ExperimentMetadata());

        Map<String, Object> result = analysisService.autoGraduateDecision("exp_1");

        assertThat(result).containsEntry("experimentId", "exp_1");
        assertThat(result).containsEntry("guardrailStatus", "BLOCKED");
        assertThat(result).containsEntry("decision", "CONTINUE");
        assertThat(result).containsEntry("decisionType", "GRADUATION");
        assertThat(result.get("evidence")).isSameAs(response.getEvidence());
        assertThat(capturedExperimentId).isEqualTo("exp_1");
    }

    @Test
    void shouldNotKeepLegacyAiFallbackHelpers() {
        List<String> methodNames = Arrays.stream(AnalysisServiceImpl.class.getDeclaredMethods())
                .map(Method::getName)
                .toList();

        assertThat(methodNames)
                .doesNotContain("generateDataDrivenAnalysis")
                .doesNotContain("generateDefaultExperimentDesign");
    }

    @Test
    void shouldRejectCausalForestAtContractLevel() throws Exception {
        AnalysisServiceImpl analysisService = new AnalysisServiceImpl();
        Method method = AnalysisServiceImpl.class.getDeclaredMethod("validateCausalInputContract", String.class, Map.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) method.invoke(analysisService,
                "CAUSAL_FOREST", Collections.singletonMap("userFeatures", List.of("viewCount")));

        assertThat(result).isNotNull();
        assertThat(result).containsEntry("status", "BLOCKED");
        assertThat(result).containsEntry("reason", "当前仅支持 DID 和 PSM");
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

        @Override
        public AIGraduationDecisionResponse decideGraduation(ExperimentDecisionContext context) {
            capturedExperimentId = context == null ? null : context.getExperimentId();
            return response;
        }
    }

    private record StubObjectProvider(AIDecisionService aiDecisionService) implements ObjectProvider<AIDecisionService> {

        @Override
        public AIDecisionService getObject(Object... args) {
            return aiDecisionService;
        }

        @Override
        public AIDecisionService getIfAvailable() {
            return aiDecisionService;
        }

        @Override
        public AIDecisionService getIfUnique() {
            return aiDecisionService;
        }

        @Override
        public AIDecisionService getObject() {
            return aiDecisionService;
        }
    }
}
