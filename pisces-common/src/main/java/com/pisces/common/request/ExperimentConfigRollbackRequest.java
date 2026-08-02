package com.pisces.common.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 实验配置回滚请求
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/16 19:20
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ExperimentConfigRollbackRequest extends BaseRequest {

    /**
     * 目标配置版本
     */
    @NotNull(message = "目标配置版本不能为空")
    private Long targetConfigVersion;

    /**
     * 回滚操作人
     */
    private String operator;

    /**
     * 回滚备注
     */
    private String comment;
}
