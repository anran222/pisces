package com.pisces.common.request;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 实验配置发布请求
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/16 19:20
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ExperimentConfigPublishRequest extends BaseRequest {

    /**
     * 发布操作人
     */
    private String operator;

    /**
     * 发布备注
     */
    private String comment;
}
