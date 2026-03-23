package com.pisces.service.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pisces.common.model.Experiment;
import com.pisces.common.model.ExperimentMetadata;
import com.pisces.common.model.EventDefinition;
import com.pisces.common.model.GroupConfigFieldDefinition;
import com.pisces.common.model.MetricDefinition;
import com.pisces.common.request.ExperimentCreateRequest;
import com.pisces.common.response.ExperimentResponse;
import com.pisces.service.rule.TrafficRuleEvaluator;
import com.pisces.service.schema.GroupConfigSchemaValidator;
import com.pisces.service.service.ConfigService;
import com.pisces.service.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExperimentServiceImplTest {

    @Mock
    private ConfigService configService;

    @InjectMocks
    private ExperimentServiceImpl experimentService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(experimentService, "trafficRuleEvaluator", new TrafficRuleEvaluator());
        ReflectionTestUtils.setField(experimentService, "groupConfigSchemaValidator",
                new GroupConfigSchemaValidator(new JsonUtil(new ObjectMapper())));
    }

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

    @Test
    void createExperimentShouldPersistGroupConfigSchemaAndNormalizedDefaults() throws Exception {
        ExperimentCreateRequest request = buildRequest("配置实验");
        request.setGroupConfigSchema(List.of(
                schemaField("mainTitle", "主标题", GroupConfigFieldDefinition.ValueType.STRING, true, "默认主标题"),
                schemaField("badgeCount", "角标数量", GroupConfigFieldDefinition.ValueType.INTEGER, false, 2),
                schemaField("showQualityBadge", "展示质检标签", GroupConfigFieldDefinition.ValueType.BOOLEAN, false, true),
                schemaField("extraMeta", "附加信息", GroupConfigFieldDefinition.ValueType.JSON, false, "{\"scene\":\"detail\"}")
        ));
        request.getGroups().get(0).setConfig(new LinkedHashMap<>(Map.of("mainTitle", "基准标题")));
        request.getGroups().get(1).setConfig(new LinkedHashMap<>(Map.of(
                "mainTitle", "实验标题",
                "badgeCount", "3",
                "showQualityBadge", "false"
        )));

        experimentService.createExperiment(request);

        ArgumentCaptor<ExperimentMetadata> captor = ArgumentCaptor.forClass(ExperimentMetadata.class);
        verify(configService).saveExperimentConfig(org.mockito.ArgumentMatchers.anyString(), captor.capture());

        ExperimentMetadata metadata = captor.getValue();
        assertThat(metadata.getGroupConfigSchema()).hasSize(4);
        assertThat(metadata.getGroups().get("A").getConfig())
                .containsEntry("mainTitle", "基准标题")
                .containsEntry("badgeCount", 2)
                .containsEntry("showQualityBadge", true);
        assertThat(metadata.getGroups().get("B").getConfig())
                .containsEntry("mainTitle", "实验标题")
                .containsEntry("badgeCount", 3)
                .containsEntry("showQualityBadge", false);
        assertThat(metadata.getGroups().get("A").getConfig().get("extraMeta"))
                .isInstanceOf(Map.class);
    }

    @Test
    void createExperimentShouldRejectInvalidSchemaTypedValue() {
        ExperimentCreateRequest request = buildRequest("非法配置实验");
        request.setGroupConfigSchema(List.of(
                schemaField("badgeCount", "角标数量", GroupConfigFieldDefinition.ValueType.INTEGER, true, null)
        ));
        request.getGroups().get(0).setConfig(Map.of("badgeCount", "abc"));
        request.getGroups().get(1).setConfig(Map.of("badgeCount", 2));

        assertThatThrownBy(() -> experimentService.createExperiment(request))
                .isInstanceOf(com.pisces.service.exception.BusinessException.class)
                .hasMessageContaining("badgeCount")
                .hasMessageContaining("INTEGER");
    }

    @Test
    void createExperimentShouldAllowNullSchemaDefaultValue() throws Exception {
        ExperimentCreateRequest request = buildRequest("空默认值配置实验");
        request.setGroupConfigSchema(List.of(
                schemaField("mainTitle", "主标题", GroupConfigFieldDefinition.ValueType.STRING, false, null)
        ));
        request.getGroups().get(0).setConfig(new LinkedHashMap<>(Map.of("mainTitle", "基准标题")));
        request.getGroups().get(1).setConfig(new LinkedHashMap<>(Map.of("mainTitle", "实验标题")));

        experimentService.createExperiment(request);

        ArgumentCaptor<ExperimentMetadata> captor = ArgumentCaptor.forClass(ExperimentMetadata.class);
        verify(configService).saveExperimentConfig(org.mockito.ArgumentMatchers.anyString(), captor.capture());
        assertThat(captor.getValue().getGroupConfigSchema().get(0).getDefaultValue()).isNull();
        assertThat(captor.getValue().getGroups().get("A").getConfig()).containsEntry("mainTitle", "基准标题");
    }

    @Test
    void createExperimentShouldAllowManualSchemaBelowAiGenerationMinimum() throws Exception {
        ExperimentCreateRequest request = buildRequest("手动保存精简配置实验");
        request.setGroupConfigSchema(List.of(
                schemaField("mainTitle", "主标题", GroupConfigFieldDefinition.ValueType.STRING, true, null),
                schemaField("subtitle", "副标题", GroupConfigFieldDefinition.ValueType.STRING, false, null)
        ));
        request.getGroups().get(0).setConfig(new LinkedHashMap<>(Map.of("mainTitle", "基准标题")));
        request.getGroups().get(1).setConfig(new LinkedHashMap<>(Map.of(
                "mainTitle", "实验标题",
                "subtitle", "平台补贴"
        )));

        experimentService.createExperiment(request);

        ArgumentCaptor<ExperimentMetadata> captor = ArgumentCaptor.forClass(ExperimentMetadata.class);
        verify(configService).saveExperimentConfig(org.mockito.ArgumentMatchers.anyString(), captor.capture());
        assertThat(captor.getValue().getGroupConfigSchema()).hasSize(2);
        assertThat(captor.getValue().getGroups().get("A").getConfig()).containsEntry("mainTitle", "基准标题");
        assertThat(captor.getValue().getGroups().get("B").getConfig())
                .containsEntry("mainTitle", "实验标题")
                .containsEntry("subtitle", "平台补贴");
    }

    @Test
    void createExperimentShouldRejectMissingEventDefinitions() {
        ExperimentCreateRequest request = buildRequest("缺少事件定义");
        request.setEventDefinitions(null);

        assertThatThrownBy(() -> experimentService.createExperiment(request))
                .isInstanceOf(com.pisces.service.exception.BusinessException.class)
                .hasMessageContaining("至少需要定义一个事件");
    }

    @Test
    void createExperimentShouldRejectMetricReferencingUndefinedEvent() {
        ExperimentCreateRequest request = buildRequest("非法指标事件");
        request.getMetricDefinitions().get(0).setNumeratorEventType("ORDER_SUBMITTED");

        assertThatThrownBy(() -> experimentService.createExperiment(request))
                .isInstanceOf(com.pisces.service.exception.BusinessException.class)
                .hasMessageContaining("ORDER_SUBMITTED")
                .hasMessageContaining("事件定义");
    }

    @Test
    void shouldNotKeepSingleUseSafeWrapperMethods() {
        List<String> methodNames = Arrays.stream(ExperimentServiceImpl.class.getDeclaredMethods())
                .map(Method::getName)
                .toList();

        assertThat(methodNames)
                .doesNotContain("pauseExperimentSafe")
                .doesNotContain("stopExperimentSafe")
                .doesNotContain("resumeExperimentSafe")
                .doesNotContain("deleteExperimentSafe");
    }

    @Test
    void getExperimentShouldExposeGroupConfigSchema() {
        Experiment experiment = new Experiment();
        experiment.setId("exp_schema_001");
        experiment.setName("配置实验");
        experiment.setStatus(Experiment.ExperimentStatus.DRAFT);

        com.pisces.common.model.ExperimentGroup group = new com.pisces.common.model.ExperimentGroup();
        group.setId("A");
        group.setName("基准组");
        group.setTrafficRatio(0.5);
        group.setConfig(new LinkedHashMap<>(Map.of("mainTitle", "基准标题")));

        ExperimentMetadata metadata = new ExperimentMetadata();
        metadata.setExperiment(experiment);
        metadata.setGroups(new LinkedHashMap<>(Map.of("A", group)));
        metadata.setGroupConfigSchema(List.of(
                schemaField("mainTitle", "主标题", GroupConfigFieldDefinition.ValueType.STRING, true, null)
        ));

        when(configService.getExperimentConfig("exp_schema_001")).thenReturn(metadata);
        metadata.setEventDefinitions(List.of(eventDefinition("PRODUCT_VIEW", "商品查看", true)));
        metadata.setMetricDefinitions(List.of(metricDefinition("PAY_RATE", "支付率",
                "PRODUCT_VIEW", "PRODUCT_VIEW", true, false)));

        ExperimentResponse response = experimentService.getExperiment("exp_schema_001");

        assertThat(response.getEventDefinitions()).hasSize(1);
        assertThat(response.getEventDefinitions().get(0).getKey()).isEqualTo("PRODUCT_VIEW");
        assertThat(response.getMetricDefinitions()).hasSize(1);
        assertThat(response.getMetricDefinitions().get(0).getKey()).isEqualTo("PAY_RATE");
        assertThat(response.getGroupConfigSchema()).hasSize(1);
        assertThat(response.getGroupConfigSchema().get(0).getKey()).isEqualTo("mainTitle");
        assertThat(response.getGroups().get("A").getConfig()).containsEntry("mainTitle", "基准标题");
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
        request.setEventDefinitions(List.of(
                eventDefinition("PRODUCT_VIEW", "商品查看", true),
                eventDefinition("PAY_SUCCESS", "支付成功", false)
        ));
        request.setMetricDefinitions(List.of(
                metricDefinition("PAY_RATE", "支付率", "PAY_SUCCESS", "PRODUCT_VIEW", true, false)
        ));
        return request;
    }

    private EventDefinition eventDefinition(String key, String label, boolean primary) {
        EventDefinition eventDefinition = new EventDefinition();
        eventDefinition.setKey(key);
        eventDefinition.setLabel(label);
        eventDefinition.setPrimary(primary);
        eventDefinition.setCategory("BUSINESS");
        return eventDefinition;
    }

    private MetricDefinition metricDefinition(String key, String name, String numeratorEventType,
                                              String denominatorEventType, boolean primaryMetric,
                                              boolean guardrailMetric) {
        MetricDefinition metricDefinition = new MetricDefinition();
        metricDefinition.setKey(key);
        metricDefinition.setName(name);
        metricDefinition.setAggregationType(MetricDefinition.AggregationType.RATE);
        metricDefinition.setNumeratorEventType(numeratorEventType);
        metricDefinition.setDenominatorType(MetricDefinition.DenominatorType.EVENT_COUNT);
        metricDefinition.setDenominatorEventType(denominatorEventType);
        metricDefinition.setPrimaryMetric(primaryMetric);
        metricDefinition.setGuardrailMetric(guardrailMetric);
        return metricDefinition;
    }

    private GroupConfigFieldDefinition schemaField(String key, String label,
                                                   GroupConfigFieldDefinition.ValueType valueType,
                                                   boolean required, Object defaultValue) {
        GroupConfigFieldDefinition fieldDefinition = new GroupConfigFieldDefinition();
        fieldDefinition.setKey(key);
        fieldDefinition.setLabel(label);
        fieldDefinition.setValueType(valueType);
        fieldDefinition.setRequired(required);
        fieldDefinition.setDefaultValue(defaultValue);
        return fieldDefinition;
    }
}
