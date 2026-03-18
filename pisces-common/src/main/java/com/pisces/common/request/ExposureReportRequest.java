package com.pisces.common.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

/**
 * 曝光上报请求
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ExposureReportRequest extends BaseRequest {

    @NotBlank(message = "实验ID不能为空")
    private String experimentId;

    @NotBlank(message = "访客ID不能为空")
    private String visitorId;

    /**
     * 曝光属性
     */
    private Map<String, Object> properties;
}
