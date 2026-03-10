package com.pisces.service.service.impl;

import com.pisces.common.model.Experiment;
import com.pisces.common.model.ExperimentMetadata;
import com.pisces.common.request.ExperimentCreateRequest;
import com.pisces.service.service.ConfigService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExperimentServiceImplTest {

    @Mock
    private ConfigService configService;

    @InjectMocks
    private ExperimentServiceImpl experimentService;

    @Test
    void createExperimentShouldInitializeConfigVersion() throws Exception {
        ExperimentCreateRequest request = buildRequest("创建实验");

        experimentService.createExperiment(request);

        ArgumentCaptor<ExperimentMetadata> captor = ArgumentCaptor.forClass(ExperimentMetadata.class);
        verify(configService).saveExperimentConfig(org.mockito.ArgumentMatchers.anyString(), captor.capture());

        assertThat(captor.getValue().getConfigVersion()).isEqualTo(1L);
    }

    @Test
    void updateExperimentShouldIncrementConfigVersion() throws Exception {
        ExperimentMetadata metadata = new ExperimentMetadata();
        metadata.setConfigVersion(3L);

        Experiment experiment = new Experiment();
        experiment.setId("exp_test_001");
        experiment.setName("旧实验");
        experiment.setStatus(Experiment.ExperimentStatus.DRAFT);
        experiment.setCreateTime(LocalDateTime.now().minusDays(1));
        metadata.setExperiment(experiment);

        when(configService.getExperimentConfig("exp_test_001")).thenReturn(metadata);

        experimentService.updateExperiment("exp_test_001", buildRequest("新实验"));

        ArgumentCaptor<ExperimentMetadata> captor = ArgumentCaptor.forClass(ExperimentMetadata.class);
        verify(configService).saveExperimentConfig(org.mockito.ArgumentMatchers.eq("exp_test_001"), captor.capture());

        assertThat(captor.getValue().getConfigVersion()).isEqualTo(4L);
    }

    private ExperimentCreateRequest buildRequest(String name) {
        ExperimentCreateRequest request = new ExperimentCreateRequest();
        request.setName(name);
        request.setDescription("desc");
        request.setStartTime(LocalDateTime.now().plusHours(1));
        request.setEndTime(LocalDateTime.now().plusDays(7));

        ExperimentCreateRequest.GroupConfig groupA = new ExperimentCreateRequest.GroupConfig();
        groupA.setId("A");
        groupA.setName("基准组");
        groupA.setTrafficRatio(0.5);

        ExperimentCreateRequest.GroupConfig groupB = new ExperimentCreateRequest.GroupConfig();
        groupB.setId("B");
        groupB.setName("变体组");
        groupB.setTrafficRatio(0.5);
        request.setGroups(List.of(groupA, groupB));

        ExperimentCreateRequest.GroupAllocationRequest allocationA =
                new ExperimentCreateRequest.GroupAllocationRequest();
        allocationA.setGroup("A");
        allocationA.setRatio(0.5);

        ExperimentCreateRequest.GroupAllocationRequest allocationB =
                new ExperimentCreateRequest.GroupAllocationRequest();
        allocationB.setGroup("B");
        allocationB.setRatio(0.5);

        ExperimentCreateRequest.TrafficConfigRequest traffic = new ExperimentCreateRequest.TrafficConfigRequest();
        traffic.setTotalTraffic(1.0);
        traffic.setStrategy("HASH");
        traffic.setAllocation(List.of(allocationA, allocationB));
        request.setTraffic(traffic);
        return request;
    }
}
