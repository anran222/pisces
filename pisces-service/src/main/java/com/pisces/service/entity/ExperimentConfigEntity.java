package com.pisces.service.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 实验配置实体
 */
@Data
public class ExperimentConfigEntity {

    /**
     * 主键ID
     */
    private Long id;

    /**
     * 实验ID
     */
    private String experimentId;

    /**
     * 配置版本
     */
    private Long configVersion;

    /**
     * 元数据JSON
     */
    private String metadataJson;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
