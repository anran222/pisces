package com.pisces.service.repository;

import com.pisces.common.model.ApplicationEventDefinition;
import com.pisces.common.model.ApplicationMetricDefinition;

import java.util.List;

/**
 * 应用字典仓库
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/16 19:02
 */
public interface ApplicationDictionaryRepository {

    void saveEventDefinitions(List<ApplicationEventDefinition> eventDefinitions);

    void saveMetricDefinitions(List<ApplicationMetricDefinition> metricDefinitions);

    List<ApplicationEventDefinition> findEventDefinitionsByAppId(String appId);

    List<ApplicationMetricDefinition> findMetricDefinitionsByAppId(String appId);
}
