package com.pisces.api.experiment;

import com.pisces.service.service.ExperimentDataGeneratorService;
import com.pisces.service.service.ExperimentDemoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ExperimentDataGeneratorControllerTest {

    private ExperimentDemoService experimentDemoService;
    private ExperimentDataGeneratorService experimentDataGeneratorService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        experimentDemoService = mock(ExperimentDemoService.class);
        experimentDataGeneratorService = mock(ExperimentDataGeneratorService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                new ExperimentDataGeneratorController(experimentDemoService, experimentDataGeneratorService)).build();
    }

    @Test
    void shouldGenerateDataForExistingExperiment() throws Exception {
        mockMvc.perform(post("/experiments/generator/exp_001/simulate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "visitorCount": 120,
                                  "daysAgo": 5
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("实验数据生成完成"));

        verify(experimentDataGeneratorService).generateDataForExistingExperiment(eq("exp_001"), eq(120), eq(5));
    }

    @Test
    void shouldUseDefaultGenerateParametersWhenRequestBodyMissing() throws Exception {
        mockMvc.perform(post("/experiments/generator/exp_002/simulate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("实验数据生成完成"));

        verify(experimentDataGeneratorService).generateDataForExistingExperiment(eq("exp_002"), eq(150), eq(7));
    }

    @Test
    void shouldReturnAiDecisionFieldsForDemoExperiment() throws Exception {
        ExperimentDemoService.ExperimentDemoResult demoResult = new ExperimentDemoService.ExperimentDemoResult();
        demoResult.setQualifiedExperiment(buildDemoCaseResult("exp_pass", true, "GRADUATE"));
        demoResult.setUnqualifiedExperiment(buildDemoCaseResult("exp_fail", false, "CONTINUE"));
        when(experimentDemoService.generateUsedPhoneDemo()).thenReturn(demoResult);

        mockMvc.perform(post("/experiments/generator/demo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.qualifiedExperiment.aiDecision").value("GRADUATE"))
                .andExpect(jsonPath("$.data.qualifiedExperiment.aiGuardrailStatus").value("PASS"))
                .andExpect(jsonPath("$.data.qualifiedExperiment.primaryMetricKey").value("PAYMENT_RATE"))
                .andExpect(jsonPath("$.data.qualifiedExperiment.groupCount").value(4))
                .andExpect(jsonPath("$.data.qualifiedExperiment.schemaFieldCount").value(6))
                .andExpect(jsonPath("$.data.unqualifiedExperiment.aiDecision").value("CONTINUE"));
    }

    private ExperimentDemoService.ExperimentCaseResult buildDemoCaseResult(String experimentId,
                                                                           boolean canGraduate,
                                                                           String aiDecision) {
        ExperimentDemoService.ExperimentCaseResult caseResult = new ExperimentDemoService.ExperimentCaseResult();
        caseResult.setExperimentId(experimentId);
        caseResult.setExperimentName("示例实验 " + experimentId);
        caseResult.setCanGraduate(canGraduate);
        caseResult.setCanStop(false);
        caseResult.setAiDecision(aiDecision);
        caseResult.setAiGuardrailStatus("PASS");
        caseResult.setAiSummary("AI 决策摘要");
        caseResult.setPrimaryMetricKey("PAYMENT_RATE");
        caseResult.setGroupCount(4);
        caseResult.setSchemaFieldCount(6);
        return caseResult;
    }
}
