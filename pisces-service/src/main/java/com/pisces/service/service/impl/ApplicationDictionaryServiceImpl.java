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
import com.pisces.service.service.ApplicationDictionaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 应用字典服务实现
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/16 19:05
 */
@Service
@RequiredArgsConstructor
public class ApplicationDictionaryServiceImpl implements ApplicationDictionaryService {

    private final ApplicationDictionaryRepository applicationDictionaryRepository;

    @Override
    public ApplicationDictionaryResponse getApplicationDictionary(String appId) {
        String normalizedAppId = requireAppId(appId);
        assertCanAccessApp(normalizedAppId);
        ApplicationDictionaryResponse response = new ApplicationDictionaryResponse();
        response.setAppId(normalizedAppId);
        response.setEventDefinitions(applicationDictionaryRepository.findEventDefinitionsByAppId(normalizedAppId));
        response.setMetricDefinitions(applicationDictionaryRepository.findMetricDefinitionsByAppId(normalizedAppId));
        return response;
    }

    @Override
    public void syncDefinitions(String appId, String experimentId, List<EventDefinition> eventDefinitions,
                                List<MetricDefinition> metricDefinitions) {
        String normalizedAppId = requireAppId(appId);
        assertCanAccessApp(normalizedAppId);
        LocalDateTime now = LocalDateTime.now();
        String operator = ApiKeyContextHolder.resolveOperator(ApiKeyContextHolder.DEFAULT_OWNER);
        applicationDictionaryRepository.saveEventDefinitions(buildEventDefinitions(
                normalizedAppId, experimentId, eventDefinitions, operator, now));
        applicationDictionaryRepository.saveMetricDefinitions(buildMetricDefinitions(
                normalizedAppId, experimentId, metricDefinitions, operator, now));
    }

    private List<ApplicationEventDefinition> buildEventDefinitions(String appId, String experimentId,
                                                                   List<EventDefinition> eventDefinitions,
                                                                   String operator, LocalDateTime now) {
        if (eventDefinitions == null || eventDefinitions.isEmpty()) {
            return List.of();
        }
        return eventDefinitions.stream()
                .map(eventDefinition -> {
                    ApplicationEventDefinition applicationEventDefinition = new ApplicationEventDefinition();
                    applicationEventDefinition.setAppId(appId);
                    applicationEventDefinition.setKey(eventDefinition.getKey());
                    applicationEventDefinition.setLabel(eventDefinition.getLabel());
                    applicationEventDefinition.setDescription(eventDefinition.getDescription());
                    applicationEventDefinition.setCategory(eventDefinition.getCategory());
                    applicationEventDefinition.setPrimary(eventDefinition.getPrimary());
                    applicationEventDefinition.setSourceExperimentId(experimentId);
                    applicationEventDefinition.setUpdatedBy(operator);
                    applicationEventDefinition.setCreatedAt(now);
                    applicationEventDefinition.setUpdatedAt(now);
                    return applicationEventDefinition;
                })
                .toList();
    }

    private List<ApplicationMetricDefinition> buildMetricDefinitions(String appId, String experimentId,
                                                                     List<MetricDefinition> metricDefinitions,
                                                                     String operator, LocalDateTime now) {
        if (metricDefinitions == null || metricDefinitions.isEmpty()) {
            return List.of();
        }
        return metricDefinitions.stream()
                .map(metricDefinition -> {
                    ApplicationMetricDefinition applicationMetricDefinition = new ApplicationMetricDefinition();
                    applicationMetricDefinition.setAppId(appId);
                    applicationMetricDefinition.setKey(metricDefinition.getKey());
                    applicationMetricDefinition.setName(metricDefinition.getName());
                    applicationMetricDefinition.setDescription(metricDefinition.getDescription());
                    applicationMetricDefinition.setAggregationType(metricDefinition.getAggregationType());
                    applicationMetricDefinition.setNumeratorEventType(metricDefinition.getNumeratorEventType());
                    applicationMetricDefinition.setDenominatorType(metricDefinition.getDenominatorType());
                    applicationMetricDefinition.setDenominatorEventType(metricDefinition.getDenominatorEventType());
                    applicationMetricDefinition.setPrimaryMetric(metricDefinition.getPrimaryMetric());
                    applicationMetricDefinition.setGuardrailMetric(metricDefinition.getGuardrailMetric());
                    applicationMetricDefinition.setSourceExperimentId(experimentId);
                    applicationMetricDefinition.setUpdatedBy(operator);
                    applicationMetricDefinition.setCreatedAt(now);
                    applicationMetricDefinition.setUpdatedAt(now);
                    return applicationMetricDefinition;
                })
                .toList();
    }

    private void assertCanAccessApp(String appId) {
        if (!canAccessApp(appId)) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "无权访问当前应用字典");
        }
    }

    private boolean canAccessApp(String appId) {
        return ApiKeyContextHolder.get()
                .map(principal -> ApiKeyContextHolder.isAdmin(principal)
                        || normalizeAppId(principal.getAppId()).equals(appId))
                .orElse(true);
    }

    private String requireAppId(String appId) {
        String normalizedAppId = normalizeAppId(appId);
        if (!StringUtils.hasText(normalizedAppId)) {
            throw new BusinessException(ResponseCode.VALIDATION_ERROR, "应用ID不能为空");
        }
        return normalizedAppId;
    }

    private static String normalizeAppId(String appId) {
        return StringUtils.hasText(appId) ? appId.trim() : ApiKeyContextHolder.DEFAULT_APP_ID;
    }
}
