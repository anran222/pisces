package com.pisces.api.analysis;

import com.pisces.common.request.EventReplayPlanRequest;
import com.pisces.common.response.EventPipelineOperationResponse;
import com.pisces.service.service.AIDecisionService;
import com.pisces.service.service.AnalysisService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AnalysisControllerEventReplayContractTest {

    private AnalysisService analysisService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        analysisService = mock(AnalysisService.class);
        AIDecisionService aiDecisionService = mock(AIDecisionService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AnalysisController(analysisService, aiDecisionService)).build();
    }

    @Test
    void shouldRouteSegmentedMaterializationRepair() throws Exception {
        EventPipelineOperationResponse response = new EventPipelineOperationResponse();
        response.setExperimentId("exp_1");
        response.setOperation("REPAIR_MATERIALIZATION");
        response.setStatus("SUCCESS");
        response.setMessage("分段 segment-001 缺失派生物化账本已修复");

        when(analysisService.repairEventMaterializationSegment(
                eq("exp_1"),
                any(EventReplayPlanRequest.class),
                eq(1),
                eq("ops")))
                .thenReturn(response);

        mockMvc.perform(post("/analysis/experiment/exp_1/events/replay/materialization/repair/segments/1")
                        .queryParam("operator", "ops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "startTime": "2026-07-30T00:00:00",
                                  "endTime": "2026-07-30T01:00:00",
                                  "eventTypes": ["PAY_SUCCESS"],
                                  "includeEvents": true,
                                  "includeExposures": false,
                                  "segmentCount": 2
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.experimentId").value("exp_1"))
                .andExpect(jsonPath("$.data.operation").value("REPAIR_MATERIALIZATION"))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));

        ArgumentCaptor<EventReplayPlanRequest> requestCaptor =
                ArgumentCaptor.forClass(EventReplayPlanRequest.class);
        verify(analysisService).repairEventMaterializationSegment(
                eq("exp_1"),
                requestCaptor.capture(),
                eq(1),
                eq("ops"));
        EventReplayPlanRequest request = requestCaptor.getValue();
        assertEquals(LocalDateTime.of(2026, 7, 30, 0, 0), request.getStartTime());
        assertEquals(LocalDateTime.of(2026, 7, 30, 1, 0), request.getEndTime());
        assertEquals(List.of("PAY_SUCCESS"), request.getEventTypes());
        assertEquals(Boolean.TRUE, request.getIncludeEvents());
        assertEquals(Boolean.FALSE, request.getIncludeExposures());
        assertEquals(2, request.getSegmentCount());
    }
}
