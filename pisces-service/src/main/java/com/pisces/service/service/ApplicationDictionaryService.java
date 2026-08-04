package com.pisces.service.service;

import com.pisces.common.model.EventDefinition;
import com.pisces.common.model.MetricDefinition;
import com.pisces.common.response.ApplicationDictionaryResponse;

import java.util.List;

/**
 * 应用字典服务
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/16 19:05
 */
public interface ApplicationDictionaryService {

    /**
     * 查询应用事件和指标字典。
     *
     * @param appId 应用ID
     * @return 应用字典
     */
    ApplicationDictionaryResponse getApplicationDictionary(String appId);

    /**
     * 保存应用事件和指标字典。
     *
     * @param appId 应用ID
     * @param eventDefinitions 事件定义
     * @param metricDefinitions 指标定义
     * @return 保存后的应用字典
     */
    ApplicationDictionaryResponse upsertApplicationDictionary(String appId, List<EventDefinition> eventDefinitions,
                                                               List<MetricDefinition> metricDefinitions);

}
