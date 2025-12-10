package com.pisces.service.service.impl;

import com.pisces.common.enums.ResponseCode;
import com.pisces.common.model.TokenInfo;
import com.pisces.common.request.LoginRequest;
import com.pisces.common.response.LoginResponse;
import com.pisces.common.response.UserResponse;
import com.pisces.service.exception.BusinessException;
import com.pisces.service.service.AuthService;
import com.pisces.service.service.UserService;
import com.pisces.service.service.TokenService;
import com.pisces.service.model.entity.UserEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;

/**
 * 认证服务实现
 */
@Slf4j
@Service
public class AuthServiceImpl implements AuthService {
    
    @Autowired
    private UserService userService;
    
    @Autowired
    private TokenService tokenService;
    
    @Value("${token.expire-hours:24}")
    private int expireHours;
    
    /**
     * 用户登录
     */
    @Override
    public LoginResponse login(LoginRequest request) {
        // 查询用户
        UserEntity user = userService.getUserByUsername(request.getUsername());
        if (user == null) {
            throw new BusinessException(ResponseCode.USER_PASSWORD_ERROR);
        }
        
        // 检查用户状态
        if (user.getStatus() != UserEntity.UserStatus.ACTIVE) {
            throw new BusinessException(ResponseCode.USER_STATUS_ERROR, "用户状态异常，无法登录");
        }
        
        // 验证密码
        String encryptedPassword = encryptPassword(request.getPassword());
        if (!encryptedPassword.equals(user.getPassword())) {
            throw new BusinessException(ResponseCode.USER_PASSWORD_ERROR);
        }
        
        // 生成Token
        String token = tokenService.generateToken(request.getUsername());
        
        // 构建响应
        LoginResponse response = new LoginResponse();
        response.setToken(token);
        response.setExpireIn((long) expireHours * 3600); // 转换为秒
        
        // 获取用户信息
        UserResponse userResponse = userService.getUserById(user.getId());
        response.setUser(userResponse);
        
        log.info("用户登录成功: {}", request.getUsername());
        return response;
    }
    
    /**
     * 用户登出
     */
    @Override
    public void logout(String token) {
        tokenService.removeToken(token);
        log.info("用户登出成功");
    }
    
    /**
     * 刷新Token
     */
    @Override
    public LoginResponse refreshToken(String token) {
        // 刷新Token
        TokenInfo newTokenInfo = tokenService.refreshToken(token);
        
        // 获取用户信息
        UserResponse userResponse = userService.getUserById(newTokenInfo.getUserId());
        
        // 构建响应
        LoginResponse response = new LoginResponse();
        response.setToken(newTokenInfo.getToken());
        response.setExpireIn((long) expireHours * 3600); // 转换为秒
        response.setUser(userResponse);
        
        log.info("Token刷新成功: 用户={}", newTokenInfo.getUsername());
        return response;
    }
    
    /**
     * 加密密码
     */
    private String encryptPassword(String password) {
        return DigestUtils.md5DigestAsHex(password.getBytes(StandardCharsets.UTF_8));
    }
}

