package com.pisces.service.repository.impl;

import com.pisces.common.model.ApplicationEventDefinition;
import com.pisces.common.model.ApplicationMetricDefinition;
import com.pisces.common.model.MetricDefinition;
import com.pisces.service.entity.ApplicationEventDefinitionEntity;
import com.pisces.service.entity.ApplicationMetricDefinitionEntity;
import com.pisces.service.mapper.ApplicationDictionaryMapper;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 应用字典数据访问实现
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/16 19:02
 */
@Repository
@AllArgsConstructor
public class ApplicationDictionaryRepository
        implements com.pisces.service.repository.ApplicationDictionaryRepository {

    private final ApplicationDictionaryMapper applicationDictionaryMapper;

    @Override
    public void saveEventDefinitions(List<ApplicationEventDefinition> eventDefinitions) {
        if (eventDefinitions == null || eventDefinitions.isEmpty()) {
            return;
        }
        for (ApplicationEventDefinition eventDefinition : eventDefinitions) {
            applicationDictionaryMapper.upsertEventDefinition(buildEventDefinitionEntity(eventDefinition));
        }
    }

    @Override
    public void saveMetricDefinitions(List<ApplicationMetricDefinition> metricDefinitions) {
        if (metricDefinitions == null || metricDefinitions.isEmpty()) {
            return;
        }
        for (ApplicationMetricDefinition metricDefinition : metricDefinitions) {
            applicationDictionaryMapper.upsertMetricDefinition(buildMetricDefinitionEntity(metricDefinition));
        }
    }

    @Override
    public List<ApplicationEventDefinition> findEventDefinitionsByAppId(String appId) {
        List<ApplicationEventDefinitionEntity> entities =
                applicationDictionaryMapper.selectEventDefinitionsByAppId(appId);
        return entities == null ? List.of() : entities.stream()
                .map(this::buildEventDefinition)
                .toList();
    }

    @Override
    public List<ApplicationMetricDefinition> findMetricDefinitionsByAppId(String appId) {
        List<ApplicationMetricDefinitionEntity> entities =
                applicationDictionaryMapper.selectMetricDefinitionsByAppId(appId);
        return entities == null ? List.of() : entities.stream()
                .map(this::buildMetricDefinition)
                .toList();
    }

    private ApplicationEventDefinitionEntity buildEventDefinitionEntity(
            ApplicationEventDefinition eventDefinition) {
        ApplicationEventDefinitionEntity entity = new ApplicationEventDefinitionEntity();
        entity.setAppId(eventDefinition.getAppId());
        entity.setEventKey(eventDefinition.getKey());
        entity.setLabel(eventDefinition.getLabel());
        entity.setDescription(eventDefinition.getDescription());
        entity.setCategory(eventDefinition.getCategory());
        entity.setPrimary(eventDefinition.getPrimary());
        entity.setSourceExperimentId(eventDefinition.getSourceExperimentId());
        entity.setUpdatedBy(eventDefinition.getUpdatedBy());
        entity.setCreatedAt(eventDefinition.getCreatedAt());
        entity.setUpdatedAt(eventDefinition.getUpdatedAt());
        return entity;
    }

    private ApplicationMetricDefinitionEntity buildMetricDefinitionEntity(
            ApplicationMetricDefinition metricDefinition) {
        ApplicationMetricDefinitionEntity entity = new ApplicationMetricDefinitionEntity();
        entity.setAppId(metricDefinition.getAppId());
        entity.setMetricKey(metricDefinition.getKey());
        entity.setName(metricDefinition.getName());
        entity.setDescription(metricDefinition.getDescription());
        entity.setAggregationType(metricDefinition.getAggregationType() == null
                ? null : metricDefinition.getAggregationType().name());
        entity.setNumeratorEventType(metricDefinition.getNumeratorEventType());
        entity.setDenominatorType(metricDefinition.getDenominatorType() == null
                ? null : metricDefinition.getDenominatorType().name());
        entity.setDenominatorEventType(metricDefinition.getDenominatorEventType());
        entity.setPrimaryMetric(metricDefinition.getPrimaryMetric());
        entity.setGuardrailMetric(metricDefinition.getGuardrailMetric());
        entity.setSourceExperimentId(metricDefinition.getSourceExperimentId());
        entity.setUpdatedBy(metricDefinition.getUpdatedBy());
        entity.setCreatedAt(metricDefinition.getCreatedAt());
        entity.setUpdatedAt(metricDefinition.getUpdatedAt());
        return entity;
    }

    private ApplicationEventDefinition buildEventDefinition(ApplicationEventDefinitionEntity entity) {
        ApplicationEventDefinition eventDefinition = new ApplicationEventDefinition();
        eventDefinition.setAppId(entity.getAppId());
        eventDefinition.setKey(entity.getEventKey());
        eventDefinition.setLabel(entity.getLabel());
        eventDefinition.setDescription(entity.getDescription());
        eventDefinition.setCategory(entity.getCategory());
        eventDefinition.setPrimary(entity.getPrimary());
        eventDefinition.setSourceExperimentId(entity.getSourceExperimentId());
        eventDefinition.setUpdatedBy(entity.getUpdatedBy());
        eventDefinition.setCreatedAt(entity.getCreatedAt());
        eventDefinition.setUpdatedAt(entity.getUpdatedAt());
        return eventDefinition;
    }

    private ApplicationMetricDefinition buildMetricDefinition(ApplicationMetricDefinitionEntity entity) {
        ApplicationMetricDefinition metricDefinition = new ApplicationMetricDefinition();
        metricDefinition.setAppId(entity.getAppId());
        metricDefinition.setKey(entity.getMetricKey());
        metricDefinition.setName(entity.getName());
        metricDefinition.setDescription(entity.getDescription());
        metricDefinition.setAggregationType(entity.getAggregationType() == null
                ? null : MetricDefinition.AggregationType.valueOf(entity.getAggregationType()));
        metricDefinition.setNumeratorEventType(entity.getNumeratorEventType());
        metricDefinition.setDenominatorType(entity.getDenominatorType() == null
                ? null : MetricDefinition.DenominatorType.valueOf(entity.getDenominatorType()));
        metricDefinition.setDenominatorEventType(entity.getDenominatorEventType());
        metricDefinition.setPrimaryMetric(entity.getPrimaryMetric());
        metricDefinition.setGuardrailMetric(entity.getGuardrailMetric());
        metricDefinition.setSourceExperimentId(entity.getSourceExperimentId());
        metricDefinition.setUpdatedBy(entity.getUpdatedBy());
        metricDefinition.setCreatedAt(entity.getCreatedAt());
        metricDefinition.setUpdatedAt(entity.getUpdatedAt());
        return metricDefinition;
    }
}
