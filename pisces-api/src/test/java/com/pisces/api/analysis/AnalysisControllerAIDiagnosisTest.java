package com.pisces.api.analysis;

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

        when(aiDecisionService.diagnoseExperiment("exp_1")).thenReturn(response);

        mockMvc.perform(get("/analysis/experiment/exp_1/ai-diagnosis"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.decisionType").value("DIAGNOSIS"))
                .andExpect(jsonPath("$.data.guardrailStatus").value("BLOCKED"))
                .andExpect(jsonPath("$.data.riskFlags[0]").value("SRM"));
    }
}
