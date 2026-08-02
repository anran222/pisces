package com.pisces.service.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 实验配置草稿实体
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/17 09:09
 */
@Data
public class ExperimentConfigDraftEntity {

    /**
     * 主键ID
     */
    private Long id;

    /**
     * 实验ID
     */
    private String experimentId;

    /**
     * 草稿版本
     */
    private Long draftVersion;

    /**
     * 基线配置版本
     */
    private Long baseConfigVersion;

    /**
     * 元数据JSON
     */
    private String metadataJson;

    /**
     * 更新人
     */
    private String updatedBy;

    /**
     * 草稿备注
     */
    private String draftComment;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
