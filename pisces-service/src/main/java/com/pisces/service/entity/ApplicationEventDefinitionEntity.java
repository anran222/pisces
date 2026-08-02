package com.pisces.service.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 应用事件定义实体
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/16 19:02
 */
@Data
public class ApplicationEventDefinitionEntity {

    private Long id;

    private String appId;

    private String eventKey;

    private String label;

    private String description;

    private String category;

    private Boolean primary;

    private String sourceExperimentId;

    private String updatedBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
