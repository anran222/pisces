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
     * 操作人
     */
    private String operator;
}
