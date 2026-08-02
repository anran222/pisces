package com.pisces.common.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 实验配置版本
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/16 19:20
 */
@Data
public class ExperimentConfigVersion {

    public static final String SOURCE_TYPE_PUBLISH = "PUBLISH";
    public static final String SOURCE_TYPE_ROLLBACK = "ROLLBACK";
    public static final String SOURCE_TYPE_DRAFT_PUBLISH = "DRAFT_PUBLISH";

    /**
     * 实验ID
     */
    private String experimentId;

    /**
     * 配置版本
     */
    private Long configVersion;

    /**
     * 配置快照
     */
    private ExperimentMetadata metadata;

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
     * 来源类型：PUBLISH 或 ROLLBACK
     */
    private String sourceType;

    /**
     * 发布时间
     */
    private LocalDateTime publishedAt;
}
