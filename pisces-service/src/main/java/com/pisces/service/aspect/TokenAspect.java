package com.pisces.service.aspect;

import com.pisces.common.enums.ResponseCode;
import com.pisces.common.model.TokenInfo;
import com.pisces.service.service.TokenService;
import com.pisces.service.exception.BusinessException;
import com.pisces.service.annotation.NoTokenRequired;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;

/**
 * Token校验切面
 */
@Slf4j
@Aspect
@Component
public class TokenAspect {
    
    @Autowired
    private TokenService tokenService;
    
    /**
     * 切点：所有Controller的方法
     */
    @Pointcut("execution(* com.pisces.api..*.*(..))")
    public void apiMethods() {
    }
    
    /**
     * Token校验
     */
    @Before("apiMethods()")
    public void checkToken(JoinPoint joinPoint) {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            throw new BusinessException(ResponseCode.INTERNAL_SERVER_ERROR, "无法获取请求信息，请稍后重试");
        }
        
        HttpServletRequest request = attributes.getRequest();
        
        // 跳过OPTIONS请求（CORS预检）
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            log.debug("跳过OPTIONS请求: {}", request.getRequestURI());
            return;
        }
        
        // 检查方法是否有@NoTokenRequired注解
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        if (method.isAnnotationPresent(NoTokenRequired.class)) {
            log.debug("跳过Token校验（@NoTokenRequired）: {}", request.getRequestURI());
            return; // 不需要Token校验
        }
        
        // 检查类级别是否有@NoTokenRequired注解
        Class<?> targetClass = joinPoint.getTarget().getClass();
        if (targetClass.isAnnotationPresent(NoTokenRequired.class)) {
            log.debug("跳过Token校验（类级别@NoTokenRequired）: {}", request.getRequestURI());
            return;
        }
        
        // 统一从请求中提取Token
        String token = extractToken(request);
        
        // Token不存在或为空的情况
        if (token == null || token.isEmpty()) {
            log.warn("请求缺少Token: URI={}, Method={}", request.getRequestURI(), request.getMethod());
            throw new BusinessException(ResponseCode.TOKEN_MISSING, "未登录或Token已过期，请先登录。请在请求头中添加Authorization: Bearer {token}或X-Token: {token}");
        }
        
        // 将提取的Token先设置到请求属性中（即使验证失败，也能在Controller中获取到）
        request.setAttribute("currentToken", token);
        
        try {
            // 验证Token
            TokenInfo tokenInfo = tokenService.validateToken(token);
            
            // 验证通过后，将Token信息设置到请求属性中，供后续使用
            request.setAttribute("currentUsername", tokenInfo.getUsername());
            request.setAttribute("currentUserId", tokenInfo.getUserId());
            request.setAttribute("currentTokenInfo", tokenInfo);
            
            log.debug("Token校验通过: 用户={}, URI={}", tokenInfo.getUsername(), request.getRequestURI());
        } catch (BusinessException e) {
            // BusinessException已经包含了明确的错误信息，直接抛出
            log.warn("Token校验失败: {}, URI={}, Token={}", e.getMessage(), request.getRequestURI(), 
                token.length() > 10 ? token.substring(0, 10) + "..." : token);
            throw e;
        } catch (Exception e) {
            log.error("Token校验异常: URI={}, Token={}", request.getRequestURI(), 
                token.length() > 10 ? token.substring(0, 10) + "..." : token, e);
            throw new BusinessException(ResponseCode.TOKEN_INVALID, "Token校验失败: " + (e.getMessage() != null ? e.getMessage() : "系统异常，请稍后重试"));
        }
    }
    
    /**
     * 从请求中统一提取Token
     * 支持多种方式：Authorization头、X-Token头、token参数
     * 
     * @param request HTTP请求
     * @return Token字符串，如果未找到返回null
     */
    private String extractToken(HttpServletRequest request) {
        String token = null;
        String source = null;
        
        // 方式1：优先从Authorization头获取（推荐方式）
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && !authHeader.isEmpty()) {
            // 如果Token以Bearer开头，提取实际Token
            if (authHeader.startsWith("Bearer ")) {
                token = authHeader.substring(7).trim();
                source = "Authorization Bearer";
            } else if (authHeader.startsWith("bearer ")) {
                // 兼容小写bearer
                token = authHeader.substring(7).trim();
                source = "Authorization bearer";
            } else {
                // 如果没有Bearer前缀，直接使用整个值
                token = authHeader.trim();
                source = "Authorization";
            }
            
            if (!token.isEmpty()) {
                log.debug("从{}头提取Token: URI={}", source, request.getRequestURI());
                return token;
            }
        }
        
        // 方式2：从X-Token头获取
        token = request.getHeader("X-Token");
        if (token != null && !token.isEmpty()) {
            token = token.trim();
            if (!token.isEmpty()) {
                log.debug("从X-Token头提取Token: URI={}", request.getRequestURI());
                return token;
            }
        }
        
        // 方式3：从请求参数获取（不推荐，但为了兼容性保留）
        token = request.getParameter("token");
        if (token != null && !token.isEmpty()) {
            token = token.trim();
            if (!token.isEmpty()) {
                log.warn("从请求参数提取Token（不推荐）: URI={}", request.getRequestURI());
                return token;
            }
        }
        
        // 未找到Token
        log.debug("未找到Token: URI={}, 已检查Authorization、X-Token头和token参数", request.getRequestURI());
        return null;
    }
}

