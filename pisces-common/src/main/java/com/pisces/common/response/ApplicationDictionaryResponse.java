package com.pisces.common.response;

import com.pisces.common.model.ApplicationEventDefinition;
import com.pisces.common.model.ApplicationMetricDefinition;
import lombok.Data;

import java.util.List;

/**
 * 应用字典响应
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/16 19:02
 */
@Data
public class ApplicationDictionaryResponse {

    /**
     * 应用ID
     */
    private String appId;

    /**
     * 事件定义
     */
    private List<ApplicationEventDefinition> eventDefinitions;

    /**
     * 指标定义
     */
    private List<ApplicationMetricDefinition> metricDefinitions;
}
