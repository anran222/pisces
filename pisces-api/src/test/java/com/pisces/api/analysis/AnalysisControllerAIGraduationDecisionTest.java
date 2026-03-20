package com.pisces.api.analysis;

import com.pisces.common.response.AIGraduationDecisionResponse;
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

class AnalysisControllerAIGraduationDecisionTest {

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
    void shouldReturnAiGraduationDecision() throws Exception {
        AIGraduationDecisionResponse response = new AIGraduationDecisionResponse();
        response.setDecisionType("GRADUATION");
        response.setSummary("AI毕业决策草案: 新客首单优惠");
        response.setGuardrailStatus("PASS");
        response.setDecision("CONTINUE");
        response.setRiskFlags(List.of());

        when(aiDecisionService.decideGraduation("exp_1")).thenReturn(response);

        mockMvc.perform(get("/analysis/experiment/exp_1/ai-graduation-decision"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.decisionType").value("GRADUATION"))
                .andExpect(jsonPath("$.data.guardrailStatus").value("PASS"))
                .andExpect(jsonPath("$.data.decision").value("CONTINUE"));
    }
}
