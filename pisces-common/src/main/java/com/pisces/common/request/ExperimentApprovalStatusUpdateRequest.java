package com.pisces.common.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 实验审批状态请求
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/16 18:50
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ExperimentApprovalStatusUpdateRequest extends BaseRequest {

    /**
     * 审批状态
     */
    @NotBlank(message = "审批状态不能为空")
    private String approvalStatus;

    /**
     * 操作人
     */
    private String operator;

    /**
     * 审批备注
     */
    private String comment;

    /**
     * 是否豁免最新报告中的阻断风险
     */
    private Boolean riskOverride;

    /**
     * 风险豁免原因
     */
    private String riskOverrideReason;
}
