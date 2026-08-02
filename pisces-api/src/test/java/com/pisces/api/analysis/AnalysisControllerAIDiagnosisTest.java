package com.pisces.api.analysis;

import com.pisces.common.response.AIDecisionEvidenceResponse;
import com.pisces.common.response.AIDiagnosisResponse;
import com.pisces.service.service.AIDecisionService;
import com.pisces.service.service.AnalysisService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AnalysisControllerAIDiagnosisTest {

    private AnalysisService analysisService;
    private AIDecisionService aiDecisionService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        analysisService = mock(AnalysisService.class);
        aiDecisionService = mock(AIDecisionService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AnalysisController(analysisService, aiDecisionService)).build();
    }

    @Test
    void shouldReturnAiDiagnosis() throws Exception {
        AIDiagnosisResponse response = new AIDiagnosisResponse();
        response.setDecisionType("DIAGNOSIS");
        response.setSummary("AI实验诊断草案: 新客首单优惠");
        response.setGuardrailStatus("BLOCKED");
        response.setRiskFlags(List.of("SRM"));
        AIDecisionEvidenceResponse evidence = new AIDecisionEvidenceResponse();
        evidence.setExperimentId("exp_1");
        evidence.setAnalysisReady(false);
        evidence.setBlockingIssues(List.of("样本量不足"));
        evidence.setLatestReportSnapshotVersion(7);
        response.setEvidence(evidence);

        when(aiDecisionService.diagnoseExperiment("exp_1")).thenReturn(response);

        mockMvc.perform(get("/analysis/experiment/exp_1/ai-diagnosis"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.decisionType").value("DIAGNOSIS"))
                .andExpect(jsonPath("$.data.guardrailStatus").value("BLOCKED"))
                .andExpect(jsonPath("$.data.riskFlags[0]").value("SRM"))
                .andExpect(jsonPath("$.data.evidence.experimentId").value("exp_1"))
                .andExpect(jsonPath("$.data.evidence.analysisReady").value(false))
                .andExpect(jsonPath("$.data.evidence.latestReportSnapshotVersion").value(7))
                .andExpect(jsonPath("$.data.evidence.blockingIssues[0]").value("样本量不足"));
    }
}
