package com.pisces.common.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 实验配置草稿
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/17 09:09
 */
@Data
public class ExperimentConfigDraft {

    /**
     * 实验ID
     */
    private String experimentId;

    /**
     * 草稿版本
     */
    private Long draftVersion;

    /**
     * 草稿基于的当前配置版本
     */
    private Long baseConfigVersion;

    /**
     * 草稿配置快照
     */
    private ExperimentMetadata metadata;

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
