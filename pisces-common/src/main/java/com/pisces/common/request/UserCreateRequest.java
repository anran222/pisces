package com.pisces.common.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Email;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户创建请求DTO
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class UserCreateRequest extends BaseRequest {
    
    @NotBlank(message = "用户名不能为空")
    private String username;
    
    @NotBlank(message = "密码不能为空")
    private String password;
    
    @Email(message = "邮箱格式不正确")
    private String email;
    
    private String nickname;
}

