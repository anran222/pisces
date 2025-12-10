package com.pisces.api.auth;

import com.pisces.common.request.LoginRequest;
import com.pisces.common.response.BaseResponse;
import com.pisces.common.response.LoginResponse;
import com.pisces.service.service.AuthService;
import com.pisces.service.context.TokenContext;
import com.pisces.service.annotation.NoTokenRequired;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 认证控制器（登录/登出）
 */
@RestController
@RequestMapping("/auth")
public class AuthController {
    
    @Autowired
    private AuthService authService;
    
    /**
     * 用户登录（不需要Token）
     */
    @NoTokenRequired
    @PostMapping("/login")
    public BaseResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        LoginResponse response = authService.login(request);
        return BaseResponse.of("登录成功", response);
    }
    
    /**
     * 用户登出
     * Token由切面统一提取和验证，使用TokenContext获取
     */
    @PostMapping("/logout")
    public BaseResponse<Void> logout() {
        // 使用TokenContext获取Token（由TokenAspect切面统一提取和设置）
        String token = TokenContext.getCurrentToken();
        authService.logout(token);
        return BaseResponse.of("登出成功", null);
    }
    
    /**
     * 刷新Token
     * Token由切面统一提取和验证，使用TokenContext获取
     */
    @PostMapping("/refresh")
    public BaseResponse<LoginResponse> refreshToken() {
        // 使用TokenContext获取Token（由TokenAspect切面统一提取和设置）
        String token = TokenContext.getCurrentToken();
        LoginResponse response = authService.refreshToken(token);
        return BaseResponse.of("Token刷新成功", response);
    }
}

