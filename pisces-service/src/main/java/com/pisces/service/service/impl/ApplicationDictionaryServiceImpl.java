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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 应用字典服务实现
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/16 19:05
 */
@Service
@RequiredArgsConstructor
public class ApplicationDictionaryServiceImpl implements ApplicationDictionaryService {

    private static final Pattern DEFINITION_KEY_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]*$");

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
    public ApplicationDictionaryResponse upsertApplicationDictionary(
            String appId, List<EventDefinition> eventDefinitions, List<MetricDefinition> metricDefinitions) {
        String normalizedAppId = requireAppId(appId);
        assertCanAccessApp(normalizedAppId);
        List<EventDefinition> normalizedEvents = normalizeEventDefinitions(eventDefinitions);
        List<MetricDefinition> normalizedMetrics = normalizeMetricDefinitions(metricDefinitions);
        validateMetricReferences(normalizedAppId, normalizedEvents, normalizedMetrics);
        saveDefinitions(normalizedAppId, normalizedEvents, normalizedMetrics);
        return getApplicationDictionary(normalizedAppId);
    }

    private void saveDefinitions(String appId, List<EventDefinition> eventDefinitions,
                                 List<MetricDefinition> metricDefinitions) {
        LocalDateTime now = LocalDateTime.now();
        String operator = ApiKeyContextHolder.resolveOperator(ApiKeyContextHolder.DEFAULT_OWNER);
        applicationDictionaryRepository.saveEventDefinitions(buildEventDefinitions(
                appId, eventDefinitions, operator, now));
        applicationDictionaryRepository.saveMetricDefinitions(buildMetricDefinitions(
                appId, metricDefinitions, operator, now));
    }

    private List<ApplicationEventDefinition> buildEventDefinitions(String appId,
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
                    applicationEventDefinition.setSourceExperimentId(null);
                    applicationEventDefinition.setUpdatedBy(operator);
                    applicationEventDefinition.setCreatedAt(now);
                    applicationEventDefinition.setUpdatedAt(now);
                    return applicationEventDefinition;
                })
                .toList();
    }

    private List<EventDefinition> normalizeEventDefinitions(List<EventDefinition> eventDefinitions) {
        if (eventDefinitions == null || eventDefinitions.isEmpty()) {
            return List.of();
        }
        Set<String> keys = new HashSet<>();
        return eventDefinitions.stream()
                .map(eventDefinition -> {
                    String key = normalizeDefinitionKey(eventDefinition == null ? null : eventDefinition.getKey(),
                            "事件编码");
                    if (!keys.add(key)) {
                        throw new BusinessException(ResponseCode.VALIDATION_ERROR, "事件编码不能重复：" + key);
                    }
                    if (eventDefinition == null || !StringUtils.hasText(eventDefinition.getLabel())) {
                        throw new BusinessException(ResponseCode.VALIDATION_ERROR, "事件名称不能为空");
                    }
                    EventDefinition normalized = new EventDefinition();
                    normalized.setKey(key);
                    normalized.setLabel(eventDefinition.getLabel().trim());
                    normalized.setDescription(normalizeText(eventDefinition.getDescription()));
                    normalized.setCategory(StringUtils.hasText(eventDefinition.getCategory())
                            ? eventDefinition.getCategory().trim().toUpperCase() : "BUSINESS");
                    normalized.setPrimary(Boolean.TRUE.equals(eventDefinition.getPrimary()));
                    return normalized;
                })
                .toList();
    }

    private List<MetricDefinition> normalizeMetricDefinitions(List<MetricDefinition> metricDefinitions) {
        if (metricDefinitions == null || metricDefinitions.isEmpty()) {
            return List.of();
        }
        Set<String> keys = new HashSet<>();
        return metricDefinitions.stream()
                .map(metricDefinition -> {
                    String key = normalizeDefinitionKey(metricDefinition == null ? null : metricDefinition.getKey(),
                            "指标编码");
                    if (!keys.add(key)) {
                        throw new BusinessException(ResponseCode.VALIDATION_ERROR, "指标编码不能重复：" + key);
                    }
                    if (metricDefinition == null || !StringUtils.hasText(metricDefinition.getName())) {
                        throw new BusinessException(ResponseCode.VALIDATION_ERROR, "指标名称不能为空");
                    }
                    MetricDefinition normalized = new MetricDefinition();
                    normalized.setKey(key);
                    normalized.setName(metricDefinition.getName().trim());
                    normalized.setDescription(normalizeText(metricDefinition.getDescription()));
                    normalized.setAggregationType(metricDefinition.getAggregationType() == null
                            ? MetricDefinition.AggregationType.RATE : metricDefinition.getAggregationType());
                    normalized.setNumeratorEventType(normalizeDefinitionKey(
                            metricDefinition.getNumeratorEventType(), "指标分子事件"));
                    normalized.setDenominatorType(metricDefinition.getDenominatorType() == null
                            ? MetricDefinition.DenominatorType.EVENT_COUNT : metricDefinition.getDenominatorType());
                    normalized.setDenominatorEventType(normalizeText(metricDefinition.getDenominatorEventType())
                            .toUpperCase());
                    normalized.setPrimaryMetric(Boolean.TRUE.equals(metricDefinition.getPrimaryMetric()));
                    normalized.setGuardrailMetric(Boolean.TRUE.equals(metricDefinition.getGuardrailMetric()));
                    if (normalized.getAggregationType() == MetricDefinition.AggregationType.RATE
                            && normalized.getDenominatorType() == MetricDefinition.DenominatorType.EVENT_COUNT
                            && !StringUtils.hasText(normalized.getDenominatorEventType())) {
                        throw new BusinessException(ResponseCode.VALIDATION_ERROR, "比率指标必须选择分母事件");
                    }
                    return normalized;
                })
                .toList();
    }

    private void validateMetricReferences(String appId, List<EventDefinition> eventDefinitions,
                                          List<MetricDefinition> metricDefinitions) {
        if (metricDefinitions.isEmpty()) {
            return;
        }
        Set<String> eventKeys = new HashSet<>();
        applicationDictionaryRepository.findEventDefinitionsByAppId(appId).stream()
                .map(ApplicationEventDefinition::getKey)
                .filter(StringUtils::hasText)
                .map(String::toUpperCase)
                .forEach(eventKeys::add);
        eventDefinitions.stream().map(EventDefinition::getKey).forEach(eventKeys::add);
        for (MetricDefinition metricDefinition : metricDefinitions) {
            if (!eventKeys.contains(metricDefinition.getNumeratorEventType())) {
                throw new BusinessException(ResponseCode.VALIDATION_ERROR,
                        "指标引用了不存在的分子事件：" + metricDefinition.getNumeratorEventType());
            }
            if (metricDefinition.getAggregationType() == MetricDefinition.AggregationType.RATE
                    && metricDefinition.getDenominatorType() == MetricDefinition.DenominatorType.EVENT_COUNT
                    && !eventKeys.contains(metricDefinition.getDenominatorEventType())) {
                throw new BusinessException(ResponseCode.VALIDATION_ERROR,
                        "指标引用了不存在的分母事件：" + metricDefinition.getDenominatorEventType());
            }
        }
    }

    private String normalizeDefinitionKey(String value, String label) {
        String normalized = normalizeText(value).toUpperCase();
        if (!DEFINITION_KEY_PATTERN.matcher(normalized).matches()) {
            throw new BusinessException(ResponseCode.VALIDATION_ERROR,
                    label + "只支持大写字母、数字和下划线");
        }
        return normalized;
    }

    private String normalizeText(String value) {
        return StringUtils.hasText(value) ? value.trim() : "";
    }

    private List<ApplicationMetricDefinition> buildMetricDefinitions(String appId,
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
                    applicationMetricDefinition.setSourceExperimentId(null);
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
