package com.pisces.service.aspect;

import com.pisces.common.enums.Permission;
import com.pisces.common.enums.ResponseCode;
import com.pisces.service.annotation.RequirePermission;
import com.pisces.service.exception.BusinessException;
import com.pisces.service.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
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

import java.lang.reflect.Method;

/**
 * 权限检查切面
 */
@Slf4j
@Aspect
@Component
public class PermissionAspect {
    
    @Autowired
    private UserService userService;
    
    /**
     * 切点：所有标注了@RequirePermission的方法
     */
    @Pointcut("@annotation(com.pisces.service.annotation.RequirePermission)")
    public void permissionPointcut() {
    }
    
    /**
     * 权限检查
     */
    @Before("permissionPointcut()")
    public void checkPermission(JoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        RequirePermission requirePermission = method.getAnnotation(RequirePermission.class);
        
        if (requirePermission == null) {
            return;
        }
        
        Permission permission = requirePermission.value();
        
        // 从请求属性中获取用户名（由TokenAspect设置）
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            throw new BusinessException(ResponseCode.UNAUTHORIZED, "未登录");
        }
        
        HttpServletRequest request = attributes.getRequest();
        String username = (String) request.getAttribute("currentUsername");
        
        if (username == null || username.isEmpty()) {
            throw new BusinessException(ResponseCode.UNAUTHORIZED, "未登录");
        }
        
        // 检查权限
        if (!userService.hasPermission(username, permission)) {
            log.warn("用户 {} 没有权限 {} 执行操作", username, permission.getDescription());
            throw new BusinessException(ResponseCode.FORBIDDEN);
        }
        
        log.debug("用户 {} 权限检查通过: {}", username, permission.getDescription());
    }
}

