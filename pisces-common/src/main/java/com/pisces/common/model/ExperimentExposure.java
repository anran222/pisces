package com.pisces.common.model;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 实验曝光事实
 */
@Data
public class ExperimentExposure implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 曝光ID
     */
    private String exposureId;

    /**
     * 实验ID
     */
    private String experimentId;

    /**
     * 访客ID
     */
    private String visitorId;

    /**
     * 实验组ID
     */
    private String groupId;

    /**
     * 曝光时间
     */
    private LocalDateTime exposedAt;

    /**
     * 曝光属性
     */
    private Map<String, Object> properties;
}
