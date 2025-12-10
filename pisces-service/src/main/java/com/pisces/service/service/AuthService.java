package com.pisces.service.service;

import com.pisces.common.request.LoginRequest;
import com.pisces.common.response.LoginResponse;

/**
 * 认证服务接口
 */
public interface AuthService {
    
    /**
     * 用户登录
     */
    LoginResponse login(LoginRequest request);
    
    /**
     * 用户登出
     */
    void logout(String token);
    
    /**
     * 刷新Token
     */
    LoginResponse refreshToken(String token);
}

