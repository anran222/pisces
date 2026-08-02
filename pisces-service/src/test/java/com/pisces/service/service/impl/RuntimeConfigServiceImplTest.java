package com.pisces.service.service.impl;

import com.pisces.common.model.Experiment;
import com.pisces.common.model.EventDefinition;
import com.pisces.common.model.ExperimentGroup;
import com.pisces.common.model.ExperimentMetadata;
import com.pisces.common.model.GroupConfigFieldDefinition;
import com.pisces.common.model.MetricDefinition;
import com.pisces.common.model.TrafficConfig;
import com.pisces.common.response.RuntimeExperimentConfigResponse;
import com.pisces.common.response.RuntimeExperimentConfigVersionResponse;
import com.pisces.service.exception.BusinessException;
import com.pisces.service.security.ApiKeyContextHolder;
import com.pisces.service.security.ApiKeyPrincipal;
import com.pisces.service.security.ApiKeyScope;
import com.pisces.service.service.ConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 运行时配置服务测试
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/1 11:49
 */
@ExtendWith(MockitoExtension.class)
class RuntimeConfigServiceImplTest {

    @Mock
    private ConfigService configService;

    @InjectMocks
    private RuntimeConfigServiceImpl runtimeConfigService;

    @AfterEach
    void tearDown() {
        ApiKeyContextHolder.clear();
    }

    @Test
    void getExperimentConfigShouldReturnRuntimeConfigForSameApp() {
        ApiKeyContextHolder.set(principal("app-a"));
        when(configService.getExperimentConfig("exp_runtime_001"))
                .thenReturn(metadata("exp_runtime_001", "app-a"));

        RuntimeExperimentConfigResponse response = runtimeConfigService.getExperimentConfig("exp_runtime_001");

        assertThat(response.getId()).isEqualTo("exp_runtime_001");
        assertThat(response.getStatus()).isEqualTo("RUNNING");
        assertThat(response.getConfigVersion()).isEqualTo(7L);
        assertThat(response.getGroups()).containsKey("group_b");
        assertThat(response.getGroups().get("group_b").getConfig()).containsEntry("discount", "15%");
        assertThat(response.getTraffic().getStrategy()).isEqualTo("HASH");
        assertThat(response.getTraffic().getAllocation()).hasSize(1);
        assertThat(response.getEventDefinitions()).extracting(EventDefinition::getKey)
                .containsExactly("PRODUCT_VIEW");
        assertThat(response.getMetricDefinitions()).extracting(MetricDefinition::getKey)
                .containsExactly("PAY_RATE");
        assertThat(response.getGroupConfigSchema()).extracting(GroupConfigFieldDefinition::getKey)
                .containsExactly("discount");
    }

    @Test
    void getExperimentConfigShouldReturnEmptyCollectionsForOptionalRuntimeAssets() {
        ApiKeyContextHolder.set(principal("app-a"));
        ExperimentMetadata metadata = metadata("exp_runtime_001", "app-a");
        metadata.setEventDefinitions(null);
        metadata.setMetricDefinitions(null);
        metadata.setGroupConfigSchema(null);
        metadata.setGroups(null);
        metadata.setTraffic(null);
        when(configService.getExperimentConfig("exp_runtime_001")).thenReturn(metadata);

        RuntimeExperimentConfigResponse response = runtimeConfigService.getExperimentConfig("exp_runtime_001");

        assertThat(response.getEventDefinitions()).isEmpty();
        assertThat(response.getMetricDefinitions()).isEmpty();
        assertThat(response.getGroupConfigSchema()).isEmpty();
        assertThat(response.getGroups()).isEmpty();
        assertThat(response.getTraffic()).isNull();
    }

    @Test
    void getExperimentConfigShouldReturnEmptyGroupConfigWhenGroupConfigIsMissing() {
        ApiKeyContextHolder.set(principal("app-a"));
        ExperimentMetadata metadata = metadata("exp_runtime_001", "app-a");
        metadata.getGroups().get("group_b").setConfig(null);
        when(configService.getExperimentConfig("exp_runtime_001")).thenReturn(metadata);

        RuntimeExperimentConfigResponse response = runtimeConfigService.getExperimentConfig("exp_runtime_001");

        assertThat(response.getGroups().get("group_b").getConfig()).isEmpty();
    }

