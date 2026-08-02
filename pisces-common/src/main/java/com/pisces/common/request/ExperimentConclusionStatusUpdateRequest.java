package com.pisces.common.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 实验结论状态更新请求
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ExperimentConclusionStatusUpdateRequest extends BaseRequest {

    /**
     * 人工确认的结论状态
     */
    @NotBlank(message = "结论状态不能为空")
    private String conclusionStatus;

    /**
     * 人工结论基于的当前配置版本
     */
    private Long expectedConfigVersion;

    /**
     * 人工结论基于的报告快照版本
     */
    private Integer reportSnapshotVersion;

    /**
     * 操作人
     */
    private String operator;

    /**
     * 结论备注
     */
    private String comment;
}
