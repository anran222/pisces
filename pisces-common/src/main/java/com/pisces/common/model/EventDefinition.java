package com.pisces.common.model;

import lombok.Data;

import java.io.Serializable;

/**
 * 事件定义
 */
@Data
public class EventDefinition implements Serializable {
    private static final long serialVersionUID = 1L;

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
}
