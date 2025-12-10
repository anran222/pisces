package com.pisces.service.context;

import com.pisces.common.enums.ResponseCode;
import com.pisces.common.model.TokenInfo;
import com.pisces.service.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Token上下文工具类
 * 用于在Controller或Service中方便地获取当前登录用户信息
 */
@Slf4j
public class TokenContext {
    
    /**
     * 获取当前用户名
     * Token由切面统一提取和验证，这里从请求属性中获取
     */
    public static String getCurrentUsername() {
        HttpServletRequest request = getRequest();
        String username = (String) request.getAttribute("currentUsername");
        if (username == null || username.isEmpty()) {
            // 检查是否有token但验证失败的情况
            String token = (String) request.getAttribute("currentToken");
            if (token != null && !token.isEmpty()) {
                throw new BusinessException(ResponseCode.TOKEN_INVALID, "Token验证失败，无法获取用户信息。请检查Token是否有效或已过期");
            }
            throw new BusinessException(ResponseCode.UNAUTHORIZED, "未登录或Token已过期，无法获取用户信息。请先登录");
        }
        return username;
    }
    
    /**
     * 获取当前用户ID
     * Token由切面统一提取和验证，这里从请求属性中获取
     */
    public static Long getCurrentUserId() {
        HttpServletRequest request = getRequest();
        Object userIdObj = request.getAttribute("currentUserId");
        if (userIdObj == null) {
            // 检查是否有token但验证失败的情况
            String token = (String) request.getAttribute("currentToken");
            if (token != null && !token.isEmpty()) {
                throw new BusinessException(ResponseCode.TOKEN_INVALID, "Token验证失败，无法获取用户ID。请检查Token是否有效或已过期");
            }
            throw new BusinessException(ResponseCode.UNAUTHORIZED, "未登录或Token已过期，无法获取用户ID。请先登录");
        }
        if (userIdObj instanceof Long) {
            return (Long) userIdObj;
        }
        if (userIdObj instanceof Number) {
            return ((Number) userIdObj).longValue();
        }
        throw new BusinessException(ResponseCode.INTERNAL_SERVER_ERROR, "用户ID格式错误，请联系管理员");
    }
    
    /**
     * 获取当前Token
     * Token由切面统一提取，这里从请求属性中获取
     */
    public static String getCurrentToken() {
        HttpServletRequest request = getRequest();
        String token = (String) request.getAttribute("currentToken");
        if (token == null || token.isEmpty()) {
            throw new BusinessException(ResponseCode.TOKEN_MISSING, "无法获取Token信息。请确保请求头中包含Authorization: Bearer {token}或X-Token: {token}");
        }
        return token;
    }
    
    /**
     * 获取当前TokenInfo
     * Token由切面统一提取和验证，这里从请求属性中获取
     */
    public static TokenInfo getCurrentTokenInfo() {
        HttpServletRequest request = getRequest();
        TokenInfo tokenInfo = (TokenInfo) request.getAttribute("currentTokenInfo");
        if (tokenInfo == null) {
            // 检查是否有token但验证失败的情况
            String token = (String) request.getAttribute("currentToken");
            if (token != null && !token.isEmpty()) {
                throw new BusinessException(ResponseCode.TOKEN_INVALID, "Token验证失败，无法获取Token信息。请检查Token是否有效或已过期");
            }
            throw new BusinessException(ResponseCode.UNAUTHORIZED, "未登录或Token已过期，无法获取Token信息。请先登录");
        }
        return tokenInfo;
    }
    
    /**
     * 获取当前用户名（如果不存在返回null，不抛异常）
     */
    public static String getCurrentUsernameOrNull() {
        try {
            return getCurrentUsername();
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * 获取当前用户ID（如果不存在返回null，不抛异常）
     */
    public static Long getCurrentUserIdOrNull() {
        try {
            return getCurrentUserId();
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * 检查是否已登录
     */
    public static boolean isLoggedIn() {
        return getCurrentUsernameOrNull() != null;
    }
    
    /**
     * 获取HttpServletRequest
     */
    private static HttpServletRequest getRequest() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            throw new BusinessException(ResponseCode.INTERNAL_SERVER_ERROR, "无法获取请求上下文");
        }
        return attributes.getRequest();
    }
}

