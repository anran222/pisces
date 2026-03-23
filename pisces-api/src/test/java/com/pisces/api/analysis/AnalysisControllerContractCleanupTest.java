package com.pisces.api.analysis;

import com.pisces.service.service.AIDecisionService;
import com.pisces.service.service.AnalysisService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.mock;

class AnalysisControllerContractCleanupTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AnalysisService analysisService = mock(AnalysisService.class);
        AIDecisionService aiDecisionService = mock(AIDecisionService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AnalysisController(analysisService, aiDecisionService)).build();
    }

    @Test
    void shouldNotExposeHteEndpoint() throws Exception {
        mockMvc.perform(post("/analysis/experiment/exp_1/hte")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[\"viewCount\"]"))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldNotExposeSensitiveGroupsEndpoint() throws Exception {
        mockMvc.perform(post("/analysis/experiment/exp_1/sensitive-groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[\"viewCount\"]"))
                .andExpect(status().isNotFound());
    }
}
