package com.pisces.api.analysis;

import com.pisces.common.response.AIDesignResponse;
import com.pisces.service.service.AIDecisionService;
import com.pisces.service.service.AnalysisService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AnalysisControllerAIDesignTest {

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
    void shouldReturnStructuredAiDesign() throws Exception {
        AIDesignResponse response = new AIDesignResponse();
        response.setDecisionType("DESIGN");
        response.setSummary("AI实验设计草案: 二手手机详情页");
        response.setGuardrailStatus("PASS");

        when(aiDecisionService.designExperiment(org.mockito.ArgumentMatchers.any())).thenReturn(response);

        mockMvc.perform(post("/analysis/experiment/ai-design/v2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "businessScenario": "二手手机详情页",
                                  "targetMetric": "支付转化率",
                                  "constraints": ["保护毛利率"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.decisionType").value("DESIGN"))
                .andExpect(jsonPath("$.data.guardrailStatus").value("PASS"));
    }
}
