package com.pisces.service.service.impl;

import com.pisces.common.model.EventDefinition;
import com.pisces.common.model.Experiment;
import com.pisces.common.model.ExperimentGroup;
import com.pisces.common.model.ExperimentMetadata;
import com.pisces.common.model.MetricDefinition;
import com.pisces.common.request.ExperimentCreateRequest;
import com.pisces.service.service.ConfigService;
import com.pisces.service.service.DataService;
import com.pisces.service.service.ExperimentService;
import com.pisces.service.service.TrafficService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExperimentDataGeneratorServiceImplTest {

    @Mock
    private ExperimentService experimentService;

    @Mock
    private TrafficService trafficService;

    @Mock
    private DataService dataService;

    @Mock
    private ConfigService configService;

    @InjectMocks
    private ExperimentDataGeneratorServiceImpl experimentDataGeneratorService;

    @Test
    void generateCompleteExperimentDataShouldCreateExperimentWithDefinitions() {
        when(experimentService.createExperiment(org.mockito.ArgumentMatchers.any(ExperimentCreateRequest.class)))
                .thenReturn(experiment("exp_generated"));
        AtomicInteger sequence = new AtomicInteger();
        when(trafficService.assignGroup(eq("exp_generated"), anyString()))
                .thenAnswer(invocation -> List.of("A", "B", "C", "D").get(sequence.getAndIncrement() % 4));

        experimentDataGeneratorService.generateCompleteExperimentData("自动实验", 1, 3);

        ArgumentCaptor<ExperimentCreateRequest> requestCaptor = ArgumentCaptor.forClass(ExperimentCreateRequest.class);
        verify(experimentService).createExperiment(requestCaptor.capture());
        ExperimentCreateRequest request = requestCaptor.getValue();

        assertThat(request.getEventDefinitions())
                .extracting(EventDefinition::getKey)
                .containsExactly("PRODUCT_VIEW", "CONSULT_CLICK", "PAY_SUCCESS");
        assertThat(request.getMetricDefinitions())
                .extracting(MetricDefinition::getKey)
                .containsExactly("PAYMENT_RATE", "CONSULT_RATE");
        assertThat(request.getGroupConfigSchema())
                .extracting(field -> field.getKey())
                .containsExactly("titleTemplate", "showMarketPrice", "showQualityReport", "trustElements");
    }

    @Test
    void generateDataForExistingExperimentShouldUseConfiguredEventDefinitions() {
        ExperimentMetadata metadata = new ExperimentMetadata();
        Map<String, ExperimentGroup> groups = new LinkedHashMap<>();
        groups.put("A", group("A"));
        groups.put("B", group("B"));
        metadata.setGroups(groups);
        metadata.setEventDefinitions(List.of(
                eventDefinition("PRODUCT_VIEW", true),
                eventDefinition("CONSULT_CLICK", false),
                eventDefinition("PAY_SUCCESS", false)
        ));
        metadata.setMetricDefinitions(List.of(metricDefinition("PAYMENT_RATE", "PAY_SUCCESS", "PRODUCT_VIEW")));
        when(configService.getExperimentConfig("exp_existing")).thenReturn(metadata);

        AtomicInteger sequence = new AtomicInteger();
        when(trafficService.assignGroup(eq("exp_existing"), anyString()))
                .thenAnswer(invocation -> sequence.getAndIncrement() % 2 == 0 ? "A" : "B");

        experimentDataGeneratorService.generateDataForExistingExperiment("exp_existing", 1, 2);

        ArgumentCaptor<String> eventTypeCaptor = ArgumentCaptor.forClass(String.class);
        verify(dataService, atLeastOnce())
                .reportEvent(eq("exp_existing"), anyString(), eventTypeCaptor.capture(), anyString(), anyMap());
        assertThat(eventTypeCaptor.getAllValues()).contains("PRODUCT_VIEW");
        assertThat(eventTypeCaptor.getAllValues()).doesNotContain("VIEW");
    }

    private Experiment experiment(String experimentId) {
        Experiment experiment = new Experiment();
        experiment.setId(experimentId);
        return experiment;
    }

    private ExperimentGroup group(String id) {
        ExperimentGroup groupConfig = new ExperimentGroup();
        groupConfig.setId(id);
        groupConfig.setName(id);
        groupConfig.setTrafficRatio(0.5D);
        groupConfig.setConfig(Map.of());
        return groupConfig;
    }

    private EventDefinition eventDefinition(String key, boolean primary) {
        EventDefinition eventDefinition = new EventDefinition();
        eventDefinition.setKey(key);
        eventDefinition.setLabel(key);
        eventDefinition.setCategory("BUSINESS");
        eventDefinition.setPrimary(primary);
        return eventDefinition;
    }

    private MetricDefinition metricDefinition(String key, String numeratorEventType, String denominatorEventType) {
        MetricDefinition metricDefinition = new MetricDefinition();
        metricDefinition.setKey(key);
        metricDefinition.setName(key);
        metricDefinition.setAggregationType(MetricDefinition.AggregationType.RATE);
        metricDefinition.setNumeratorEventType(numeratorEventType);
        metricDefinition.setDenominatorType(MetricDefinition.DenominatorType.EVENT_COUNT);
        metricDefinition.setDenominatorEventType(denominatorEventType);
        metricDefinition.setPrimaryMetric(true);
        return metricDefinition;
    }
}
