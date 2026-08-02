package com.pisces.common.request;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 实验配置草稿保存请求
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/17 09:09
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ExperimentConfigDraftSaveRequest extends ExperimentCreateRequest {

    /**
     * 保存操作人
     */
    private String operator;

    /**
     * 草稿备注
     */
    private String comment;
}
