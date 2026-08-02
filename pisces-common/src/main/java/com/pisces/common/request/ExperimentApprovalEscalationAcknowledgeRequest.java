package com.pisces.common.request;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 审批升级告警确认请求
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/17 14:45
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ExperimentApprovalEscalationAcknowledgeRequest extends BaseRequest {

    /**
     * 操作人；存在 API Key principal 时优先使用 principal owner
     */
    private String operator;

    /**
     * 确认备注
     */
    private String comment;
}
