package com.pisces.service.service.impl;

import com.pisces.common.enums.ResponseCode;
import com.pisces.common.model.ApplicationEventDefinition;
import com.pisces.common.model.ApplicationMetricDefinition;
import com.pisces.common.model.EventDefinition;
import com.pisces.common.model.MetricDefinition;
import com.pisces.common.response.ApplicationDictionaryResponse;
import com.pisces.service.exception.BusinessException;
import com.pisces.service.repository.ApplicationDictionaryRepository;
import com.pisces.service.security.ApiKeyContextHolder;
import com.pisces.service.security.ApiKeyPrincipal;
import com.pisces.service.security.ApiKeyScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 应用字典服务测试
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/16 19:05
 */
@ExtendWith(MockitoExtension.class)
class ApplicationDictionaryServiceImplTest {

    @Mock
    private ApplicationDictionaryRepository applicationDictionaryRepository;

    @InjectMocks
    private ApplicationDictionaryServiceImpl applicationDictionaryService;

    @AfterEach
    void tearDown() {
        ApiKeyContextHolder.clear();
    }

    @Test
    void getApplicationDictionaryShouldReturnDefinitionsForAdmin() {
        ApiKeyContextHolder.set(principal("platform", "ops", ApiKeyScope.ADMIN));
        ApplicationEventDefinition eventDefinition = applicationEventDefinition("PRODUCT_VIEW");
        ApplicationMetricDefinition metricDefinition = applicationMetricDefinition("PAY_RATE");
        when(applicationDictionaryRepository.findEventDefinitionsByAppId("app-a"))
                .thenReturn(List.of(eventDefinition));
        when(applicationDictionaryRepository.findMetricDefinitionsByAppId("app-a"))
                .thenReturn(List.of(metricDefinition));

        ApplicationDictionaryResponse response =
                applicationDictionaryService.getApplicationDictionary(" app-a ");

        assertThat(response.getAppId()).isEqualTo("app-a");
        assertThat(response.getEventDefinitions()).containsExactly(eventDefinition);
        assertThat(response.getMetricDefinitions()).containsExactly(metricDefinition);
    }

    @Test
    void getApplicationDictionaryShouldRejectOtherAppForNonAdmin() {
        ApiKeyContextHolder.set(principal("app-a", "owner-a", ApiKeyScope.MANAGEMENT));

        assertThatThrownBy(() -> applicationDictionaryService.getApplicationDictionary("app-b"))
                .isInstanceOf(BusinessException.class)
                .satisfies(throwable -> assertThat(((BusinessException) throwable).getResponseCode())
                        .isEqualTo(ResponseCode.FORBIDDEN));
    }

    @Test
    @SuppressWarnings("unchecked")
    void syncDefinitionsShouldPersistEventAndMetricDefinitions() {
        ApiKeyContextHolder.set(principal("app-a", "owner-a", ApiKeyScope.MANAGEMENT));
        EventDefinition eventDefinition = eventDefinition("PRODUCT_VIEW", "商品查看", true);
        MetricDefinition metricDefinition = metricDefinition("PAY_RATE", "支付率");

        applicationDictionaryService.syncDefinitions("app-a", "exp_dict", List.of(eventDefinition),
                List.of(metricDefinition));

        ArgumentCaptor<List<ApplicationEventDefinition>> eventCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<ApplicationMetricDefinition>> metricCaptor = ArgumentCaptor.forClass(List.class);
        verify(applicationDictionaryRepository).saveEventDefinitions(eventCaptor.capture());
        verify(applicationDictionaryRepository).saveMetricDefinitions(metricCaptor.capture());
        ApplicationEventDefinition savedEventDefinition = eventCaptor.getValue().get(0);
        assertThat(savedEventDefinition.getAppId()).isEqualTo("app-a");
        assertThat(savedEventDefinition.getKey()).isEqualTo("PRODUCT_VIEW");
        assertThat(savedEventDefinition.getSourceExperimentId()).isEqualTo("exp_dict");
        assertThat(savedEventDefinition.getUpdatedBy()).isEqualTo("owner-a");
        assertThat(savedEventDefinition.getCreatedAt()).isNotNull();
        assertThat(savedEventDefinition.getUpdatedAt()).isNotNull();

        ApplicationMetricDefinition savedMetricDefinition = metricCaptor.getValue().get(0);
        assertThat(savedMetricDefinition.getAppId()).isEqualTo("app-a");
        assertThat(savedMetricDefinition.getKey()).isEqualTo("PAY_RATE");
        assertThat(savedMetricDefinition.getSourceExperimentId()).isEqualTo("exp_dict");
        assertThat(savedMetricDefinition.getUpdatedBy()).isEqualTo("owner-a");
        assertThat(savedMetricDefinition.getAggregationType()).isEqualTo(MetricDefinition.AggregationType.RATE);
    }

    private ApiKeyPrincipal principal(String appId, String owner, ApiKeyScope firstScope,
                                      ApiKeyScope... remainingScopes) {
        ApiKeyPrincipal principal = new ApiKeyPrincipal();
        principal.setAppId(appId);
        principal.setOwner(owner);
        principal.setScopes(EnumSet.of(firstScope, remainingScopes));
        return principal;
    }

    private ApplicationEventDefinition applicationEventDefinition(String key) {
        ApplicationEventDefinition eventDefinition = new ApplicationEventDefinition();
        eventDefinition.setAppId("app-a");
        eventDefinition.setKey(key);
        eventDefinition.setLabel("商品查看");
        return eventDefinition;
    }

    private ApplicationMetricDefinition applicationMetricDefinition(String key) {
        ApplicationMetricDefinition metricDefinition = new ApplicationMetricDefinition();
        metricDefinition.setAppId("app-a");
        metricDefinition.setKey(key);
        metricDefinition.setName("支付率");
        return metricDefinition;
    }

    private EventDefinition eventDefinition(String key, String label, boolean primary) {
        EventDefinition eventDefinition = new EventDefinition();
        eventDefinition.setKey(key);
        eventDefinition.setLabel(label);
        eventDefinition.setCategory("BUSINESS");
        eventDefinition.setPrimary(primary);
        return eventDefinition;
    }

    private MetricDefinition metricDefinition(String key, String name) {
        MetricDefinition metricDefinition = new MetricDefinition();
        metricDefinition.setKey(key);
        metricDefinition.setName(name);
        metricDefinition.setAggregationType(MetricDefinition.AggregationType.RATE);
        metricDefinition.setNumeratorEventType("PAY_SUCCESS");
        metricDefinition.setDenominatorType(MetricDefinition.DenominatorType.EVENT_COUNT);
        metricDefinition.setDenominatorEventType("PRODUCT_VIEW");
        metricDefinition.setPrimaryMetric(true);
        metricDefinition.setGuardrailMetric(false);
        return metricDefinition;
    }
}
