package com.pisces.common.response;

import com.pisces.common.model.Experiment;
import com.pisces.common.model.ExperimentMetadata;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 实验配置版本响应
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/16 19:20
 */
@Data
public class ExperimentConfigVersionResponse {

    /**
     * 实验ID
     */
    private String experimentId;

    /**
     * 实验名称
     */
    private String experimentName;

    /**
     * 所属应用ID
     */
    private String appId;

    /**
     * 归属人
     */
    private String owner;

    /**
     * 实验状态
     */
    private Experiment.ExperimentStatus experimentStatus;

    /**
     * 审批状态
     */
    private ExperimentMetadata.ApprovalStatus approvalStatus;

    /**
     * 配置版本
     */
    private Long configVersion;

    /**
     * 来源配置版本
     */
    private Long sourceConfigVersion;

    /**
     * 来源类型
     */
    private String sourceType;

    /**
     * 所属流量分层ID
     */
    private String layerId;

    /**
     * 实验组数量
     */
    private Integer groupCount;

    /**
     * 事件定义数量
     */
    private Integer eventDefinitionCount;

    /**
     * 指标定义数量
     */
    private Integer metricDefinitionCount;

    /**
     * 发布人
     */
    private String publishedBy;

    /**
     * 发布备注
     */
    private String publishComment;

    /**
     * 发布时间
     */
    private LocalDateTime publishedAt;
}
