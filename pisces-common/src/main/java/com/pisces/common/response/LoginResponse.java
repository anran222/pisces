package com.pisces.common.response;

import lombok.Data;
import java.io.Serializable;

/**
 * 登录响应
 */
@Data
public class LoginResponse implements Serializable {
    private static final long serialVersionUID = 1L;
    
    /**
     * Token
     */
    private String token;
    
    /**
     * 用户信息
     */
    private UserResponse user;
    
    /**
     * Token过期时间（秒）
     */
    private Long expireIn;
}