    @Test
    void getExperimentConfigShouldRejectOtherApp() {
        ApiKeyContextHolder.set(principal("app-b"));
        when(configService.getExperimentConfig("exp_runtime_001"))
                .thenReturn(metadata("exp_runtime_001", "app-a"));

        assertThatThrownBy(() -> runtimeConfigService.getExperimentConfig("exp_runtime_001"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权访问当前应用实验");
    }

    @Test
    void getExperimentConfigVersionShouldReportChangeState() {
        ApiKeyContextHolder.set(principal("app-a"));
        when(configService.getExperimentConfig("exp_runtime_001"))
                .thenReturn(metadata("exp_runtime_001", "app-a"));

        RuntimeExperimentConfigVersionResponse unchanged =
                runtimeConfigService.getExperimentConfigVersion("exp_runtime_001", 7L, null);
        RuntimeExperimentConfigVersionResponse changed =
                runtimeConfigService.getExperimentConfigVersion("exp_runtime_001", 6L, null);

        assertThat(unchanged.getExperimentId()).isEqualTo("exp_runtime_001");
        assertThat(unchanged.getKnownVersion()).isEqualTo(7L);
        assertThat(unchanged.getCurrentVersion()).isEqualTo(7L);
        assertThat(unchanged.getChanged()).isFalse();
        assertThat(unchanged.getStatus()).isEqualTo("RUNNING");
        assertThat(unchanged.getGeneratedAt()).isNotNull();
        assertThat(changed.getChanged()).isTrue();
    }

    @Test
    void getExperimentConfigVersionShouldNotWaitWhenKnownVersionIsMissing() throws InterruptedException {
        ApiKeyContextHolder.set(principal("app-a"));
        when(configService.getExperimentConfig("exp_runtime_001"))
                .thenReturn(metadata("exp_runtime_001", "app-a"));

        RuntimeExperimentConfigVersionResponse response =
                runtimeConfigService.getExperimentConfigVersion("exp_runtime_001", null, 100L);

        assertThat(response.getKnownVersion()).isNull();
        assertThat(response.getCurrentVersion()).isEqualTo(7L);
        assertThat(response.getChanged()).isTrue();
        verify(configService, never()).getExperimentConfigChangeSequence("exp_runtime_001");
        verify(configService, never()).waitForExperimentConfigChange(anyString(), anyLong(), anyLong());
    }

    @Test
    void getExperimentConfigVersionShouldWaitWhenKnownVersionIsCurrent() throws InterruptedException {
        ApiKeyContextHolder.set(principal("app-a"));
        when(configService.getExperimentConfigChangeSequence("exp_runtime_001")).thenReturn(12L);
        when(configService.getExperimentConfig("exp_runtime_001"))
                .thenReturn(metadata("exp_runtime_001", "app-a", 7L),
                        metadata("exp_runtime_001", "app-a", 8L));

        RuntimeExperimentConfigVersionResponse response =
                runtimeConfigService.getExperimentConfigVersion("exp_runtime_001", 7L, 100L);

        assertThat(response.getCurrentVersion()).isEqualTo(8L);
        assertThat(response.getChanged()).isTrue();
        verify(configService).waitForExperimentConfigChange("exp_runtime_001", 12L, 100L);
    }

    @Test
    void getExperimentConfigVersionShouldClampWaitMillis() throws InterruptedException {
        ApiKeyContextHolder.set(principal("app-a"));
        when(configService.getExperimentConfigChangeSequence("exp_runtime_001")).thenReturn(15L);
        when(configService.getExperimentConfig("exp_runtime_001"))
                .thenReturn(metadata("exp_runtime_001", "app-a"));

        RuntimeExperimentConfigVersionResponse response =
                runtimeConfigService.getExperimentConfigVersion("exp_runtime_001", 7L, 60_000L);

        assertThat(response.getChanged()).isFalse();
        verify(configService).waitForExperimentConfigChange("exp_runtime_001", 15L, 30_000L);
    }

    private ApiKeyPrincipal principal(String appId) {
        ApiKeyPrincipal principal = new ApiKeyPrincipal();
        principal.setAppId(appId);
        principal.setOwner("sdk");
        principal.setScopes(EnumSet.of(ApiKeyScope.RUNTIME));
        return principal;
    }

    private ExperimentMetadata metadata(String experimentId, String appId) {
        return metadata(experimentId, appId, 7L);
    }

    private ExperimentMetadata metadata(String experimentId, String appId, Long configVersion) {
        Experiment experiment = new Experiment();
        experiment.setId(experimentId);
        experiment.setName("价格实验");
        experiment.setDescription("商品价格实验");
        experiment.setStatus(Experiment.ExperimentStatus.RUNNING);
        experiment.setAppId(appId);
        experiment.setOwner("owner-a");

        ExperimentGroup group = new ExperimentGroup();
        group.setId("group_b");
        group.setName("实验组");
        group.setTrafficRatio(0.5D);
        group.setConfig(Map.of("discount", "15%"));

        TrafficConfig.GroupAllocation allocation = new TrafficConfig.GroupAllocation();
        allocation.setGroup("group_b");
        allocation.setRatio(1D);

        TrafficConfig trafficConfig = new TrafficConfig();
        trafficConfig.setTotalTraffic(1D);
        trafficConfig.setStrategy(TrafficConfig.TrafficStrategy.HASH);
        trafficConfig.setHashKey("visitorId");
        trafficConfig.setAllocation(List.of(allocation));

        Map<String, ExperimentGroup> groups = new LinkedHashMap<>();
        groups.put("group_b", group);

        ExperimentMetadata metadata = new ExperimentMetadata();
        metadata.setExperiment(experiment);
        metadata.setConfigVersion(configVersion);
        metadata.setAppId(appId);
        metadata.setOwner("owner-a");
        metadata.setGroups(groups);
        metadata.setTraffic(trafficConfig);
        metadata.setEventDefinitions(List.of(eventDefinition()));
        metadata.setMetricDefinitions(List.of(metricDefinition()));
        metadata.setGroupConfigSchema(List.of(groupConfigFieldDefinition()));
        return metadata;
    }

    private EventDefinition eventDefinition() {
        EventDefinition eventDefinition = new EventDefinition();
        eventDefinition.setKey("PRODUCT_VIEW");
        eventDefinition.setLabel("商品查看");
        eventDefinition.setDescription("进入商品详情页");
        eventDefinition.setCategory("FUNNEL");
        eventDefinition.setPrimary(true);
        return eventDefinition;
    }

    private MetricDefinition metricDefinition() {
        MetricDefinition metricDefinition = new MetricDefinition();
        metricDefinition.setKey("PAY_RATE");
        metricDefinition.setName("支付率");
        metricDefinition.setDescription("支付成功占查看比率");
        metricDefinition.setAggregationType(MetricDefinition.AggregationType.RATE);
        metricDefinition.setNumeratorEventType("PAY_SUCCESS");
        metricDefinition.setDenominatorType(MetricDefinition.DenominatorType.EVENT_COUNT);
        metricDefinition.setDenominatorEventType("PRODUCT_VIEW");
        metricDefinition.setPrimaryMetric(true);
        metricDefinition.setGuardrailMetric(false);
        return metricDefinition;
    }

    private GroupConfigFieldDefinition groupConfigFieldDefinition() {
        GroupConfigFieldDefinition fieldDefinition = new GroupConfigFieldDefinition();
        fieldDefinition.setKey("discount");
        fieldDefinition.setLabel("优惠力度");
        fieldDefinition.setValueType(GroupConfigFieldDefinition.ValueType.STRING);
        fieldDefinition.setRequired(true);
        fieldDefinition.setDescription("商品详情页优惠文案");
        fieldDefinition.setDefaultValue("0%");
        return fieldDefinition;
    }
}
