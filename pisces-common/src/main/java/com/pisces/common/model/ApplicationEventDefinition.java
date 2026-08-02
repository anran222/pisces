package com.pisces.common.model;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 应用事件定义
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/16 19:02
 */
@Data
public class ApplicationEventDefinition implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 应用ID
     */
    private String appId;

    /**
     * 事件编码
     */
    private String key;

    /**
     * 事件名称
     */
    private String label;

    /**
     * 事件描述
     */
    private String description;

    /**
     * 事件分类
     */
    private String category;

    /**
     * 是否主事件
     */
    private Boolean primary;

    /**
     * 来源实验ID
     */
    private String sourceExperimentId;

    /**
     * 更新人
     */
    private String updatedBy;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
