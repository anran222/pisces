package com.pisces.api.runtime;

import com.pisces.common.model.EventDefinition;
import com.pisces.common.model.GroupConfigFieldDefinition;
import com.pisces.common.model.MetricDefinition;
import com.pisces.common.response.RuntimeExperimentConfigResponse;
import com.pisces.common.response.RuntimeExperimentConfigVersionResponse;
import com.pisces.service.service.RuntimeConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.empty;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RuntimeConfigControllerContractTest {

    private static final int SUCCESS_CODE = 200;
    private static final long CONFIG_VERSION = 7L;
    private static final long KNOWN_VERSION = 6L;
    private static final long WAIT_MILLIS = 250L;
    private static final double TOTAL_TRAFFIC = 0.50D;
    private static final double GROUP_TRAFFIC_RATIO = 0.25D;
    private static final String SUCCESS_MESSAGE = "操作成功";
    private static final String EXPERIMENT_ID = "exp_runtime_contract";
    private static final String EXPERIMENT_NAME = "运行时契约实验";
    private static final String EXPERIMENT_DESCRIPTION = "验证 SDK 运行时配置 HTTP 契约";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String EVENT_KEY = "PRODUCT_VIEW";
    private static final String EVENT_LABEL = "商品浏览";
    private static final String EVENT_CATEGORY = "engagement";
    private static final String METRIC_KEY = "PAYMENT_RATE";
    private static final String METRIC_NAME = "支付转化率";
    private static final String SCHEMA_KEY = "discountRate";
    private static final String SCHEMA_LABEL = "折扣率";
    private static final String GROUP_ID = "group_b";
    private static final String GROUP_NAME = "实验组B";
    private static final String DISCOUNT_VALUE = "15%";
    private static final String TRAFFIC_STRATEGY = "HASH";
    private static final String TRAFFIC_HASH_KEY = "visitorId";

    private RuntimeConfigService runtimeConfigService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        runtimeConfigService = mock(RuntimeConfigService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new RuntimeConfigController(runtimeConfigService)).build();
    }

    @Test
    void getExperimentConfigShouldReturnRuntimeContractShape() throws Exception {
        RuntimeExperimentConfigResponse response = runtimeConfigResponse();
        when(runtimeConfigService.getExperimentConfig(EXPERIMENT_ID)).thenReturn(response);

        mockMvc.perform(get("/runtime/experiments/{experimentId}/config", EXPERIMENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(SUCCESS_CODE))
                .andExpect(jsonPath("$.message").value(SUCCESS_MESSAGE))
                .andExpect(jsonPath("$.data.id").value(EXPERIMENT_ID))
                .andExpect(jsonPath("$.data.name").value(EXPERIMENT_NAME))
                .andExpect(jsonPath("$.data.description").value(EXPERIMENT_DESCRIPTION))
                .andExpect(jsonPath("$.data.status").value(STATUS_RUNNING))
                .andExpect(jsonPath("$.data.configVersion").value(CONFIG_VERSION))
                .andExpect(jsonPath("$.data.eventDefinitions[0].key").value(EVENT_KEY))
                .andExpect(jsonPath("$.data.eventDefinitions[0].label").value(EVENT_LABEL))
                .andExpect(jsonPath("$.data.metricDefinitions[0].key").value(METRIC_KEY))
                .andExpect(jsonPath("$.data.metricDefinitions[0].name").value(METRIC_NAME))
                .andExpect(jsonPath("$.data.groupConfigSchema[0].key").value(SCHEMA_KEY))
                .andExpect(jsonPath("$.data.groupConfigSchema[0].label").value(SCHEMA_LABEL))
                .andExpect(jsonPath("$.data.groups.group_b.id").value(GROUP_ID))
                .andExpect(jsonPath("$.data.groups.group_b.name").value(GROUP_NAME))
                .andExpect(jsonPath("$.data.groups.group_b.config.discountRate").value(DISCOUNT_VALUE))
                .andExpect(jsonPath("$.data.traffic.strategy").value(TRAFFIC_STRATEGY))
                .andExpect(jsonPath("$.data.traffic.hashKey").value(TRAFFIC_HASH_KEY))
                .andExpect(jsonPath("$.data.traffic.totalTraffic").value(TOTAL_TRAFFIC))
                .andExpect(jsonPath("$.data.traffic.allocation[0].group").value(GROUP_ID))
                .andExpect(jsonPath("$.data.traffic.allocation[0].ratio").value(GROUP_TRAFFIC_RATIO));

        verify(runtimeConfigService).getExperimentConfig(EXPERIMENT_ID);
    }

    @Test
    void getExperimentConfigShouldPreserveEmptyCollectionsInHttpResponse() throws Exception {
        RuntimeExperimentConfigResponse response = emptyRuntimeConfigResponse();
        when(runtimeConfigService.getExperimentConfig(EXPERIMENT_ID)).thenReturn(response);

        mockMvc.perform(get("/runtime/experiments/{experimentId}/config", EXPERIMENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eventDefinitions").value(empty()))
                .andExpect(jsonPath("$.data.metricDefinitions").value(empty()))
                .andExpect(jsonPath("$.data.groupConfigSchema").value(empty()))
                .andExpect(jsonPath("$.data.groups").value(anEmptyMap()));

        verify(runtimeConfigService).getExperimentConfig(EXPERIMENT_ID);
    }

    @Test
    void getExperimentConfigVersionShouldBindKnownVersionAndWaitMillis() throws Exception {
        RuntimeExperimentConfigVersionResponse response = versionResponse(KNOWN_VERSION);
        when(runtimeConfigService.getExperimentConfigVersion(EXPERIMENT_ID, KNOWN_VERSION, WAIT_MILLIS))
                .thenReturn(response);

        mockMvc.perform(get("/runtime/experiments/{experimentId}/config/version", EXPERIMENT_ID)
                        .param("knownVersion", String.valueOf(KNOWN_VERSION))
                        .param("waitMillis", String.valueOf(WAIT_MILLIS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(SUCCESS_CODE))
                .andExpect(jsonPath("$.data.experimentId").value(EXPERIMENT_ID))
                .andExpect(jsonPath("$.data.knownVersion").value(KNOWN_VERSION))
                .andExpect(jsonPath("$.data.currentVersion").value(CONFIG_VERSION))
                .andExpect(jsonPath("$.data.changed").value(true))
                .andExpect(jsonPath("$.data.status").value(STATUS_RUNNING));

        verify(runtimeConfigService).getExperimentConfigVersion(EXPERIMENT_ID, KNOWN_VERSION, WAIT_MILLIS);
    }

    @Test
    void getExperimentConfigVersionShouldAllowMissingOptionalQueryParams() throws Exception {
        RuntimeExperimentConfigVersionResponse response = versionResponse(null);
        when(runtimeConfigService.getExperimentConfigVersion(EXPERIMENT_ID, null, null)).thenReturn(response);

        mockMvc.perform(get("/runtime/experiments/{experimentId}/config/version", EXPERIMENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.experimentId").value(EXPERIMENT_ID))
                .andExpect(jsonPath("$.data.currentVersion").value(CONFIG_VERSION))
                .andExpect(jsonPath("$.data.changed").value(true));

        verify(runtimeConfigService).getExperimentConfigVersion(EXPERIMENT_ID, null, null);
    }

    private RuntimeExperimentConfigResponse runtimeConfigResponse() {
        RuntimeExperimentConfigResponse response = new RuntimeExperimentConfigResponse();
        response.setId(EXPERIMENT_ID);
        response.setName(EXPERIMENT_NAME);
        response.setDescription(EXPERIMENT_DESCRIPTION);
        response.setStatus(STATUS_RUNNING);
        response.setConfigVersion(CONFIG_VERSION);
        response.setEventDefinitions(List.of(eventDefinition()));
        response.setMetricDefinitions(List.of(metricDefinition()));
        response.setGroupConfigSchema(List.of(groupConfigFieldDefinition()));
        response.setGroups(groupConfigs());
        response.setTraffic(trafficConfig());
        return response;
    }

    private RuntimeExperimentConfigResponse emptyRuntimeConfigResponse() {
        RuntimeExperimentConfigResponse response = new RuntimeExperimentConfigResponse();
        response.setId(EXPERIMENT_ID);
        response.setName(EXPERIMENT_NAME);
        response.setDescription(EXPERIMENT_DESCRIPTION);
        response.setStatus(STATUS_RUNNING);
        response.setConfigVersion(CONFIG_VERSION);
        response.setEventDefinitions(Collections.emptyList());
        response.setMetricDefinitions(Collections.emptyList());
        response.setGroupConfigSchema(Collections.emptyList());
        response.setGroups(Collections.emptyMap());
        return response;
    }

    private EventDefinition eventDefinition() {
        EventDefinition eventDefinition = new EventDefinition();
        eventDefinition.setKey(EVENT_KEY);
        eventDefinition.setLabel(EVENT_LABEL);
        eventDefinition.setDescription(EXPERIMENT_DESCRIPTION);
        eventDefinition.setCategory(EVENT_CATEGORY);
        eventDefinition.setPrimary(true);
        return eventDefinition;
    }

    private MetricDefinition metricDefinition() {
        MetricDefinition metricDefinition = new MetricDefinition();
        metricDefinition.setKey(METRIC_KEY);
        metricDefinition.setName(METRIC_NAME);
        metricDefinition.setDescription(EXPERIMENT_DESCRIPTION);
        metricDefinition.setAggregationType(MetricDefinition.AggregationType.RATE);
        metricDefinition.setDenominatorType(MetricDefinition.DenominatorType.ASSIGNMENT_COUNT);
        metricDefinition.setNumeratorEventType(EVENT_KEY);
        metricDefinition.setPrimaryMetric(true);
        metricDefinition.setGuardrailMetric(false);
        return metricDefinition;
    }

    private GroupConfigFieldDefinition groupConfigFieldDefinition() {
        GroupConfigFieldDefinition fieldDefinition = new GroupConfigFieldDefinition();
        fieldDefinition.setKey(SCHEMA_KEY);
        fieldDefinition.setLabel(SCHEMA_LABEL);
        fieldDefinition.setValueType(GroupConfigFieldDefinition.ValueType.STRING);
        fieldDefinition.setRequired(true);
        fieldDefinition.setDescription(EXPERIMENT_DESCRIPTION);
        fieldDefinition.setDefaultValue(DISCOUNT_VALUE);
        return fieldDefinition;
    }

    private Map<String, RuntimeExperimentConfigResponse.GroupConfigResponse> groupConfigs() {
        RuntimeExperimentConfigResponse.GroupConfigResponse groupConfig =
                new RuntimeExperimentConfigResponse.GroupConfigResponse();
        groupConfig.setId(GROUP_ID);
        groupConfig.setName(GROUP_NAME);
        groupConfig.setTrafficRatio(GROUP_TRAFFIC_RATIO);
        groupConfig.setConfig(Map.of(SCHEMA_KEY, DISCOUNT_VALUE));
        Map<String, RuntimeExperimentConfigResponse.GroupConfigResponse> groups = new LinkedHashMap<>();
        groups.put(GROUP_ID, groupConfig);
        return groups;
    }

    private RuntimeExperimentConfigResponse.TrafficConfigResponse trafficConfig() {
        RuntimeExperimentConfigResponse.GroupAllocationResponse allocation =
                new RuntimeExperimentConfigResponse.GroupAllocationResponse();
        allocation.setGroup(GROUP_ID);
        allocation.setRatio(GROUP_TRAFFIC_RATIO);
        RuntimeExperimentConfigResponse.TrafficConfigResponse trafficConfig =
                new RuntimeExperimentConfigResponse.TrafficConfigResponse();
        trafficConfig.setTotalTraffic(TOTAL_TRAFFIC);
        trafficConfig.setStrategy(TRAFFIC_STRATEGY);
        trafficConfig.setHashKey(TRAFFIC_HASH_KEY);
        trafficConfig.setAllocation(List.of(allocation));
        return trafficConfig;
    }

    private RuntimeExperimentConfigVersionResponse versionResponse(Long knownVersion) {
        RuntimeExperimentConfigVersionResponse response = new RuntimeExperimentConfigVersionResponse();
        response.setExperimentId(EXPERIMENT_ID);
        response.setKnownVersion(knownVersion);
        response.setCurrentVersion(CONFIG_VERSION);
        response.setChanged(true);
        response.setStatus(STATUS_RUNNING);
        return response;
    }
}
