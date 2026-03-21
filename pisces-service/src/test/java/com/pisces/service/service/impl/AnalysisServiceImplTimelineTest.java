package com.pisces.service.service.impl;

import com.pisces.common.model.Event;
import com.pisces.common.model.EventDefinition;
import com.pisces.common.model.Experiment;
import com.pisces.common.model.ExperimentGroup;
import com.pisces.common.model.ExperimentMetadata;
import com.pisces.common.model.MetricDefinition;
import com.pisces.service.config.TongYiConfig;
import com.pisces.service.service.BayesianAnalysisService;
import com.pisces.service.service.CausalInferenceService;
import com.pisces.service.service.ConfigService;
import com.pisces.service.service.DataService;
import com.pisces.service.service.HTEAnalysisService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalysisServiceImplTimelineTest {

    @Mock
    private ConfigService configService;

    @Mock
    private DataService dataService;

    @Mock
    private BayesianAnalysisService bayesianAnalysisService;

    @Mock
    private CausalInferenceService causalInferenceService;

    @Mock
    private HTEAnalysisService hteAnalysisService;

    @InjectMocks
    private AnalysisServiceImpl analysisService;

    @Test
    void getExperimentTimelineShouldAggregateRealEventDataByDay() {
        LocalDateTime start = LocalDateTime.now().truncatedTo(ChronoUnit.DAYS).minusDays(1);
        LocalDateTime dayOneViewTime = start.plusHours(1);
        LocalDateTime dayOneConvertTime = start.plusHours(2);
        LocalDateTime dayTwoViewTime = start.plusDays(1).plusHours(1);

        ExperimentMetadata metadata = new ExperimentMetadata();
        Experiment experiment = new Experiment();
        experiment.setId("exp_real_timeline");
        experiment.setName("真实时间线实验");
        experiment.setStartTime(start);
        metadata.setExperiment(experiment);

        ExperimentGroup groupA = new ExperimentGroup();
        groupA.setId("A");
        groupA.setName("A组");
        ExperimentGroup groupB = new ExperimentGroup();
        groupB.setId("B");
        groupB.setName("B组");
        metadata.setGroups(Map.of("A", groupA, "B", groupB));

        when(configService.getExperimentConfig("exp_real_timeline")).thenReturn(metadata);
        when(dataService.getEvents("exp_real_timeline", "A")).thenReturn(List.of(
                buildEvent("visitor-a1", "A", "VIEW", dayOneViewTime),
                buildEvent("visitor-a1", "A", "CONVERT", dayOneConvertTime),
                buildEvent("visitor-a2", "A", "VIEW", dayTwoViewTime)
        ));
        when(dataService.getEvents("exp_real_timeline", "B")).thenReturn(List.of(
                buildEvent("visitor-b1", "B", "VIEW", dayOneViewTime.plusHours(1))
        ));

        TongYiConfig tongYiConfig = new TongYiConfig();
        tongYiConfig.setEnabled(true);
        tongYiConfig.setApiKey("test-key");
        ReflectionTestUtils.setField(analysisService, "tongYiConfig", tongYiConfig);

        Map<String, Object> timeline = analysisService.getExperimentTimeline(
                "exp_real_timeline", "CONVERSION_RATE", "DAY");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dataPoints = (List<Map<String, Object>>) timeline.get("dataPoints");

        assertThat(dataPoints).isNotEmpty();
        assertThat(timeline.get("note")).asString().doesNotContain("模拟");

        Map<String, Object> dayOnePoint = dataPoints.stream()
                .filter(point -> ((LocalDateTime) point.get("timestamp")).toLocalDate().equals(start.toLocalDate()))
                .findFirst()
                .orElseThrow();

        Map<String, Object> dayTwoPoint = dataPoints.stream()
                .filter(point -> ((LocalDateTime) point.get("timestamp")).toLocalDate()
                        .equals(start.plusDays(1).toLocalDate()))
                .findFirst()
                .orElseThrow();

        @SuppressWarnings("unchecked")
        Map<String, Double> dayOneValues = (Map<String, Double>) dayOnePoint.get("values");
        @SuppressWarnings("unchecked")
        Map<String, Double> dayTwoValues = (Map<String, Double>) dayTwoPoint.get("values");

        assertThat(dayOneValues.get("A")).isEqualTo(1.0);
        assertThat(dayOneValues.get("B")).isEqualTo(0.0);
        assertThat(dayTwoValues.get("A")).isEqualTo(0.0);
    }

    @Test
    void getExperimentTimelineShouldUseCustomMetricDefinitionWhenMetricKeyProvided() {
        LocalDateTime start = LocalDateTime.now().truncatedTo(ChronoUnit.DAYS).minusDays(1);
        LocalDateTime dayOneViewTime = start.plusHours(1);
        LocalDateTime dayOnePayTime = start.plusHours(2);

        ExperimentMetadata metadata = new ExperimentMetadata();
        Experiment experiment = new Experiment();
        experiment.setId("exp_custom_metric_timeline");
        experiment.setName("自定义指标时间线实验");
        experiment.setStartTime(start);
        metadata.setExperiment(experiment);

        ExperimentGroup groupA = new ExperimentGroup();
        groupA.setId("A");
        groupA.setName("A组");
        metadata.setGroups(Map.of("A", groupA));
        metadata.setEventDefinitions(List.of(
                eventDefinition("PRODUCT_VIEW", "商品查看"),
                eventDefinition("PAY_SUCCESS", "支付成功")
        ));
        metadata.setMetricDefinitions(List.of(metricDefinition("PAYMENT_RATE", "PAY_SUCCESS", "PRODUCT_VIEW")));

        when(configService.getExperimentConfig("exp_custom_metric_timeline")).thenReturn(metadata);
        when(dataService.getEvents("exp_custom_metric_timeline", "A")).thenReturn(List.of(
                buildEvent("visitor-a1", "A", "PRODUCT_VIEW", dayOneViewTime),
                buildEvent("visitor-a1", "A", "PAY_SUCCESS", dayOnePayTime),
                buildEvent("visitor-a2", "A", "PRODUCT_VIEW", dayOneViewTime.plusHours(3))
        ));

        TongYiConfig tongYiConfig = new TongYiConfig();
        tongYiConfig.setEnabled(true);
        tongYiConfig.setApiKey("test-key");
        ReflectionTestUtils.setField(analysisService, "tongYiConfig", tongYiConfig);

        Map<String, Object> timeline = analysisService.getExperimentTimeline(
                "exp_custom_metric_timeline", "PAYMENT_RATE", "DAY");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dataPoints = (List<Map<String, Object>>) timeline.get("dataPoints");
        Map<String, Object> dayOnePoint = dataPoints.stream()
                .filter(point -> ((LocalDateTime) point.get("timestamp")).toLocalDate().equals(start.toLocalDate()))
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Double> dayOneValues = (Map<String, Double>) dayOnePoint.get("values");

        assertThat(dayOneValues.get("A")).isEqualTo(0.5);
    }

    private Event buildEvent(String visitorId, String groupId, String eventType, LocalDateTime timestamp) {
        Event event = new Event();
        event.setExperimentId("exp_real_timeline");
        event.setUserId(visitorId);
        event.setGroupId(groupId);
        event.setEventType(eventType);
        event.setTimestamp(timestamp);
        return event;
    }

    private EventDefinition eventDefinition(String key, String label) {
        EventDefinition definition = new EventDefinition();
        definition.setKey(key);
        definition.setLabel(label);
        return definition;
    }

    private MetricDefinition metricDefinition(String key, String numeratorEventType, String denominatorEventType) {
        MetricDefinition definition = new MetricDefinition();
        definition.setKey(key);
        definition.setName(key);
        definition.setAggregationType(MetricDefinition.AggregationType.RATE);
        definition.setNumeratorEventType(numeratorEventType);
        definition.setDenominatorType(MetricDefinition.DenominatorType.EVENT_COUNT);
        definition.setDenominatorEventType(denominatorEventType);
        definition.setPrimaryMetric(true);
        return definition;
    }
}
