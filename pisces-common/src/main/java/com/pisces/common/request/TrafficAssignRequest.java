package com.pisces.common.request;

import lombok.Data;

import java.util.Map;

/**
 * 流量分配请求
 */
@Data
public class TrafficAssignRequest {

    /**
     * 实验ID
     */
    private String experimentId;

    /**
     * 访客ID
     */
    private String visitorId;

    /**
     * 访客属性
     */
    private Map<String, Object> attributes;
}
