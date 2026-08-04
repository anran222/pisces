package com.pisces.common.request;

import com.pisces.common.model.EventDefinition;
import com.pisces.common.model.MetricDefinition;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 应用事件与指标字典保存请求。
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/8/4 23:20
 */
@Data
public class ApplicationDictionaryUpsertRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 事件定义。
     */
    private List<EventDefinition> eventDefinitions;

    /**
     * 指标定义。
     */
    private List<MetricDefinition> metricDefinitions;
}
