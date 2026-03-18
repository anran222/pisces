package com.pisces.common.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 访客身份绑定请求
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class IdentityBindRequest extends BaseRequest {

    @NotBlank(message = "deviceId不能为空")
    private String deviceId;

    @NotBlank(message = "userId不能为空")
    private String userId;
}
