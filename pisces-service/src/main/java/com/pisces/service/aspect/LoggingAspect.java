package com.pisces.service.aspect;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;

/**
 * 日志切面
 */
@Slf4j
@Aspect
@Component
public class LoggingAspect {
    
    /**
     * 切点：所有service实现类的方法
     */
    @Pointcut("execution(* com.pisces.service.impl.*.*(..))")
    public void serviceMethods() {
    }
    
    /**
     * 环绕通知：记录方法执行时间和参数
     */
    @Around("serviceMethods()")
    public Object logExecutionTime(ProceedingJoinPoint joinPoint) throws Throwable {
        long startTime = System.currentTimeMillis();
        String methodName = joinPoint.getSignature().getName();
        String className = joinPoint.getTarget().getClass().getSimpleName();
        
        log.debug("开始执行方法: {}.{}", className, methodName);
        
        try {
            Object result = joinPoint.proceed();
            long executionTime = System.currentTimeMillis() - startTime;
            log.debug("方法执行完成: {}.{}, 耗时: {}ms", className, methodName, executionTime);
            return result;
        } catch (Throwable e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("方法执行异常: {}.{}, 耗时: {}ms, 异常: {}", className, methodName, executionTime, e.getMessage());
            throw e;
        }
    }
}

