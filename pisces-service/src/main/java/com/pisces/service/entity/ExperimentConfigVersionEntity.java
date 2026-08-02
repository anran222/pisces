package com.pisces.service.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 实验配置版本实体
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/16 19:20
 */
@Data
public class ExperimentConfigVersionEntity {

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
     * 发布人
     */
    private String publishedBy;

    /**
     * 发布备注
     */
    private String publishComment;

    /**
     * 来源配置版本
     */
    private Long sourceConfigVersion;

    /**
     * 来源类型
     */
    private String sourceType;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
